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
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import dev.genericpayments.platform.subscriptions.domain.ChangeTiming;
import java.time.Instant;
import java.util.*;

/** Read-only verification of an already bound subscription. The caller supplies the original
 * account's verified client and persisted catalog mappings, capturing its local fence before IO.
 * Creation/Setup/CHANGE proof is deliberately not inferred from status or metadata. */
public final class StripeBillingSnapshotMapper {
    private StripeBillingSnapshotMapper() {}
    private static final com.fasterxml.jackson.databind.ObjectMapper NATIVE_JSON=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record PublishedPrice(SubscriptionPriceTerms terms,String providerPriceReference,String providerProductReference) {}
    public record TrialContext(SubscriptionBillingProofStore.Trial request,dev.genericpayments.platform.provider.application.model.ProviderOperation step,PublishedPrice original,SubscriptionBillingProofStore.Setup requiredSetup){}
    public record Ownership(PaymentAccount account,Subscription subscription,CustomerBinding customer,List<PublishedPrice> prices,TrialContext trial,SubscriptionBillingProofStore proofs,List<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration> prorations,List<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.ScheduleProof> schedules) {
        public Ownership(PaymentAccount a,Subscription s,CustomerBinding b,List<PublishedPrice> prices){this(a,s,b,prices,null,null,List.of());}
        public Ownership(PaymentAccount a,Subscription s,CustomerBinding b,List<PublishedPrice> prices,TrialContext trial,SubscriptionBillingProofStore proofs){this(a,s,b,prices,trial,proofs,List.of());}
        public Ownership(PaymentAccount a,Subscription s,CustomerBinding b,List<PublishedPrice> prices,TrialContext trial,SubscriptionBillingProofStore proofs,List<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration> prorations){this(a,s,b,prices,trial,proofs,prorations,List.of());}
        public Ownership { prices=List.copyOf(prices);prorations=List.copyOf(prorations);schedules=List.copyOf(schedules); }
    }
    private static final int PAGE_SIZE=10, MAX_PAGES=10, MAX_READS=100;
    public static Snapshot retrieve(StripeClient client,Ownership owner,Instant observedAt) {
        try {return new Read(client,owner,observedAt).snapshot();}
        catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException | RuntimeException malformed){throw mismatch();}
    }
    /** Exact credited native line, funded using the same complete normalization as ordinary reads. */
    static void requireCreditedFunding(StripeClient client,Ownership owner,com.stripe.model.Subscription remote,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote quote,Instant at){
        var linked=quote.lines().stream().filter(l->l.creditedInvoice()!=null).toList();if(linked.isEmpty())return;
        require(linked.size()==1);var credit=linked.getFirst();require(credit.creditedLines().size()==1);
        try{
            var read=new Read(client,owner,at);for(var p:owner.prices())require(read.prices.put(p.providerPriceReference(),p)==null);
            ref(credit.creditedInvoice(),"in_");ref(credit.creditedLines().getFirst(),"il_");
            var invoice=read.get(()->client.v1().invoices().retrieve(credit.creditedInvoice()));require(credit.creditedInvoice().equals(invoice.getId())&&StripeBillingMutationAdapter.noAdjustments(invoice));
            var normalized=read.invoice(invoice,remote,remote.getItems().getData().getFirst(),null);
            require(normalized.price().equals(quote.source())&&!normalized.period().start().isAfter(quote.anchor())&&!normalized.period().end().isBefore(quote.paidThrough())
                &&(normalized.settlement()==SettlementKind.CASH||normalized.settlement()==SettlementKind.ZERO&&normalized.kind()==BillingRecord.Kind.PRORATION));
            var lines=read.sourceLines.get(invoice.getId());require(lines!=null);var literal=lines.stream().filter(l->credit.creditedLines().getFirst().equals(l.getId())).toList();require(literal.size()==1);var line=literal.getFirst();
            require(invoice.getId().equals(line.getInvoice())&&quote.subscriptionReference().equals(line.getSubscription())&&line.getAmount()!=null&&line.getAmount()>0
                &&(line.getTaxes()==null||line.getTaxes().isEmpty())&&(line.getDiscounts()==null||line.getDiscounts().isEmpty())
                &&line.getParent()!=null&&line.getParent().getSubscriptionItemDetails()!=null&&quote.subscriptionReference().equals(line.getParent().getSubscriptionItemDetails().getSubscription())
                &&quote.item().equals(line.getParent().getSubscriptionItemDetails().getSubscriptionItem())&&line.getPricing()!=null&&line.getPricing().getPriceDetails()!=null
                &&quote.sourcePrice().equals(line.getPricing().getPriceDetails().getPrice())&&quote.product().equals(line.getPricing().getPriceDetails().getProduct())
                &&line.getPeriod()!=null&&line.getPeriod().getStart()!=null&&line.getPeriod().getEnd()!=null&&line.getPeriod().getStart()<=quote.anchor().getEpochSecond()&&line.getPeriod().getEnd()>=quote.paidThrough().getEpochSecond());
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException malformed){throw mismatch();}
    }
    /** Ephemeral, verified original Billing action; never serialize as a normal snapshot. */
    public record PaymentAction(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding binding,String clientSecret){
        @Override public String toString(){return "PaymentAction[redacted]";}
    }
    public static PaymentAction retrievePaymentAction(StripeClient client,StripeBillingOwnership.PaymentProof proof,Instant at){
        try{
            var r=new Read(client,new Ownership(proof.account(),proof.subscription(),proof.customer(),proof.prices(),null,null,proof.history()),at);
            var snapshot=r.snapshot();
            List<SubscriptionProviderModels.Invoice> selected;
            if(proof instanceof StripeBillingOwnership.OriginalChange change){
                var q=change.result().quote();var original=change.result().result();
                require(q.source().equals(snapshot.billingPrice())&&q.period().equals(snapshot.period())&&snapshot.status()==Subscription.Status.ACTIVE&&q.entitled().equals(proof.subscription().entitledPrice()));
                var item=r.remote.getItems().getData().getFirst();var pending=r.remote.getPendingUpdate();
                require(q.item().equals(item.getId())&&pending!=null&&original.pendingExpiry()!=null&&Objects.equals(pending.getExpiresAt(),original.pendingExpiry())&&at.getEpochSecond()<original.pendingExpiry()&&pending.getSubscriptionItems()!=null&&pending.getSubscriptionItems().size()==1);
                var target=pending.getSubscriptionItems().getFirst();require(q.item().equals(target.getId())&&Objects.equals(1L,target.getQuantity())&&target.getPrice()!=null&&q.targetPrice().equals(target.getPrice().getId()));
                selected=snapshot.invoices().stream().filter(i->i.reference().equals(original.invoice())&&i.kind()==BillingRecord.Kind.PRORATION&&i.price().equals(q.target())&&Objects.equals(i.previousPrice(),q.source())&&i.period().equals(new Subscription.Period(q.anchor(),q.paidThrough()))).toList();
            }else if(proof instanceof StripeBillingOwnership.OriginalResume resume){
                StripeBillingOwnership.requireCurrentRemote(r.remote,proof.subscription(),proof.customer(),proof.price());
                require(Set.of("active","paused","incomplete","past_due").contains(r.remote.getStatus()));
                selected=snapshot.invoices().stream().filter(i->i.reference().equals(resume.result().invoice())&&i.kind()==BillingRecord.Kind.RENEWAL&&i.period().equals(resume.result().period())).toList();
            }else if(proof instanceof StripeBillingOwnership.OriginalRecovery recovery){
                StripeBillingOwnership.requireCurrentRemote(r.remote,proof.subscription(),proof.customer(),proof.price());
                selected=snapshot.invoices().stream().filter(i->exactRecoveryInvoice(i,recovery.bill())).toList();
            }else{
                StripeBillingOwnership.requireRemote(r.remote,proof.account(),proof.subscription(),proof.operation(),proof.customer(),proof.price(),proof.step());
                require("incomplete".equals(r.remote.getStatus()));
                selected=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.INITIAL).toList();
            }
            require(selected.size()==1);
            var i=selected.getFirst();require(i.settlement()==SettlementKind.OPEN&&i.price().equals(proof.price().terms())&&(proof instanceof StripeBillingOwnership.OriginalChange||proof instanceof StripeBillingOwnership.OriginalRecovery||i.period().equals(snapshot.period())));
            return paymentAction(r,proof,i);
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException unsafe){throw mismatch();}
    }
    private static PaymentAction paymentAction(Read r,StripeBillingOwnership.PaymentProof proof,SubscriptionProviderModels.Invoice i) throws StripeException {
            var client=r.client;
            var invoice=r.sourceInvoices.stream().filter(v->v.getId().equals(i.reference())).findFirst().orElseThrow(StripeBillingSnapshotMapper::mismatch);
            require("open".equals(invoice.getStatus()));
            if(proof instanceof StripeBillingOwnership.OriginalResume||proof instanceof StripeBillingOwnership.OriginalChange)require(StripeBillingOwnership.inWindow(invoice.getCreated(),proof.step()));
            var allocations=r.sourceAllocations.get(i.reference());require(allocations!=null&&allocations.size()==1);
            var a=allocations.getFirst();long due=i.amountDueMinor();require("open".equals(a.getStatus())&&Boolean.TRUE.equals(a.getIsDefault())&&Objects.equals(due,a.getAmountRequested())&&zeros(a.getAmountPaid())&&a.getPayment()!=null&&"payment_intent".equals(a.getPayment().getType()));
            ref(a.getPayment().getPaymentIntent(),"pi_");var pi=r.get(()->client.v1().paymentIntents().retrieve(a.getPayment().getPaymentIntent()));
            require(a.getPayment().getPaymentIntent().equals(pi.getId())&&"payment_intent".equals(pi.getObject())&&Boolean.FALSE.equals(pi.getLivemode())&&proof.customer().providerReference().equals(pi.getCustomer())
                &&invoice.getCurrency().equals(pi.getCurrency())&&Objects.equals(due,pi.getAmount())&&zeros(pi.getAmountReceived())&&"automatic".equals(pi.getCaptureMethod())&&List.of("card").equals(pi.getPaymentMethodTypes())
                &&Set.of("requires_payment_method","requires_action").contains(pi.getStatus()));
            if(pi.getLatestCharge()!=null){
                ref(pi.getLatestCharge(),"ch_");var ch=r.get(()->client.v1().charges().retrieve(pi.getLatestCharge()));
                require(pi.getLatestCharge().equals(ch.getId())&&"charge".equals(ch.getObject())&&Boolean.FALSE.equals(ch.getLivemode())&&pi.getId().equals(ch.getPaymentIntent())&&proof.customer().providerReference().equals(ch.getCustomer())&&pi.getCurrency().equals(ch.getCurrency())
                    &&Objects.equals(due,ch.getAmount())&&zeros(ch.getAmountCaptured())&&Boolean.FALSE.equals(ch.getPaid())&&Boolean.FALSE.equals(ch.getCaptured())&&"failed".equals(ch.getStatus())&&ch.getPaymentMethodDetails()!=null&&"card".equals(ch.getPaymentMethodDetails().getType()));
            }
            var reverse=r.pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setPayment(InvoicePaymentListParams.Payment.builder().setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT).setPaymentIntent(pi.getId()).build()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            require(reverse.size()==1&&a.equals(reverse.getFirst()));
            String secret=pi.getClientSecret();require(secret!=null&&secret.startsWith(pi.getId()+"_secret_")&&secret.length()<=1024&&secret.matches("[A-Za-z0-9_-]+"));
            if(invoice.getConfirmationSecret()!=null)require("payment_intent".equals(invoice.getConfirmationSecret().getType())&&secret.equals(invoice.getConfirmationSecret().getClientSecret()));
            String canonical=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical(proof.step().fingerprint(),proof.operation().fingerprint(),i.period().start(),i.period().end());
            if(proof instanceof StripeBillingOwnership.OriginalChange change)canonical=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical("CHANGE_V1",canonical,change.result().quote().toString(),change.result().result().creditLine(),change.result().result().debitLine());
            if(proof instanceof StripeBillingOwnership.OriginalRecovery recovery){
                canonical=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical("RECOVERY_V1",canonical,recovery.bill().id(),recovery.origin().operation(),recovery.origin().step());
                var saved=recovery.result();if(saved!=null)require(Objects.equals(saved.allocation(),a.getId())&&Objects.equals(saved.intent(),pi.getId()));
            }
            var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(proof.step().id(),proof.operation().providerOperationKey(),StripeBillingGrantTokens.hash(canonical),proof.subscription().providerReference(),proof.customer().providerReference(),i.reference(),a.getId(),pi.getId(),proof instanceof StripeBillingOwnership.OriginalRecovery?"recovery-v1":proof instanceof StripeBillingOwnership.OriginalChange?"change-v1":proof instanceof StripeBillingOwnership.OriginalResume?"resume-v1":"payment-v1",null);
            return new PaymentAction(binding,secret);
    }
    public record RecoveryRead(Snapshot snapshot,String allocation,String intent,String method,boolean firstPay) {}
    public static RecoveryRead retrieveRecovery(StripeClient client,StripeBillingOwnership.OriginalRecovery proof,Instant at){
        try{
            var r=new Read(client,new Ownership(proof.account(),proof.subscription(),proof.customer(),proof.prices(),null,null,proof.history()),at);var snapshot=r.snapshot();
            StripeBillingOwnership.requireCurrentRemote(r.remote,proof.subscription(),proof.customer(),proof.price());
            require(r.remote.getSchedule()==null&&r.remote.getPendingUpdate()==null&&Set.of("active","past_due","unpaid","incomplete").contains(r.remote.getStatus()));
            var selected=snapshot.invoices().stream().filter(i->exactRecoveryInvoice(i,proof.bill())).toList();require(selected.size()==1);var i=selected.getFirst();
            if(i.settlement()==SettlementKind.CASH)return new RecoveryRead(snapshot,null,null,null,false);
            require(i.settlement()==SettlementKind.OPEN);var allocations=r.sourceAllocations.get(i.reference());require(allocations!=null);
            if(allocations.size()==1){var action=paymentAction(r,proof,i);return new RecoveryRead(snapshot,action.binding().allocationReference(),action.binding().intentReference(),null,false);}
            require(allocations.isEmpty());var invoice=r.sourceInvoices.stream().filter(v->v.getId().equals(i.reference())).findFirst().orElseThrow(StripeBillingSnapshotMapper::mismatch);
            boolean first=Boolean.FALSE.equals(invoice.getAttempted())&&Objects.equals(0L,invoice.getAttemptCount())&&Boolean.FALSE.equals(invoice.getAutoAdvance())&&invoice.getNextPaymentAttempt()==null
                &&r.explicitNoNextAttempt.contains(invoice.getId())
                &&invoice.getConfirmationSecret()==null&&StripeBillingMutationAdapter.noAdjustments(invoice)&&snapshot.paymentMethodReady()
                &&Objects.equals(invoice.getAmountPaid(),0L)&&Objects.equals(invoice.getAmountRemaining(),proof.bill().amountDueMinor());
            return new RecoveryRead(snapshot,null,null,first?r.remote.getDefaultPaymentMethod():null,first);
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException unsafe){throw mismatch();}
    }
    private static boolean exactRecoveryInvoice(SubscriptionProviderModels.Invoice invoice,BillingRecord bill){return invoice.reference().equals(bill.providerInvoiceReference())&&invoice.kind()==bill.kind()&&invoice.price().equals(bill.billedPrice())&&Objects.equals(invoice.previousPrice(),bill.previousPrice())&&invoice.period().equals(bill.period())&&invoice.amountDueMinor()==bill.amountDueMinor();}
    @FunctionalInterface private interface Call<T>{T run() throws StripeException;}
    @FunctionalInterface private interface Page<T extends HasId>{StripeCollection<T> read(String after) throws StripeException;}
    private static final class Read {
        final StripeClient client;final Ownership owner;final Instant at;
        final Map<String,PublishedPrice> prices=new HashMap<>();final Set<String> cashIds=new HashSet<>();
        com.stripe.model.Subscription remote;List<com.stripe.model.Invoice> sourceInvoices;
        final Map<String,List<InvoicePayment>> sourceAllocations=new HashMap<>();
        final Map<String,List<InvoiceLineItem>> sourceLines=new HashMap<>();
        final Set<String> explicitNoNextAttempt=new HashSet<>();
        int requests;
        TrialEvidence trialEvidence;
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
            remote=get(()->client.v1().subscriptions().retrieve(s.providerReference()));
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
            if(owner.trial()!=null){
                var context=owner.trial();var request=context.request();var step=context.step();
                require(request.account().equals(a.id())&&request.subscription().equals(s.id())&&step.resourceId().equals(request.operation())&&step.accountId().equals(a.id())
                    &&step.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_CREATE&&step.status()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED&&remote.getId().equals(step.providerReference()));
                if(context.original().terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL){var setup=context.requiredSetup();require(setup!=null&&setup.operation().equals(request.operation())&&setup.account().equals(a.id())&&setup.subscription().equals(s.id())&&setup.method()!=null);}
                if(request.verified()){
                    require(step.id().equals(request.step())&&remote.getId().equals(request.subscriptionReference()));trial=request.period();
                    if(remote.getTrialStart()!=null||remote.getTrialEnd()!=null)require(trial.equals(period(remote.getTrialStart(),remote.getTrialEnd())));
                }else{
                    if(remote.getTrialStart()!=null||remote.getTrialEnd()!=null){
                        trial=period(remote.getTrialStart(),remote.getTrialEnd());
                        require(trial.end().getEpochSecond()==request.requestedEnd()&&StripeBillingOwnership.inWindow(remote.getTrialStart(),step));
                    }
                }
            }
            var invoices=pages(after->{var page=client.v1().invoices().list(InvoiceListParams.builder().setSubscription(remote.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build());rememberExplicitNullAttempt(page);return page;});
            sourceInvoices=invoices;
            var normalized=new ArrayList<SubscriptionProviderModels.Invoice>();
            for(var invoice:invoices)normalized.add(invoice(invoice,remote,item,trial));
            if(owner.trial()!=null)require(trialEvidence!=null);
            Instant cancel=remote.getCancelAt()==null?null:Instant.ofEpochSecond(remote.getCancelAt());
            if(Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())){if(cancel==null)cancel=period.end();else require(cancel.equals(period.end()));}
            boolean methodReady=false;
            if(remote.getDefaultPaymentMethod()!=null){
                String method=remote.getDefaultPaymentMethod();
                    require(method.matches("pm_[A-Za-z0-9]{1,252}"));
                    var pm=get(()->client.v1().paymentMethods().retrieve(method));
                    require(method.equals(pm.getId())&&"payment_method".equals(pm.getObject())&&Boolean.FALSE.equals(pm.getLivemode())&&b.providerReference().equals(pm.getCustomer())&&"card".equals(pm.getType())&&pm.getCard()!=null);methodReady=true;
            }
            return new Snapshot(remote.getId(),status,mapped.terms(),period,trialEvidence==null?trial:trialEvidence.period(),cancel,normalized,null,methodReady,0,at,null,List.of(),trialEvidence);
        }
        void rememberExplicitNullAttempt(StripeCollection<com.stripe.model.Invoice> page){
            // A nullable SDK getter loses the distinction between absent and explicit null.
            // Retain that one native field from this exact already-read collection response.
            if(page.getLastResponse()==null)return;
            try{
                var root=NATIVE_JSON.readTree(page.getLastResponse().body());var data=root.get("data");if(data==null||!data.isArray()||page.getData()==null||data.size()!=page.getData().size())return;
                for(int i=0;i<data.size();i++){var raw=data.get(i);var id=raw.get("id");if(id!=null&&id.isTextual()&&id.textValue().equals(page.getData().get(i).getId())&&raw.has("next_payment_attempt")&&raw.get("next_payment_attempt").isNull())explicitNoNextAttempt.add(id.textValue());}
            }catch(java.io.IOException|RuntimeException malformed){throw mismatch();}
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
            sourceLines.put(invoice.getId(),lines);
            var matching=owner.prorations().stream().filter(p->invoice.getId().equals(p.result().invoice())).toList();
            if(!matching.isEmpty()){require(matching.size()==1);return proration(invoice,lines,matching.getFirst());}
            // Multi-line proration needs the original quote's exact debit/credit proof.
            require(lines.size()==1);var line=lines.getFirst();ref(line.getId(),"il_");
            require("line_item".equals(line.getObject())&&Boolean.FALSE.equals(line.getLivemode())&&invoice.getId().equals(line.getInvoice())&&remote.getId().equals(line.getSubscription())&&Objects.equals(line.getQuantity(),1L)
                &&line.getParent()!=null&&"subscription_item_details".equals(line.getParent().getType())&&line.getParent().getSubscriptionItemDetails()!=null);
            var detail=line.getParent().getSubscriptionItemDetails();
            var trialContext=owner.trial();boolean historicalTrial=trialContext!=null&&trialContext.request().verified()&&invoice.getId().equals(trialContext.request().invoice());
            require(remote.getId().equals(detail.getSubscription())&&detail.getProration()!=null);
            require(line.getPricing()!=null&&"price_details".equals(line.getPricing().getType())&&line.getPricing().getPriceDetails()!=null);
            var pricing=line.getPricing().getPriceDetails();var mapped=prices.get(pricing.getPrice());require(mapped!=null&&mapped.providerProductReference().equals(pricing.getProduct()));
            currency(invoice.getCurrency(),mapped.terms());currency(line.getCurrency(),mapped.terms());
            require(line.getPeriod()!=null);var period=period(line.getPeriod().getStart(),line.getPeriod().getEnd());require(invoice.getAmountDue()!=null&&invoice.getAmountDue()>=0&&line.getAmount()!=null);
            var kind=switch(invoice.getBillingReason()){case "subscription_create"->BillingRecord.Kind.INITIAL;case "subscription_cycle"->BillingRecord.Kind.RENEWAL;case "subscription_update"->BillingRecord.Kind.PRORATION;default->throw mismatch();};
            boolean originalTrial=trialContext!=null&&!trialContext.request().verified()&&kind==BillingRecord.Kind.INITIAL&&invoice.getAmountDue()==0
                &&mapped.equals(trialContext.original())&&period.end().getEpochSecond()==trialContext.request().requestedEnd()
                &&StripeBillingOwnership.inWindow(period.start().getEpochSecond(),trialContext.step())&&StripeBillingOwnership.inWindow(invoice.getCreated(),trialContext.step())
                &&(trial==null||trial.equals(period));
            var scheduleHistory=owner.schedules().stream().filter(p->{var q=p.quote();var binding=p.schedule();return binding.releaseStep()==null&&binding.updateStep()!=null&&binding.targetEnd()!=null
                &&q.customerReference().equals(invoice.getCustomer())&&q.subscriptionReference().equals(remote.getId())&&q.targetPrice().equals(pricing.getPrice())&&q.product().equals(pricing.getProduct())
                &&line.getPeriod()!=null&&Objects.equals(line.getPeriod().getStart(),q.period().end().getEpochSecond())&&Objects.equals(line.getPeriod().getEnd(),binding.targetEnd());}).toList();
            require(scheduleHistory.size()<=1);
            if(!scheduleHistory.isEmpty()){require("subscription_cycle".equals(invoice.getBillingReason())&&Boolean.FALSE.equals(detail.getProration())&&detail.getSubscriptionItem()!=null&&detail.getSubscriptionItem().matches("si_[A-Za-z0-9]{1,252}")
                &&StripeBillingMutationAdapter.noAdjustments(invoice)&&(line.getTaxes()==null||line.getTaxes().isEmpty())&&(line.getDiscounts()==null||line.getDiscounts().isEmpty()));}
            else if(originalTrial)require(detail.getSubscriptionItem()!=null&&detail.getSubscriptionItem().matches("si_[A-Za-z0-9]{1,252}"));
            else require((historicalTrial?trialContext.request().item():item.getId()).equals(detail.getSubscriptionItem()));
            var allocations=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            sourceAllocations.put(invoice.getId(),allocations);
            for(var allocation:allocations){ref(allocation.getId(),"inpay_");require("invoice_payment".equals(allocation.getObject())&&Boolean.FALSE.equals(allocation.getLivemode())&&invoice.getId().equals(allocation.getInvoice()));currency(allocation.getCurrency(),mapped.terms());}
            SettlementKind settlement=SettlementKind.UNSUPPORTED;List<CashObject> cash=List.of();
            boolean plain=!detail.getProration()&&kind!=BillingRecord.Kind.PRORATION&&"charge_automatically".equals(invoice.getCollectionMethod())
                &&zeros(invoice.getAmountPaidOffStripe(),invoice.getAmountOverpaid(),invoice.getStartingBalance(),invoice.getEndingBalance(),invoice.getPrePaymentCreditNotesAmount(),invoice.getPostPaymentCreditNotesAmount())
                &&empty(invoice.getDiscounts())&&empty(invoice.getTotalDiscountAmounts())&&empty(invoice.getTotalPretaxCreditAmounts())&&empty(line.getDiscountAmounts())&&empty(line.getPretaxCreditAmounts())
                &&Objects.equals(invoice.getTotal(),invoice.getAmountDue())&&Objects.equals(invoice.getSubtotal(),invoice.getAmountDue())&&Objects.equals(line.getAmount(),invoice.getAmountDue());
            switch(invoice.getStatus()){
                case "paid"->{
                    if(plain&&invoice.getAmountDue()==0&&zeros(invoice.getAmountPaid(),invoice.getAmountRemaining())&&allocations.isEmpty()
                        &&kind==BillingRecord.Kind.INITIAL&&trialContext!=null&&mapped.equals(trialContext.original())
                        &&(originalTrial||historicalTrial&&trial!=null&&trial.equals(period))){
                        require(trialEvidence==null);
                        trialEvidence=new TrialEvidence(trialContext.request().operation(),trialContext.step().id(),invoice.getId(),detail.getSubscriptionItem(),period,mapped.terms(),trialContext.requiredSetup()==null?null:trialContext.requiredSetup().method());
                        settlement=SettlementKind.ZERO;
                    }
                    else if(plain&&invoice.getAmountDue()>0&&invoice.getAmountDue()==mapped.terms().regularAmount().minorUnits()&&Objects.equals(invoice.getAmountPaid(),invoice.getAmountDue())&&zeros(invoice.getAmountRemaining())&&allocations.size()==1){
                        var confirmed=capture(invoice,allocations.getFirst());if(confirmed!=null){require(cashIds.add(confirmed.reference()));cash=List.of(confirmed);settlement=SettlementKind.CASH;}
                    }
                }
                case "open","draft","uncollectible"->{if(plain&&invoice.getAmountDue()>0&&invoice.getAmountDue()==mapped.terms().regularAmount().minorUnits()&&zeros(invoice.getAmountPaid())&&Objects.equals(invoice.getAmountRemaining(),invoice.getAmountDue())&&allocations.stream().allMatch(p->("open".equals(p.getStatus())||"canceled".equals(p.getStatus()))&&p.getPayment()!=null&&"payment_intent".equals(p.getPayment().getType())&&p.getPayment().getPaymentIntent()!=null))settlement=SettlementKind.OPEN;}
                case "void"->{if(plain&&allocations.isEmpty())settlement=SettlementKind.VOID;}
                default->throw mismatch();
            }
            if(!scheduleHistory.isEmpty())require(settlement==SettlementKind.CASH||item.getId().equals(detail.getSubscriptionItem())&&settlement==SettlementKind.OPEN);
            return new SubscriptionProviderModels.Invoice(invoice.getId(),kind,mapped.terms(),null,period,invoice.getAmountDue(),mapped.terms().regularAmount().currency(),settlement,cash);
        }
        SubscriptionProviderModels.Invoice proration(com.stripe.model.Invoice invoice,List<InvoiceLineItem> lines,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration proof)throws StripeException {
            var q=proof.quote();var result=proof.result();var s=owner.subscription();
            require(q.account().equals(owner.account().id())&&q.site().equals(s.siteId())&&q.customer().equals(s.customerId())&&q.customerReference().equals(owner.customer().providerReference())&&q.subscription().equals(s.id())&&q.subscriptionReference().equals(s.providerReference())&&q.id().equals(result.quote())&&result.account().equals(q.account())&&result.subscription().equals(s.id())&&result.reference().equals(q.subscriptionReference())&&result.invoice().equals(invoice.getId())&&q.timing()==ChangeTiming.IMMEDIATE);
            StripeBillingMutationAdapter.requireInvoice(invoice,q,result.invoice());
            require(lines.stream().allMatch(l->invoice.getId().equals(l.getInvoice())&&q.subscriptionReference().equals(l.getSubscription())));
            require(lines.stream().allMatch(l->l.getPricing()!=null&&l.getPricing().getPriceDetails()!=null&&Objects.equals(l.getId(),q.sourcePrice().equals(l.getPricing().getPriceDetails().getPrice())?result.creditLine():result.debitLine())));
            var period=new Subscription.Period(q.anchor(),q.paidThrough());
            require(q.lines().equals(StripeBillingMutationAdapter.prorationLines(lines,q.subscriptionReference(),q.item(),q.sourcePrice(),q.targetPrice(),q.product(),period,q.target().regularAmount().currency(),true)));
            var source=prices.get(q.sourcePrice());var target=prices.get(q.targetPrice());require(source!=null&&source.terms().equals(q.source())&&target!=null&&target.terms().equals(q.target()));
            var allocations=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));sourceAllocations.put(invoice.getId(),allocations);
            for(var allocation:allocations){ref(allocation.getId(),"inpay_");require("invoice_payment".equals(allocation.getObject())&&Boolean.FALSE.equals(allocation.getLivemode())&&invoice.getId().equals(allocation.getInvoice()));currency(allocation.getCurrency(),q.target());}
            SettlementKind settlement=SettlementKind.UNSUPPORTED;List<CashObject> cash=List.of();
            if("paid".equals(invoice.getStatus())&&zeros(invoice.getAmountRemaining())){
                if(q.dueNow()==0&&zeros(invoice.getAmountPaid())&&allocations.isEmpty())settlement=SettlementKind.ZERO;
                else if(q.dueNow()>0&&Objects.equals(invoice.getAmountPaid(),q.dueNow())&&allocations.size()==1&&Boolean.TRUE.equals(allocations.getFirst().getIsDefault())){
                    var captured=capture(invoice,allocations.getFirst());if(captured!=null){require(cashIds.add(captured.reference()));cash=List.of(captured);settlement=SettlementKind.CASH;}
                }
            }else if(Set.of("open","draft","uncollectible").contains(invoice.getStatus())&&q.dueNow()>0&&zeros(invoice.getAmountPaid())&&Objects.equals(invoice.getAmountRemaining(),q.dueNow())&&allocations.stream().allMatch(a->Set.of("open","canceled").contains(a.getStatus())&&a.getPayment()!=null&&"payment_intent".equals(a.getPayment().getType())&&a.getPayment().getPaymentIntent()!=null))settlement=SettlementKind.OPEN;
            else if("void".equals(invoice.getStatus())&&zeros(invoice.getAmountPaid())&&allocations.isEmpty())settlement=SettlementKind.VOID;
            return new SubscriptionProviderModels.Invoice(invoice.getId(),BillingRecord.Kind.PRORATION,q.target(),q.source(),period,q.dueNow(),q.target().regularAmount().currency(),settlement,cash);
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
