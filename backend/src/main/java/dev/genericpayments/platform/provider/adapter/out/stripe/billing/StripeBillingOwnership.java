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

/** Shared immutable Billing proof; no provider HTTP or grants. */
public final class StripeBillingOwnership {
    private final StripeCredentialResolver credentials;
    private final dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps;
    private final StripeBillingBindingPort bindings;
    private final DigestPort digest;
    private final LoadPaymentAccountPort accounts;
    private final LoadSitePort sites;
    private final CustomerStore customerStore;
    private final SubscriptionStore subscriptions;
    private final SubscriptionOperationStore operations;
    private final SubscriptionCatalogPort catalog;
    private final ProviderCatalogMappingPort mappings;
    public StripeBillingOwnership(StripeCredentialResolver credentials,dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps,
        StripeBillingBindingPort bindings,DigestPort digest,LoadPaymentAccountPort accounts,LoadSitePort sites,CustomerStore customerStore,
        SubscriptionStore subscriptions,SubscriptionOperationStore operations,SubscriptionCatalogPort catalog,ProviderCatalogMappingPort mappings){
        this.credentials=credentials;this.steps=steps;this.bindings=bindings;this.digest=digest;this.accounts=accounts;this.sites=sites;
        this.customerStore=customerStore;this.subscriptions=subscriptions;this.operations=operations;this.catalog=catalog;this.mappings=mappings;
    }
    public record OriginalCreate(PaymentAccount account,Subscription subscription,SubscriptionOperation operation,CustomerBinding customer,
        ProviderOperation step,StripeBillingSnapshotMapper.PublishedPrice price){}
    public OriginalCreate acknowledgedCreate(Lookup lookup){
        var s=owned(lookup.account(),lookup.subscriptionId());
        if(s.providerReference()==null||!Objects.equals(s.providerReference(),lookup.reference())||lookup.providerOperationKey()==null)throw mismatch();
        var parts=lookup.providerOperationKey().split(":");if(parts.length!=4||!parts[0].equals("subscription")||!parts[3].equals("CREATE"))throw mismatch();
        UUID id;try{id=UUID.fromString(parts[2]);}catch(IllegalArgumentException bad){throw mismatch();}
        var o=original(s,id);if(!o.providerOperationKey().equals(lookup.providerOperationKey())||CreateSubscriptionFingerprint.startsTrial(o.fingerprint()))throw mismatch();
        var b=bindings.findCustomer(lookup.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeBillingOwnership::mismatch);requireBinding(lookup.account(),s,b);
        var step=steps.find(lookup.account().id(),o.providerOperationKey()).orElseThrow(StripeBillingOwnership::mismatch);
        var p=originalPrice(lookup.account(),s,o,b,prices(lookup.account(),s),step);requireStep(lookup.account(),s,o,step,fingerprint(lookup.account(),s,o,b,p));
        if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED||!s.providerReference().equals(step.providerReference()))throw mismatch();
        return new OriginalCreate(lookup.account(),s,o,b,step,p);
    }
    public void requireAccount(PaymentAccount a){requireAccount(a,true);}
    public void requireAccount(PaymentAccount a,boolean active){
        if(a==null||!accounts.findById(a.id()).filter(a::equals).isPresent()||active&&a.status()!=PaymentAccountStatus.ACTIVE
            ||a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX)throw conflict();
        credentials.resolve(a);
    }
    public Subscription owned(PaymentAccount a,UUID id){
        requireAccount(a);if(id==null)throw conflict();
        var s=subscriptions.findOwned(a.id(),id).orElseThrow(StripeBillingOwnership::conflict);
        var site=sites.findById(s.siteId()).orElseThrow(StripeBillingOwnership::conflict);
        var customer=customerStore.findById(s.siteId(),s.customerId()).orElseThrow(StripeBillingOwnership::conflict);
        if(!id.equals(s.id())||!a.id().equals(s.paymentAccountId())||!s.siteId().equals(site.id())||site.status()!=SiteStatus.ACTIVE
            ||!s.customerId().equals(customer.id())||!s.siteId().equals(customer.siteId())||customer.status()!=CustomerStatus.ACTIVE)throw conflict();
        return s;
    }
    public SubscriptionOperation original(Subscription s,UUID id){
        if(id==null)throw conflict();var o=operations.find(s.siteId(),s.id(),id).orElseThrow(StripeBillingOwnership::conflict);
        if(!id.equals(o.id())||o.kind()!=SubscriptionOperation.Kind.CREATE||!s.id().equals(o.subscriptionId())
            ||!s.siteId().equals(o.siteId())||!s.customerId().equals(o.customerId())
            ||!("subscription:"+s.paymentAccountId().value()+":"+o.id()+":CREATE").equals(o.providerOperationKey()))throw conflict();
        return o;
    }
    public StripeBillingSnapshotMapper.PublishedPrice price(PaymentAccount a,Subscription s,SubscriptionPriceTerms t){
        if(!s.productId().equals(t.productId())||!t.equals(catalog.terms(s.siteId(),t.priceId()))||t.taxMode()==TaxMode.EXCLUSIVE)throw conflict();
        var price=mappings.findPrice(a.id(),t.priceId()).orElseThrow(StripeBillingOwnership::conflict).value();
        var product=mappings.findProduct(a.id(),t.productId()).orElseThrow(StripeBillingOwnership::conflict).value();
        if(!price.matches("price_[A-Za-z0-9]{1,249}")||!product.matches("prod_[A-Za-z0-9]{1,250}"))throw conflict();
        return new StripeBillingSnapshotMapper.PublishedPrice(t,price,product);
    }
    public List<StripeBillingSnapshotMapper.PublishedPrice> prices(PaymentAccount a,Subscription s){
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
    public StripeBillingSnapshotMapper.PublishedPrice originalPrice(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,
            List<StripeBillingSnapshotMapper.PublishedPrice> prices,ProviderOperation step){
        var matches=prices.stream().filter(p->fingerprint(a,s,o,b,p).equals(step.fingerprint())).toList();
        if(matches.size()!=1)throw mismatch();return matches.getFirst();
    }
    public static void requireBinding(PaymentAccount a,Subscription s,CustomerBinding b){
        if(b==null||!a.id().equals(b.accountId())||!s.siteId().equals(b.siteId())||!s.customerId().equals(b.customerId())
            ||b.providerReference()==null||!b.providerReference().matches("cus_[A-Za-z0-9]{1,251}"))throw mismatch();
    }
    public String fingerprint(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,StripeBillingSnapshotMapper.PublishedPrice p){
        var t=p.terms();return StripeRequestFingerprint.of(digest,a,"SUBSCRIPTION_CREATE",o.id().toString(),o.providerOperationKey(),o.fingerprint(),
            Long.toString(o.expectedRevision()),Long.toString(o.policyVersion()),s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),b.providerReference(),
            t.priceId().value().toString(),Integer.toString(t.version()),t.productId().value().toString(),p.providerPriceReference(),p.providerProductReference(),
            Long.toString(t.regularAmount().minorUnits()),t.regularAmount().currency(),t.interval().name(),Integer.toString(t.trialDays()),t.trialPolicy().name(),t.taxMode().name(),
            "paid","consent:true","classic","charge_automatically","default_incomplete","card","on_subscription","quantity:1");
    }
    public static void requireStep(PaymentAccount a,Subscription s,SubscriptionOperation o,ProviderOperation step,String fingerprint){
        if(step.kind()!=ProviderOperation.Kind.SUBSCRIPTION_CREATE||!a.id().equals(step.accountId())||!a.externalAccountId().equals(step.externalAccountId())
            ||!a.credentialsRef().equals(step.credentialsRef())||!o.id().equals(step.resourceId())||!o.providerOperationKey().equals(step.operationKey())||!fingerprint.equals(step.fingerprint())
            ||step.providerReference()!=null&&(s.providerReference()!=null&&!s.providerReference().equals(step.providerReference())||o.providerReference()!=null&&!o.providerReference().equals(step.providerReference())))throw conflict();
    }
    public static void requireRemote(com.stripe.model.Subscription r,PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,
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
    public static boolean inWindow(Long timestamp,ProviderOperation step){return timestamp!=null&&step.firstDispatchedAt()!=null&&step.retryBefore()!=null
        &&timestamp>=step.firstDispatchedAt().getEpochSecond()&&timestamp<step.retryBefore().getEpochSecond();}
    public static Map<String,String> metadata(PaymentAccount a,Subscription s,SubscriptionOperation o){return Map.of("account_id",a.id().value().toString(),"site_id",s.siteId().value().toString(),
        "customer_id",s.customerId().value().toString(),"subscription_id",s.id().toString(),"operation_id",o.id().toString());}
    public static Subscription bound(Subscription s,String reference){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),reference,
        s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
    public static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request");}
    public static PaymentApplicationException mismatch(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
    public static PaymentApplicationException unsupported(){return new PaymentApplicationException("PROVIDER_CAPABILITY_UNAVAILABLE",409,"Provider capability unavailable");}
}
