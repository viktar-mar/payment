package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionCreateParams;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.customers.domain.CustomerStatus;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import dev.genericpayments.platform.subscriptions.application.service.CreateSubscriptionFingerprint;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;

/** Owned non-trial Billing CREATE and verified reads. ACK records identity, never settlement. */
@Transactional(propagation=Propagation.NEVER)
public class StripeSubscriptionProviderAdapter implements SubscriptionProviderPort {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps;
    private final StripeBillingBindingPort bindings;
    private final StripeBillingCustomerAdapter customers;
    private final DigestPort digest;
    private final LoadPaymentAccountPort accounts;
    private final LoadSitePort sites;
    private final CustomerStore customerStore;
    private final SubscriptionStore subscriptions;
    private final SubscriptionOperationStore operations;
    private final SubscriptionCatalogPort catalog;
    private final ProviderCatalogMappingPort mappings;
    private final Clock clock;
    private final ProviderOperationGuard guard=new ProviderOperationGuard();
    public StripeSubscriptionProviderAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            PaymentAccountVerificationPort verification,dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps,
            StripeBillingBindingPort bindings,StripeBillingCustomerAdapter customers,DigestPort digest,
            LoadPaymentAccountPort accounts,LoadSitePort sites,CustomerStore customerStore,SubscriptionStore subscriptions,
            SubscriptionOperationStore operations,SubscriptionCatalogPort catalog,ProviderCatalogMappingPort mappings,Clock clock) {
        this.credentials=credentials;this.clients=clients;this.verification=verification;this.steps=steps;
        this.bindings=bindings;this.customers=customers;this.digest=digest;this.accounts=accounts;this.sites=sites;
        this.customerStore=customerStore;this.subscriptions=subscriptions;this.operations=operations;
        this.catalog=catalog;this.mappings=mappings;this.clock=clock;
    }
    @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public Capabilities capabilities(PaymentAccount account){requireAccount(account,false);return new Capabilities(false,false,false,false);}
    public Reply create(Command command){
        if(command==null||command.operation().subscriptionId()==null)throw conflict();
        var s=owned(command.account(),command.operation().subscriptionId());
        var o=original(s,command.operation().id());
        if(!o.equals(command.operation())||!s.billingPrice().equals(command.current())||!command.recurringConsent()
            ||command.startTrial()!=CreateSubscriptionFingerprint.startsTrial(o.fingerprint())
            ||command.target()!=null||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null
            ||!Objects.equals(command.providerSubscriptionReference(),s.providerReference()))throw conflict();
        // First trial and its setup/reserved dates are deliberately owned by Task7.
        if(command.startTrial())throw unsupported();
        var p=price(command.account(),s,command.current());
        var b=bindings.findCustomer(command.account().id(),s.siteId(),s.customerId())
            .orElseGet(()->customers.ensureCustomer(command.account(),s.siteId(),s.customerId()));
        requireBinding(command.account(),s,b);
        String fingerprint=fingerprint(command.account(),s,o,b,p);
        var reserved=steps.reserve(command.account(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,o.id(),o.providerOperationKey(),fingerprint);
        requireStep(command.account(),s,o,reserved,fingerprint);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)return accepted(reserved.providerReference());
        if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw conflict();
        String owner=UUID.randomUUID().toString();
        var claim=steps.claim(reserved.id(),owner,Instant.now(),Duration.ofSeconds(60));
        if(claim.isEmpty()){
            var latest=steps.findById(reserved.id()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
            requireStep(command.account(),s,o,latest,fingerprint);
            return latest.status()==ProviderOperation.Status.ACKNOWLEDGED?accepted(latest.providerReference()):unknown();
        }
        var step=claim.get();requireStep(command.account(),s,o,step,fingerprint);
        owned(command.account(),s.id());verification.verify(command.account());
        String key=guard.requireDispatch(step,command.account(),fingerprint,Instant.now());
        com.stripe.model.Subscription remote;
        try{
            remote=client(command.account()).v1().subscriptions().create(SubscriptionCreateParams.builder()
                .setCustomer(b.providerReference())
                .setBillingMode(SubscriptionCreateParams.BillingMode.builder().setType(SubscriptionCreateParams.BillingMode.Type.CLASSIC).build())
                .setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
                    .addPaymentMethodType(SubscriptionCreateParams.PaymentSettings.PaymentMethodType.CARD)
                    .setSaveDefaultPaymentMethod(SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION).build())
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(p.providerPriceReference()).setQuantity(1L).build())
                .putAllMetadata(metadata(command.account(),s,o)).build(),RequestOptions.builder().setIdempotencyKey(key).build());
            requireRemote(remote,command.account(),s,o,b,p,step);
        }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());return unknown();}
        owned(command.account(),s.id());original(s,o.id());
        return accepted(steps.acknowledge(step.id(),owner,step.version(),remote.getId()).providerReference());
    }
    public Snapshot retrieve(Lookup lookup){
        var s=owned(lookup.account(),lookup.subscriptionId());
        if(!Objects.equals(s.providerReference(),lookup.reference()))throw mismatch();
        var b=bindings.findCustomer(lookup.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        requireBinding(lookup.account(),s,b);var prices=prices(lookup.account(),s);
        SubscriptionOperation o=null;ProviderOperation step=null;StripeBillingSnapshotMapper.PublishedPrice initial=null;
        if(lookup.providerOperationKey()!=null){
            // Only original CREATE keys are interpreted here. Later command proofs remain unavailable.
            String[] parts=lookup.providerOperationKey().split(":");
            if(parts.length==4&&parts[0].equals("subscription")&&parts[3].equals("CREATE")){
                try{o=original(s,UUID.fromString(parts[2]));}catch(IllegalArgumentException malformed){throw mismatch();}
                if(!o.providerOperationKey().equals(lookup.providerOperationKey()))throw mismatch();
                step=steps.find(lookup.account().id(),o.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                initial=originalPrice(lookup.account(),s,o,b,prices,step);
                requireStep(lookup.account(),s,o,step,fingerprint(lookup.account(),s,o,b,initial));
                if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
                if(s.providerReference()!=null&&!s.providerReference().equals(step.providerReference()))throw mismatch();
                s=bound(s,step.providerReference());
            }
        }
        if(s.providerReference()==null)throw mismatch();
        verification.verify(lookup.account());
        var result=StripeBillingSnapshotMapper.retrieve(client(lookup.account()),new StripeBillingSnapshotMapper.Ownership(lookup.account(),s,b,prices),clock.instant());
        owned(lookup.account(),s.id());
        if(o==null)return result;
        var originalTerms=initial.terms();
        // The first read may arrive after renewal. Historical INITIAL cash still proves the original
        // CREATE, even when its period is no longer today's currentPeriod. Renewal cash alone cannot.
        boolean paid=result.invoices().stream().anyMatch(i->i.kind()==BillingRecord.Kind.INITIAL
            &&i.settlement()==SettlementKind.CASH&&i.price().equals(originalTerms));
        return new Snapshot(result.subscriptionReference(),result.status(),result.billingPrice(),result.period(),result.trial(),result.cancellationAt(),result.invoices(),
            o.providerOperationKey(),result.paymentMethodReady(),result.providerRevision(),result.observedAt(),
            new OperationObservation(o.providerOperationKey(),paid?OperationState.APPLIED:OperationState.PENDING),result.recoveryObservations());
    }
    /** Trusted exact-event seam for Task6; never searches events or authorizes attachment from metadata. */
    public Reply recoverCreate(PaymentAccount account,UUID subscription,UUID operation,String eventId){
        var s=owned(account,subscription);var o=original(s,operation);
        if(eventId==null||!eventId.matches("evt_[A-Za-z0-9]{1,251}"))throw mismatch();
        var b=bindings.findCustomer(account.id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        requireBinding(account,s,b);
        var step=steps.find(account.id(),o.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        var p=originalPrice(account,s,o,b,prices(account,s),step);
        requireStep(account,s,o,step,fingerprint(account,s,o,b,p));
        if(step.firstDispatchedAt()==null||step.retryBefore()==null||step.status()==ProviderOperation.Status.FAILED
            ||step.leaseExpiresAt()!=null&&Instant.now().isBefore(step.leaseExpiresAt()))throw mismatch();
        verification.verify(account);String reference;
        try{
            var client=client(account);var event=client.v1().events().retrieve(eventId);
            if(event==null||!eventId.equals(event.getId())||!"customer.subscription.created".equals(event.getType())
                ||!"2026-09-30.endive".equals(event.getApiVersion())||!Boolean.FALSE.equals(event.getLivemode())
                ||event.getAccount()!=null||event.getContext()!=null||!inWindow(event.getCreated(),step)
                ||event.getRequest()==null||!step.operationKey().equals(event.getRequest().getIdempotencyKey()))throw mismatch();
            var data=event.getDataObjectDeserializer().getObject().orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
            if(!(data instanceof com.stripe.model.Subscription created))throw mismatch();
            requireRemote(created,account,s,o,b,p,step);reference=created.getId();
            var customer=client.v1().customers().retrieve(b.providerReference());
            if(customer==null||!b.providerReference().equals(customer.getId())||!"customer".equals(customer.getObject())
                ||!Boolean.FALSE.equals(customer.getLivemode())||Boolean.TRUE.equals(customer.getDeleted()))throw mismatch();
            var remote=client.v1().subscriptions().retrieve(reference);requireRemote(remote,account,s,o,b,p,step);
            if(!reference.equals(remote.getId())||!Objects.equals(created.getCreated(),remote.getCreated()))throw mismatch();
        }catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException|RuntimeException malformed){throw mismatch();}
        owned(account,s.id());original(s,o.id());
        return accepted(steps.bindRecovered(step,reference,step.fingerprint()).providerReference());
    }
    public NextAction nextAction(Lookup lookup){owned(lookup.account(),lookup.subscriptionId());return null;}
    public ProviderQuote preview(QuoteRequest request){throw unsupported();}
    public Reply change(Command command){throw unsupported();}
    public Reply clearChange(Command command){throw unsupported();}
    public Reply cancel(Command command,boolean immediate){throw unsupported();}
    public Reply withdrawCancel(Command command){throw unsupported();}
    public Reply setup(Command command){throw unsupported();}
    public Reply recover(Command command){throw unsupported();}
    public Reply resume(Command command){throw unsupported();}
    public Reply confirmAction(Command command,String actionToken){throw unsupported();}
    public PortalNavigation portal(Command command,String allowedReturnUrl){throw unsupported();}
    private StripeClient client(PaymentAccount account){return clients.create(credentials.resolve(account));}
    private void requireAccount(PaymentAccount a){requireAccount(a,true);}
    private void requireAccount(PaymentAccount a,boolean active){
        if(a==null||!accounts.findById(a.id()).filter(a::equals).isPresent()||active&&a.status()!=PaymentAccountStatus.ACTIVE
            ||a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX)throw conflict();
        credentials.resolve(a);
    }
    private Subscription owned(PaymentAccount a,UUID id){
        requireAccount(a);if(id==null)throw conflict();
        var s=subscriptions.findOwned(a.id(),id).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
        var site=sites.findById(s.siteId()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
        var customer=customerStore.findById(s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
        if(!id.equals(s.id())||!a.id().equals(s.paymentAccountId())||!s.siteId().equals(site.id())||site.status()!=SiteStatus.ACTIVE
            ||!s.customerId().equals(customer.id())||!s.siteId().equals(customer.siteId())||customer.status()!=CustomerStatus.ACTIVE)throw conflict();
        return s;
    }
    private SubscriptionOperation original(Subscription s,UUID id){
        if(id==null)throw conflict();var o=operations.find(s.siteId(),s.id(),id).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
        if(!id.equals(o.id())||o.kind()!=SubscriptionOperation.Kind.CREATE||!s.id().equals(o.subscriptionId())
            ||!s.siteId().equals(o.siteId())||!s.customerId().equals(o.customerId())
            ||!("subscription:"+s.paymentAccountId().value()+":"+o.id()+":CREATE").equals(o.providerOperationKey()))throw conflict();
        return o;
    }
    private StripeBillingSnapshotMapper.PublishedPrice price(PaymentAccount a,Subscription s,SubscriptionPriceTerms t){
        if(!s.productId().equals(t.productId())||!t.equals(catalog.terms(s.siteId(),t.priceId()))||t.taxMode()==TaxMode.EXCLUSIVE)throw conflict();
        var price=mappings.findPrice(a.id(),t.priceId()).orElseThrow(StripeSubscriptionProviderAdapter::conflict).value();
        var product=mappings.findProduct(a.id(),t.productId()).orElseThrow(StripeSubscriptionProviderAdapter::conflict).value();
        if(!price.matches("price_[A-Za-z0-9]{1,249}")||!product.matches("prod_[A-Za-z0-9]{1,250}"))throw conflict();
        return new StripeBillingSnapshotMapper.PublishedPrice(t,price,product);
    }
    private List<StripeBillingSnapshotMapper.PublishedPrice> prices(PaymentAccount a,Subscription s){
        var rows=mappings.findPricesForProduct(a.id(),s.siteId(),s.productId());
        if(rows==null||rows.isEmpty()||rows.size()>100)throw mismatch();var result=new ArrayList<StripeBillingSnapshotMapper.PublishedPrice>();var ids=new HashSet<String>();
        for(var row:rows){
            if(!a.id().equals(row.paymentAccountId())||!s.productId().equals(row.productId())||!ids.add(row.priceRef().value()))throw mismatch();
            var p=price(a,s,catalog.terms(s.siteId(),row.priceId()));
            if(!row.priceRef().value().equals(p.providerPriceReference())||!row.productRef().value().equals(p.providerProductReference()))throw mismatch();result.add(p);
        }
        if(result.stream().noneMatch(p->p.terms().equals(s.billingPrice())))throw mismatch();
        return List.copyOf(result);
    }
    private StripeBillingSnapshotMapper.PublishedPrice originalPrice(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,
            List<StripeBillingSnapshotMapper.PublishedPrice> prices,ProviderOperation step){
        var matches=prices.stream().filter(p->fingerprint(a,s,o,b,p).equals(step.fingerprint())).toList();
        if(matches.size()!=1)throw mismatch();return matches.getFirst();
    }
    private static void requireBinding(PaymentAccount a,Subscription s,CustomerBinding b){
        if(b==null||!a.id().equals(b.accountId())||!s.siteId().equals(b.siteId())||!s.customerId().equals(b.customerId())
            ||b.providerReference()==null||!b.providerReference().matches("cus_[A-Za-z0-9]{1,251}"))throw mismatch();
    }
    private String fingerprint(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,StripeBillingSnapshotMapper.PublishedPrice p){
        var t=p.terms();return StripeRequestFingerprint.of(digest,a,"SUBSCRIPTION_CREATE",o.id().toString(),o.providerOperationKey(),o.fingerprint(),
            Long.toString(o.expectedRevision()),Long.toString(o.policyVersion()),s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),b.providerReference(),
            t.priceId().value().toString(),Integer.toString(t.version()),t.productId().value().toString(),p.providerPriceReference(),p.providerProductReference(),
            Long.toString(t.regularAmount().minorUnits()),t.regularAmount().currency(),t.interval().name(),Integer.toString(t.trialDays()),t.trialPolicy().name(),t.taxMode().name(),
            "paid","consent:true","classic","charge_automatically","default_incomplete","card","on_subscription","quantity:1");
    }
    private static void requireStep(PaymentAccount a,Subscription s,SubscriptionOperation o,ProviderOperation step,String fingerprint){
        if(step.kind()!=ProviderOperation.Kind.SUBSCRIPTION_CREATE||!a.id().equals(step.accountId())||!a.externalAccountId().equals(step.externalAccountId())
            ||!a.credentialsRef().equals(step.credentialsRef())||!o.id().equals(step.resourceId())||!o.providerOperationKey().equals(step.operationKey())||!fingerprint.equals(step.fingerprint())
            ||step.providerReference()!=null&&(s.providerReference()!=null&&!s.providerReference().equals(step.providerReference())||o.providerReference()!=null&&!o.providerReference().equals(step.providerReference())))throw conflict();
    }
    private static void requireRemote(com.stripe.model.Subscription r,PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,
            StripeBillingSnapshotMapper.PublishedPrice p,ProviderOperation step){
        if(r==null||r.getId()==null||!r.getId().matches("sub_[A-Za-z0-9]{1,251}")||!"subscription".equals(r.getObject())||!Boolean.FALSE.equals(r.getLivemode())
            ||!b.providerReference().equals(r.getCustomer())||!p.terms().regularAmount().currency().equalsIgnoreCase(r.getCurrency())
            ||r.getMetadata()==null||!r.getMetadata().entrySet().containsAll(metadata(a,s,o).entrySet())
            ||!inWindow(r.getCreated(),step)||!"charge_automatically".equals(r.getCollectionMethod())||r.getBillingMode()==null||!"classic".equals(r.getBillingMode().getType())
            ||r.getTrialStart()!=null||r.getTrialEnd()!=null||r.getPauseCollection()!=null||r.getPaymentSettings()==null
            ||!List.of("card").equals(r.getPaymentSettings().getPaymentMethodTypes())||!"on_subscription".equals(r.getPaymentSettings().getSaveDefaultPaymentMethod())
            ||r.getItems()==null||!Boolean.FALSE.equals(r.getItems().getHasMore())||r.getItems().getData()==null||r.getItems().getData().size()!=1)throw mismatch();
        var item=r.getItems().getData().getFirst();var price=item.getPrice();var t=p.terms();
        if(item.getId()==null||!item.getId().matches("si_[A-Za-z0-9]{1,252}")||!"subscription_item".equals(item.getObject())||!r.getId().equals(item.getSubscription())||!Objects.equals(1L,item.getQuantity())||Boolean.TRUE.equals(item.getDeleted())
            ||price==null||!"price".equals(price.getObject())||!"recurring".equals(price.getType())||!p.providerPriceReference().equals(price.getId())||!p.providerProductReference().equals(price.getProduct())||!Boolean.FALSE.equals(price.getLivemode())
            ||!Objects.equals(t.regularAmount().minorUnits(),price.getUnitAmount())||!t.regularAmount().currency().equalsIgnoreCase(price.getCurrency())||!"inclusive".equals(price.getTaxBehavior())
            ||price.getRecurring()==null||!t.interval().name().equalsIgnoreCase(price.getRecurring().getInterval())||!Objects.equals(1L,price.getRecurring().getIntervalCount())
            ||!"licensed".equals(price.getRecurring().getUsageType())||price.getMetadata()==null||!t.priceId().value().toString().equals(price.getMetadata().get("local_price_id"))
            ||!Integer.toString(t.version()).equals(price.getMetadata().get("local_price_version")))throw mismatch();
    }
    private static boolean inWindow(Long timestamp,ProviderOperation step){return timestamp!=null&&step.firstDispatchedAt()!=null&&step.retryBefore()!=null
        &&timestamp>=step.firstDispatchedAt().getEpochSecond()&&timestamp<step.retryBefore().getEpochSecond();}
    private static Map<String,String> metadata(PaymentAccount a,Subscription s,SubscriptionOperation o){return Map.of("account_id",a.id().value().toString(),"site_id",s.siteId().value().toString(),
        "customer_id",s.customerId().value().toString(),"subscription_id",s.id().toString(),"operation_id",o.id().toString());}
    private static Subscription bound(Subscription s,String reference){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),reference,
        s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
    private static Reply accepted(String reference){if(reference==null||!reference.matches("sub_[A-Za-z0-9]{1,251}"))throw mismatch();return new Reply(Outcome.ACCEPTED,reference,null);}
    private static Reply unknown(){return new Reply(Outcome.UNKNOWN,null,null);}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request");}
    private static PaymentApplicationException mismatch(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
    private static PaymentApplicationException unsupported(){return new PaymentApplicationException("PROVIDER_CAPABILITY_UNAVAILABLE",409,"Provider capability unavailable");}
}
