package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.param.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.time.Instant;
import java.util.*;

/** Read-only verification of an already bound subscription. The caller supplies the original
 * account's verified client and persisted catalog mappings, capturing its local fence before IO.
 * Creation/Setup/CHANGE proof is deliberately not inferred from status or metadata. */
public final class StripeBillingSnapshotMapper {
    private StripeBillingSnapshotMapper() {}
    public record PublishedPrice(SubscriptionPriceTerms terms,String providerPriceReference,String providerProductReference) {}
    public record Ownership(PaymentAccount account,Subscription subscription,CustomerBinding customer,List<PublishedPrice> prices) {
        public Ownership { prices=List.copyOf(prices); }
    }
    private static final int PAGE_SIZE=10, MAX_PAGES=10, MAX_READS=100;
    public static Snapshot retrieve(StripeClient client,Ownership owner,Instant observedAt) {
        try {return new Read(client,owner,observedAt).snapshot();}
        catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException | RuntimeException malformed){throw mismatch();}
    }
    @FunctionalInterface private interface Call<T>{T run() throws StripeException;}
    @FunctionalInterface private interface Page<T extends HasId>{StripeCollection<T> read(String after) throws StripeException;}
    private static final class Read {
        final StripeClient client;final Ownership owner;final Instant at;
        final Map<String,PublishedPrice> prices=new HashMap<>();final Set<String> cashIds=new HashSet<>();
        int requests;
        Read(StripeClient client,Ownership owner,Instant at){this.client=Objects.requireNonNull(client);this.owner=Objects.requireNonNull(owner);this.at=Objects.requireNonNull(at);}
        <T> T get(Call<T> call) throws StripeException {require(++requests<=MAX_READS);return Objects.requireNonNull(call.run());}
        <T extends HasId> List<T> pages(Page<T> call) throws StripeException {
            var all=new ArrayList<T>();var ids=new HashSet<String>();String cursor=null;
            for(int p=0;p<MAX_PAGES;p++){
                String after=cursor;var page=get(()->call.read(after));
                require("list".equals(page.getObject())&&page.getData()!=null&&page.getHasMore()!=null&&page.getData().size()<=PAGE_SIZE);
                for(var item:page.getData()){require(item!=null&&item.getId()!=null&&ids.add(item.getId()));all.add(item);}
                if(!page.getHasMore())return List.copyOf(all);
                require(!page.getData().isEmpty());cursor=page.getData().getLast().getId();
            }
            throw mismatch();
        }
        Snapshot snapshot() throws StripeException {
            var a=owner.account();var s=owner.subscription();var b=owner.customer();
            require(a!=null&&s!=null&&b!=null&&a.provider()==PaymentProviderType.STRIPE&&a.mode()==PaymentAccountMode.SANDBOX
                &&a.id().equals(s.paymentAccountId())&&a.id().equals(b.accountId())&&s.siteId().equals(b.siteId())&&s.customerId().equals(b.customerId()));
            ref(s.providerReference(),"sub_");ref(b.providerReference(),"cus_");
            for(var p:owner.prices()){
                require(p!=null&&p.terms()!=null&&s.productId().equals(p.terms().productId())&&p.terms().taxMode()!=dev.genericpayments.platform.catalog.domain.TaxMode.EXCLUSIVE);
                ref(p.providerPriceReference(),"price_");ref(p.providerProductReference(),"prod_");require(prices.put(p.providerPriceReference(),p)==null);
            }
            var customer=get(()->client.v1().customers().retrieve(b.providerReference()));
            require(b.providerReference().equals(customer.getId())&&"customer".equals(customer.getObject())&&Boolean.FALSE.equals(customer.getLivemode())&&!Boolean.TRUE.equals(customer.getDeleted()));
            var remote=get(()->client.v1().subscriptions().retrieve(s.providerReference()));
            require(s.providerReference().equals(remote.getId())&&"subscription".equals(remote.getObject())&&Boolean.FALSE.equals(remote.getLivemode())&&b.providerReference().equals(remote.getCustomer())
                &&remote.getPauseCollection()==null&&"charge_automatically".equals(remote.getCollectionMethod())&&remote.getBillingMode()!=null&&"classic".equals(remote.getBillingMode().getType()));
            var items=remote.getItems();require(items!=null&&Boolean.FALSE.equals(items.getHasMore())&&items.getData()!=null&&items.getData().size()==1);
            var item=items.getData().getFirst();ref(item.getId(),"si_");require("subscription_item".equals(item.getObject())&&s.providerReference().equals(item.getSubscription())&&Objects.equals(1L,item.getQuantity())&&!Boolean.TRUE.equals(item.getDeleted()));
            var price=item.getPrice();require(price!=null);var mapped=prices.get(price.getId());require(mapped!=null);validatePrice(price,mapped);
            var period=period(item.getCurrentPeriodStart(),item.getCurrentPeriodEnd());currency(remote.getCurrency(),mapped.terms());
            var status=switch(remote.getStatus()){
                case "active"->Subscription.Status.ACTIVE;case "trialing"->Subscription.Status.TRIALING;case "incomplete"->Subscription.Status.INCOMPLETE;
                case "incomplete_expired","canceled"->Subscription.Status.CANCELLED;case "past_due"->Subscription.Status.PAST_DUE;case "unpaid"->Subscription.Status.UNPAID;case "paused"->Subscription.Status.PAUSED;
                default->throw mismatch();};
            Subscription.Period trial=null;
            // First trial requires original durable CREATE proof supplied by the trial task.
            if(s.trial()!=null){require(s.trial().equals(period(remote.getTrialStart(),remote.getTrialEnd())));trial=s.trial();}
            var invoices=pages(after->client.v1().invoices().list(InvoiceListParams.builder().setSubscription(remote.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            var normalized=new ArrayList<SubscriptionProviderModels.Invoice>();
            for(var invoice:invoices)normalized.add(invoice(invoice,remote,item,trial));
            Instant cancel=remote.getCancelAt()==null?null:Instant.ofEpochSecond(remote.getCancelAt());
            if(Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())){if(cancel==null)cancel=period.end();else require(cancel.equals(period.end()));}
            return new Snapshot(remote.getId(),status,mapped.terms(),period,trial,cancel,normalized,null,false,0,at,null,List.of());
        }
        void validatePrice(Price p,PublishedPrice mapped){
            var t=mapped.terms();var recurring=p.getRecurring();
            require("price".equals(p.getObject())&&Boolean.FALSE.equals(p.getLivemode())&&mapped.providerProductReference().equals(p.getProduct())&&Objects.equals(p.getUnitAmount(),t.regularAmount().minorUnits())
                &&"inclusive".equals(p.getTaxBehavior())&&"recurring".equals(p.getType())&&recurring!=null&&Objects.equals(recurring.getIntervalCount(),1L)
                &&"licensed".equals(recurring.getUsageType())&&t.interval().name().toLowerCase(Locale.ROOT).equals(recurring.getInterval())
                &&p.getMetadata()!=null&&t.priceId().value().toString().equals(p.getMetadata().get("local_price_id"))&&Integer.toString(t.version()).equals(p.getMetadata().get("local_price_version")));
            currency(p.getCurrency(),t);
        }
        SubscriptionProviderModels.Invoice invoice(com.stripe.model.Invoice invoice,com.stripe.model.Subscription remote,SubscriptionItem item,Subscription.Period trial) throws StripeException {
            ref(invoice.getId(),"in_");require("invoice".equals(invoice.getObject())&&Boolean.FALSE.equals(invoice.getLivemode())&&owner.customer().providerReference().equals(invoice.getCustomer())
                &&invoice.getParent()!=null&&"subscription_details".equals(invoice.getParent().getType())&&invoice.getParent().getSubscriptionDetails()!=null&&remote.getId().equals(invoice.getParent().getSubscriptionDetails().getSubscription()));
            var lines=pages(after->client.v1().invoices().lineItems().list(invoice.getId(),InvoiceLineItemListParams.builder().setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            // Multi-line proration needs the original quote's exact debit/credit proof.
            require(lines.size()==1);var line=lines.getFirst();ref(line.getId(),"il_");
            require("line_item".equals(line.getObject())&&Boolean.FALSE.equals(line.getLivemode())&&invoice.getId().equals(line.getInvoice())&&remote.getId().equals(line.getSubscription())&&Objects.equals(line.getQuantity(),1L)
                &&line.getParent()!=null&&"subscription_item_details".equals(line.getParent().getType())&&line.getParent().getSubscriptionItemDetails()!=null);
            var detail=line.getParent().getSubscriptionItemDetails();require(remote.getId().equals(detail.getSubscription())&&item.getId().equals(detail.getSubscriptionItem())&&detail.getProration()!=null);
            require(line.getPricing()!=null&&"price_details".equals(line.getPricing().getType())&&line.getPricing().getPriceDetails()!=null);
            var pricing=line.getPricing().getPriceDetails();var mapped=prices.get(pricing.getPrice());require(mapped!=null&&mapped.providerProductReference().equals(pricing.getProduct()));
            currency(invoice.getCurrency(),mapped.terms());currency(line.getCurrency(),mapped.terms());
            require(line.getPeriod()!=null);var period=period(line.getPeriod().getStart(),line.getPeriod().getEnd());require(invoice.getAmountDue()!=null&&invoice.getAmountDue()>=0&&line.getAmount()!=null);
            var kind=switch(invoice.getBillingReason()){case "subscription_create"->BillingRecord.Kind.INITIAL;case "subscription_cycle"->BillingRecord.Kind.RENEWAL;case "subscription_update"->BillingRecord.Kind.PRORATION;default->throw mismatch();};
            var allocations=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            for(var allocation:allocations){ref(allocation.getId(),"inpay_");require("invoice_payment".equals(allocation.getObject())&&Boolean.FALSE.equals(allocation.getLivemode())&&invoice.getId().equals(allocation.getInvoice()));currency(allocation.getCurrency(),mapped.terms());}
            SettlementKind settlement=SettlementKind.UNSUPPORTED;List<CashObject> cash=List.of();
            boolean plain=!detail.getProration()&&kind!=BillingRecord.Kind.PRORATION&&"charge_automatically".equals(invoice.getCollectionMethod())
                &&zeros(invoice.getAmountPaidOffStripe(),invoice.getAmountOverpaid(),invoice.getStartingBalance(),invoice.getEndingBalance(),invoice.getPrePaymentCreditNotesAmount(),invoice.getPostPaymentCreditNotesAmount())
                &&empty(invoice.getDiscounts())&&empty(invoice.getTotalDiscountAmounts())&&empty(invoice.getTotalPretaxCreditAmounts())&&empty(line.getDiscountAmounts())&&empty(line.getPretaxCreditAmounts())
                &&Objects.equals(invoice.getTotal(),invoice.getAmountDue())&&Objects.equals(invoice.getSubtotal(),invoice.getAmountDue())&&Objects.equals(line.getAmount(),invoice.getAmountDue());
            switch(invoice.getStatus()){
                case "paid"->{
                    if(plain&&invoice.getAmountDue()==0&&zeros(invoice.getAmountPaid(),invoice.getAmountRemaining())&&allocations.isEmpty()
                        &&kind==BillingRecord.Kind.INITIAL&&trial!=null&&trial.equals(period)&&"trialing".equals(remote.getStatus())&&mapped.terms().equals(owner.subscription().billingPrice()))settlement=SettlementKind.ZERO;
                    else if(plain&&invoice.getAmountDue()>0&&invoice.getAmountDue()==mapped.terms().regularAmount().minorUnits()&&Objects.equals(invoice.getAmountPaid(),invoice.getAmountDue())&&zeros(invoice.getAmountRemaining())&&allocations.size()==1){
                        var confirmed=capture(invoice,allocations.getFirst());if(confirmed!=null){require(cashIds.add(confirmed.reference()));cash=List.of(confirmed);settlement=SettlementKind.CASH;}
                    }
                }
                case "open","draft","uncollectible"->{if(plain&&invoice.getAmountDue()>0&&invoice.getAmountDue()==mapped.terms().regularAmount().minorUnits()&&zeros(invoice.getAmountPaid())&&Objects.equals(invoice.getAmountRemaining(),invoice.getAmountDue())&&allocations.stream().allMatch(p->("open".equals(p.getStatus())||"canceled".equals(p.getStatus()))&&p.getPayment()!=null&&"payment_intent".equals(p.getPayment().getType())&&p.getPayment().getPaymentIntent()!=null))settlement=SettlementKind.OPEN;}
                case "void"->{if(plain&&allocations.isEmpty())settlement=SettlementKind.VOID;}
                default->throw mismatch();
            }
            return new SubscriptionProviderModels.Invoice(invoice.getId(),kind,mapped.terms(),null,period,invoice.getAmountDue(),mapped.terms().regularAmount().currency(),settlement,cash);
        }
        CashObject capture(com.stripe.model.Invoice invoice,InvoicePayment allocation) throws StripeException {
            var payment=allocation.getPayment();long due=invoice.getAmountDue();
            if(!"paid".equals(allocation.getStatus())||!Objects.equals(allocation.getAmountPaid(),due)||!Objects.equals(allocation.getAmountRequested(),due)||payment==null||!"payment_intent".equals(payment.getType()))return null;
            ref(payment.getPaymentIntent(),"pi_");var intent=get(()->client.v1().paymentIntents().retrieve(payment.getPaymentIntent()));
            require(payment.getPaymentIntent().equals(intent.getId())&&"payment_intent".equals(intent.getObject())&&Boolean.FALSE.equals(intent.getLivemode())&&owner.customer().providerReference().equals(intent.getCustomer())&&invoice.getCurrency().equals(intent.getCurrency()));
            if(!"succeeded".equals(intent.getStatus())||!"automatic".equals(intent.getCaptureMethod())||!List.of("card").equals(intent.getPaymentMethodTypes())||!Objects.equals(intent.getAmount(),due)||!Objects.equals(intent.getAmountReceived(),due))return null;
            ref(intent.getLatestCharge(),"ch_");var charge=get(()->client.v1().charges().retrieve(intent.getLatestCharge()));
            require(intent.getLatestCharge().equals(charge.getId())&&"charge".equals(charge.getObject())&&Boolean.FALSE.equals(charge.getLivemode())&&owner.customer().providerReference().equals(charge.getCustomer())&&intent.getId().equals(charge.getPaymentIntent())&&invoice.getCurrency().equals(charge.getCurrency()));
            if(!Boolean.TRUE.equals(charge.getCaptured())||!Boolean.TRUE.equals(charge.getPaid())||!"succeeded".equals(charge.getStatus())||!Objects.equals(charge.getAmount(),due)||!Objects.equals(charge.getAmountCaptured(),due)||charge.getPaymentMethodDetails()==null||!"card".equals(charge.getPaymentMethodDetails().getType()))return null;
            var reverse=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setPayment(InvoicePaymentListParams.Payment.builder().setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT).setPaymentIntent(intent.getId()).build()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            if(reverse.size()!=1)return null;var sole=reverse.getFirst();require(sole.equals(allocation));
            require(allocation.getStatusTransitions()!=null&&allocation.getStatusTransitions().getPaidAt()!=null&&allocation.getStatusTransitions().getPaidAt()>0);
            // Refunds are separate later facts; retain original captured gross PI identity.
            return new CashObject(intent.getId(),due,invoice.getCurrency().toUpperCase(Locale.ROOT),PaymentStatus.SUCCEEDED,Instant.ofEpochSecond(allocation.getStatusTransitions().getPaidAt()));
        }
    }
    private static Subscription.Period period(Long start,Long end){require(start!=null&&end!=null&&start>0&&end>start);return new Subscription.Period(Instant.ofEpochSecond(start),Instant.ofEpochSecond(end));}
    private static boolean zeros(Long... values){return Arrays.stream(values).allMatch(v->Objects.equals(v,0L));}
    private static boolean empty(List<?> list){return list!=null&&list.isEmpty();}
    private static void currency(String value,SubscriptionPriceTerms terms){require(terms.regularAmount().currency().toLowerCase(Locale.ROOT).equals(value));}
    private static void ref(String value,String prefix){require(value!=null&&value.matches(prefix+"[A-Za-z0-9_]{1,200}"));}
    private static void require(boolean value){if(!value)throw mismatch();}
    private static PaymentApplicationException mismatch(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider billing evidence does not match");}
}
