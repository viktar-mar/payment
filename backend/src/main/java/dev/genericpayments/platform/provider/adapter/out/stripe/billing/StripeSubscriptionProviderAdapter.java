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
    private final StripeBillingOwnership ownership;
    private java.util.function.Supplier<StripeBillingActionAdapter> billingActions;
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
        this.ownership=new StripeBillingOwnership(credentials,steps,bindings,digest,accounts,sites,customerStore,subscriptions,operations,catalog,mappings);
    }
    public StripeSubscriptionProviderAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            PaymentAccountVerificationPort verification,dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps,
            StripeBillingBindingPort bindings,StripeBillingCustomerAdapter customers,DigestPort digest,LoadPaymentAccountPort accounts,
            LoadSitePort sites,CustomerStore customerStore,SubscriptionStore subscriptions,SubscriptionOperationStore operations,
            SubscriptionCatalogPort catalog,ProviderCatalogMappingPort mappings,Clock clock,java.util.function.Supplier<StripeBillingActionAdapter> billingActions){
        this(credentials,clients,verification,steps,bindings,customers,digest,accounts,sites,customerStore,subscriptions,operations,catalog,mappings,clock);this.billingActions=billingActions;
    }
    @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public Capabilities capabilities(PaymentAccount account){ownership.requireAccount(account,false);return new Capabilities(false,false,false,false);}
    public Reply create(Command command){
        if(command==null||command.operation().subscriptionId()==null)throw conflict();
        var s=ownership.owned(command.account(),command.operation().subscriptionId());
        var o=ownership.original(s,command.operation().id());
        if(!o.equals(command.operation())||!s.billingPrice().equals(command.current())||!command.recurringConsent()
            ||command.startTrial()!=CreateSubscriptionFingerprint.startsTrial(o.fingerprint())
            ||command.target()!=null||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null
            ||!Objects.equals(command.providerSubscriptionReference(),s.providerReference()))throw conflict();
        // First trial and its setup/reserved dates are deliberately owned by Task7.
        if(command.startTrial())throw unsupported();
        var p=ownership.price(command.account(),s,command.current());
        var b=bindings.findCustomer(command.account().id(),s.siteId(),s.customerId())
            .orElseGet(()->customers.ensureCustomer(command.account(),s.siteId(),s.customerId()));
        ownership.requireBinding(command.account(),s,b);
        String fingerprint=ownership.fingerprint(command.account(),s,o,b,p);
        var reserved=steps.reserve(command.account(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,o.id(),o.providerOperationKey(),fingerprint);
        ownership.requireStep(command.account(),s,o,reserved,fingerprint);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)return accepted(reserved.providerReference());
        if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw conflict();
        String owner=UUID.randomUUID().toString();
        var claim=steps.claim(reserved.id(),owner,Instant.now(),Duration.ofSeconds(60));
        if(claim.isEmpty()){
            var latest=steps.findById(reserved.id()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
            ownership.requireStep(command.account(),s,o,latest,fingerprint);
            return latest.status()==ProviderOperation.Status.ACKNOWLEDGED?accepted(latest.providerReference()):unknown();
        }
        var step=claim.get();ownership.requireStep(command.account(),s,o,step,fingerprint);
        ownership.owned(command.account(),s.id());verification.verify(command.account());
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
                .putAllMetadata(ownership.metadata(command.account(),s,o)).build(),RequestOptions.builder().setIdempotencyKey(key).build());
            ownership.requireRemote(remote,command.account(),s,o,b,p,step);
        }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());return unknown();}
        ownership.owned(command.account(),s.id());ownership.original(s,o.id());
        return accepted(steps.acknowledge(step.id(),owner,step.version(),remote.getId()).providerReference());
    }
    public Snapshot retrieve(Lookup lookup){
        var s=ownership.owned(lookup.account(),lookup.subscriptionId());
        if(!Objects.equals(s.providerReference(),lookup.reference()))throw mismatch();
        var b=bindings.findCustomer(lookup.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        ownership.requireBinding(lookup.account(),s,b);var prices=ownership.prices(lookup.account(),s);
        SubscriptionOperation o=null;ProviderOperation step=null;StripeBillingSnapshotMapper.PublishedPrice initial=null;
        if(lookup.providerOperationKey()!=null){
            // Only original CREATE keys are interpreted here. Later command proofs remain unavailable.
            String[] parts=lookup.providerOperationKey().split(":");
            if(parts.length==4&&parts[0].equals("subscription")&&parts[3].equals("CREATE")){
                try{o=ownership.original(s,UUID.fromString(parts[2]));}catch(IllegalArgumentException malformed){throw mismatch();}
                if(!o.providerOperationKey().equals(lookup.providerOperationKey()))throw mismatch();
                step=steps.find(lookup.account().id(),o.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                initial=ownership.originalPrice(lookup.account(),s,o,b,prices,step);
                ownership.requireStep(lookup.account(),s,o,step,ownership.fingerprint(lookup.account(),s,o,b,initial));
                if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
                if(s.providerReference()!=null&&!s.providerReference().equals(step.providerReference()))throw mismatch();
                s=ownership.bound(s,step.providerReference());
            }
        }
        if(s.providerReference()==null)throw mismatch();
        verification.verify(lookup.account());
        var result=StripeBillingSnapshotMapper.retrieve(client(lookup.account()),new StripeBillingSnapshotMapper.Ownership(lookup.account(),s,b,prices),clock.instant());
        ownership.owned(lookup.account(),s.id());
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
        var s=ownership.owned(account,subscription);var o=ownership.original(s,operation);
        if(eventId==null||!eventId.matches("evt_[A-Za-z0-9]{1,251}"))throw mismatch();
        var b=bindings.findCustomer(account.id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        ownership.requireBinding(account,s,b);
        var step=steps.find(account.id(),o.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        var p=ownership.originalPrice(account,s,o,b,ownership.prices(account,s),step);
        ownership.requireStep(account,s,o,step,ownership.fingerprint(account,s,o,b,p));
        if(step.firstDispatchedAt()==null||step.retryBefore()==null||step.status()==ProviderOperation.Status.FAILED
            ||step.leaseExpiresAt()!=null&&Instant.now().isBefore(step.leaseExpiresAt()))throw mismatch();
        verification.verify(account);String reference;
        try{
            var client=client(account);var event=client.v1().events().retrieve(eventId);
            if(event==null||!eventId.equals(event.getId())||!"customer.subscription.created".equals(event.getType())
                ||!"2026-09-30.endive".equals(event.getApiVersion())||!Boolean.FALSE.equals(event.getLivemode())
                ||event.getAccount()!=null||event.getContext()!=null||!ownership.inWindow(event.getCreated(),step)
                ||event.getRequest()==null||!step.operationKey().equals(event.getRequest().getIdempotencyKey()))throw mismatch();
            var data=event.getDataObjectDeserializer().getObject().orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
            if(!(data instanceof com.stripe.model.Subscription created))throw mismatch();
            ownership.requireRemote(created,account,s,o,b,p,step);reference=created.getId();
            var customer=client.v1().customers().retrieve(b.providerReference());
            if(customer==null||!b.providerReference().equals(customer.getId())||!"customer".equals(customer.getObject())
                ||!Boolean.FALSE.equals(customer.getLivemode())||Boolean.TRUE.equals(customer.getDeleted()))throw mismatch();
            var remote=client.v1().subscriptions().retrieve(reference);ownership.requireRemote(remote,account,s,o,b,p,step);
            if(!reference.equals(remote.getId())||!Objects.equals(created.getCreated(),remote.getCreated()))throw mismatch();
        }catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException|RuntimeException malformed){throw mismatch();}
        ownership.owned(account,s.id());ownership.original(s,o.id());
        return accepted(steps.bindRecovered(step,reference,step.fingerprint()).providerReference());
    }
    public NextAction nextAction(Lookup lookup){ownership.owned(lookup.account(),lookup.subscriptionId());return billingActions==null?null:billingActions.get().nextAction(lookup);}
    public ProviderQuote preview(QuoteRequest request){throw unsupported();}
    public Reply change(Command command){throw unsupported();}
    public Reply clearChange(Command command){throw unsupported();}
    public Reply cancel(Command command,boolean immediate){throw unsupported();}
    public Reply withdrawCancel(Command command){throw unsupported();}
    public Reply setup(Command command){throw unsupported();}
    public Reply recover(Command command){throw unsupported();}
    public Reply resume(Command command){throw unsupported();}
    public Reply confirmAction(Command command,String actionToken){if(billingActions==null)throw unsupported();return billingActions.get().confirmAction(command,actionToken);}
    public PortalNavigation portal(Command command,String allowedReturnUrl){throw unsupported();}
    private StripeClient client(PaymentAccount account){return clients.create(credentials.resolve(account));}
    private static Reply accepted(String reference){if(reference==null||!reference.matches("sub_[A-Za-z0-9]{1,251}"))throw mismatch();return new Reply(Outcome.ACCEPTED,reference,null);}
    private static Reply unknown(){return new Reply(Outcome.UNKNOWN,null,null);}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request");}
    private static PaymentApplicationException mismatch(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
    private static PaymentApplicationException unsupported(){return new PaymentApplicationException("PROVIDER_CAPABILITY_UNAVAILABLE",409,"Provider capability unavailable");}
}
