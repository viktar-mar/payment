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
    private SubscriptionBillingProofStore proofs;
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionPlanProofStore planProofs;
    private Clock providerClock=Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionSetupContinuationGuard continuationGuard;
    @org.springframework.beans.factory.annotation.Autowired private dev.genericpayments.platform.subscriptions.application.service.SubscriptionResumeReceiptTransaction resumeReceipts;
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
    public StripeSubscriptionProviderAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps,StripeBillingBindingPort bindings,StripeBillingCustomerAdapter customers,
            DigestPort digest,LoadPaymentAccountPort accounts,LoadSitePort sites,CustomerStore customerStore,SubscriptionStore subscriptions,SubscriptionOperationStore operations,
            SubscriptionCatalogPort catalog,ProviderCatalogMappingPort mappings,Clock clock,java.util.function.Supplier<StripeBillingActionAdapter> actions,
            SubscriptionBillingProofStore proofs,Clock providerClock){
        this(credentials,clients,verification,steps,bindings,customers,digest,accounts,sites,customerStore,subscriptions,operations,catalog,mappings,clock,actions);
        this.proofs=Objects.requireNonNull(proofs);this.providerClock=Objects.requireNonNull(providerClock);
    }
    @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public Capabilities capabilities(PaymentAccount account){ownership.requireAccount(account,false);return new Capabilities(planProofs!=null,false,proofs!=null&&billingActions!=null&&billingActions.get().enabled()&&continuationGuard!=null,false);}
    public CreatePreparation prepareCreate(Command command){
        if(!command.startTrial()||command.current().trialPolicy()!=dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL)return new ReadyForCreate();
        if(proofs==null)throw unsupported();
        var existing=proofs.setup(command.account().id(),command.operation().id());
        if(existing.isPresent()&&existing.get().method()!=null)return new ReadyForCreate();
        var setup=prepareSetup(command);
        return new PendingSetup(new OwnedSetupReceipt(setup.operation(),setup.step(),setup.reference()),null);
    }
    private SubscriptionBillingProofStore.Setup prepareSetup(Command command){
        if(proofs==null||billingActions==null||!billingActions.get().enabled()||!command.recurringConsent())throw unsupported();
        var s=ownership.owned(command.account(),command.operation().subscriptionId());
        var o=operations.find(s.siteId(),s.id(),command.operation().id()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
        if(!o.equals(command.operation())||!s.billingPrice().equals(command.current())||!Set.of(SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Kind.SETUP).contains(o.kind())
            ||!Objects.equals(s.providerReference(),command.providerSubscriptionReference())||command.target()!=null||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null)throw conflict();
        if(o.kind()==SubscriptionOperation.Kind.CREATE&&(!command.startTrial()||!CreateSubscriptionFingerprint.startsTrial(o.fingerprint())||s.billingPrice().trialPolicy()!=dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL))throw conflict();
        if(o.kind()==SubscriptionOperation.Kind.SETUP&&s.providerReference()==null||o.kind()==SubscriptionOperation.Kind.CREATE&&s.providerReference()!=null)throw conflict();
        if(!s.nonTerminal()||o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw conflict();
        var b=bindings.findCustomer(command.account().id(),s.siteId(),s.customerId()).orElseGet(()->{if(s.providerReference()!=null)throw mismatch();return customers.ensureCustomer(command.account(),s.siteId(),s.customerId());});
        ownership.requireBinding(command.account(),s,b);
        String key=o.providerOperationKey()+":SETUP",fp=setupFingerprint(command.account(),s,o,b);
        var step=steps.reserve(command.account(),ProviderOperation.Kind.SETUP_INTENT_CREATE,o.id(),key,fp);requireSetupStep(command.account(),o,step,fp);
        if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED){
            String owner=UUID.randomUUID().toString();var claim=steps.claim(step.id(),owner,providerClock.instant(),Duration.ofSeconds(60));
            if(claim.isEmpty())throw conflict();step=claim.get();
            ownership.owned(command.account(),s.id());verification.verify(command.account());
            proofs.authorizeSetup(s,o);
            var dispatch=guard.requireDispatch(step,command.account(),fp,providerClock.instant());
            try{
                var remote=client(command.account()).v1().setupIntents().create(com.stripe.param.SetupIntentCreateParams.builder().setCustomer(b.providerReference())
                    .setUsage(com.stripe.param.SetupIntentCreateParams.Usage.OFF_SESSION).addAllowedPaymentMethodType(com.stripe.param.SetupIntentCreateParams.AllowedPaymentMethodType.CARD)
                    .putAllMetadata(StripeBillingOwnership.metadata(command.account(),s,o)).build(),RequestOptions.builder().setIdempotencyKey(dispatch).build());
                requireSetupRemote(remote,s,o,b,step);
                ownership.owned(command.account(),s.id());
                step=steps.acknowledge(step.id(),owner,step.version(),remote.getId());
            }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());throw mismatch();}
        }
        return proofs.recordSetup(s,o,step.id(),step.providerReference());
    }
    private String setupFingerprint(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b){return ownership.setupFingerprint(a,s,o,b);}
    private static void requireSetupStep(PaymentAccount a,SubscriptionOperation o,ProviderOperation step,String fp){if(step.kind()!=ProviderOperation.Kind.SETUP_INTENT_CREATE||!step.accountId().equals(a.id())||!step.resourceId().equals(o.id())||!step.operationKey().equals(o.providerOperationKey()+":SETUP")||!step.fingerprint().equals(fp)||!step.externalAccountId().equals(a.externalAccountId())||!step.credentialsRef().equals(a.credentialsRef()))throw mismatch();}
    static void requireSetupRemote(com.stripe.model.SetupIntent r,Subscription s,SubscriptionOperation o,CustomerBinding b,ProviderOperation step){
        if(r==null||r.getId()==null||!r.getId().matches("seti_[A-Za-z0-9]{1,250}")||!"setup_intent".equals(r.getObject())||!Boolean.FALSE.equals(r.getLivemode())||!b.providerReference().equals(r.getCustomer())||!"off_session".equals(r.getUsage())||!List.of("card").equals(r.getPaymentMethodTypes())||!Set.of("requires_payment_method","requires_confirmation","requires_action","processing","succeeded").contains(r.getStatus())||!StripeBillingOwnership.inWindow(r.getCreated(),step)||r.getMetadata()==null||!o.id().toString().equals(r.getMetadata().get("operation_id"))||!s.id().toString().equals(r.getMetadata().get("subscription_id")))throw mismatch();
        if(step.providerReference()!=null&&!step.providerReference().equals(r.getId()))throw mismatch();
    }
    public Reply create(Command command){return create(command,null);}
    private Reply create(Command command,SetupContinuationFence fence){
        if(command==null||command.operation().subscriptionId()==null)throw conflict();
        var s=ownership.owned(command.account(),command.operation().subscriptionId());
        var o=ownership.original(s,command.operation().id());
        if(!o.equals(command.operation())||!s.billingPrice().equals(command.current())||!command.recurringConsent()
            ||command.startTrial()!=CreateSubscriptionFingerprint.startsTrial(o.fingerprint())
            ||command.target()!=null||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null
            ||!Objects.equals(command.providerSubscriptionReference(),s.providerReference()))throw conflict();
        var p=ownership.price(command.account(),s,command.current());
        var b=bindings.findCustomer(command.account().id(),s.siteId(),s.customerId())
            .orElseGet(()->customers.ensureCustomer(command.account(),s.siteId(),s.customerId()));
        ownership.requireBinding(command.account(),s,b);
        SubscriptionBillingProofStore.Trial trial=null;SubscriptionBillingProofStore.Setup setup=null;
        if(command.startTrial()){
            if(proofs==null)throw unsupported();
            if(p.terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL){
                setup=proofs.setup(command.account().id(),o.id()).filter(v->v.method()!=null).orElseThrow(StripeSubscriptionProviderAdapter::unsupported);
            }
            trial=proofs.trial(command.account().id(),s.id()).orElse(null);
            if(trial==null){
                verification.verify(command.account());
                try{var customer=client(command.account()).v1().customers().retrieve(b.providerReference());
                    if(customer==null||!b.providerReference().equals(customer.getId())||!"customer".equals(customer.getObject())||!Boolean.FALSE.equals(customer.getLivemode())||Boolean.TRUE.equals(customer.getDeleted())||customer.getTestClock()!=null)throw mismatch();
                }catch(StripeException malformed){throw mismatch();}
                trial=proofs.reserveTrial(s,o,providerClock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(Duration.ofDays(p.terms().trialDays())).getEpochSecond());
            }
            if(!trial.operation().equals(o.id())||!trial.subscription().equals(s.id()))throw mismatch();
        }
        String fingerprint=trial==null?ownership.fingerprint(command.account(),s,o,b,p):ownership.trialFingerprint(command.account(),s,o,b,p,trial,setup);
        var reserved=steps.reserve(command.account(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,o.id(),o.providerOperationKey(),fingerprint);
        ownership.requireStep(command.account(),s,o,reserved,fingerprint);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)return accepted(reserved.providerReference());
        if(trial!=null&&trial.requestedEnd()<=providerClock.instant().getEpochSecond())return unknown();
        if(trial!=null)proofs.reserveTrial(s,o,trial.requestedEnd());
        if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw conflict();
        String owner=UUID.randomUUID().toString();
        var claim=steps.claim(reserved.id(),owner,providerClock.instant(),Duration.ofSeconds(60));
        if(claim.isEmpty()){
            var latest=steps.findById(reserved.id()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
            ownership.requireStep(command.account(),s,o,latest,fingerprint);
            return latest.status()==ProviderOperation.Status.ACKNOWLEDGED?accepted(latest.providerReference()):unknown();
        }
        var step=claim.get();ownership.requireStep(command.account(),s,o,step,fingerprint);
        ownership.owned(command.account(),s.id());verification.verify(command.account());
        if(fence!=null)continuationGuard.requireCurrent(command,fence);
        String key=guard.requireDispatch(step,command.account(),fingerprint,providerClock.instant());
        com.stripe.model.Subscription remote;
        try{
            var params=SubscriptionCreateParams.builder()
                .setCustomer(b.providerReference())
                .setBillingMode(SubscriptionCreateParams.BillingMode.builder().setType(SubscriptionCreateParams.BillingMode.Type.CLASSIC).build())
                .setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
                    .addPaymentMethodType(SubscriptionCreateParams.PaymentSettings.PaymentMethodType.CARD)
                    .setSaveDefaultPaymentMethod(SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION).build())
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(p.providerPriceReference()).setQuantity(1L).build())
                .putAllMetadata(ownership.metadata(command.account(),s,o));
            if(trial!=null){params.setTrialEnd(trial.requestedEnd()).setTrialSettings(SubscriptionCreateParams.TrialSettings.builder().setEndBehavior(SubscriptionCreateParams.TrialSettings.EndBehavior.builder().setMissingPaymentMethod(SubscriptionCreateParams.TrialSettings.EndBehavior.MissingPaymentMethod.PAUSE).build()).build());if(setup!=null)params.setDefaultPaymentMethod(setup.method());}
            remote=client(command.account()).v1().subscriptions().create(params.build(),RequestOptions.builder().setIdempotencyKey(key).build());
            if(trial==null)ownership.requireRemote(remote,command.account(),s,o,b,p,step);
            else requireTrialRemote(remote,command.account(),s,o,b,p,step,trial,setup);
        }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());return unknown();}
        ownership.owned(command.account(),s.id());ownership.original(s,o.id());
        return accepted(steps.acknowledge(step.id(),owner,step.version(),remote.getId()).providerReference());
    }
    public SetupContinuationResult continueOriginalSetup(Command command,SetupContinuationFence fence){
        if(proofs==null||continuationGuard==null||fence==null)throw unsupported();
        var s=ownership.owned(command.account(),command.operation().subscriptionId());var o=operations.find(s.siteId(),s.id(),command.operation().id()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        if(!o.equals(command.operation())||!Set.of(SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Kind.SETUP).contains(o.kind())||!command.recurringConsent()||!Objects.equals(s.providerReference(),command.providerSubscriptionReference()))throw mismatch();
        if(o.kind()==SubscriptionOperation.Kind.CREATE&&(!command.startTrial()||!CreateSubscriptionFingerprint.startsTrial(o.fingerprint())
            ||s.billingPrice().trialPolicy()!=dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL))throw mismatch();
        var b=bindings.findCustomer(command.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);ownership.requireBinding(command.account(),s,b);
        // An acknowledged original CREATE owns its receipt even after the saved card is removed.
        if(o.kind()==SubscriptionOperation.Kind.CREATE&&steps.find(command.account().id(),o.providerOperationKey()).filter(v->v.status()==ProviderOperation.Status.ACKNOWLEDGED).isPresent())
            return new SetupContinuationResult(create(command,fence));
        var setup=proofs.setup(command.account().id(),o.id()).orElse(null);
        var step=setup==null?steps.find(command.account().id(),o.providerOperationKey()+":SETUP").orElseThrow(StripeSubscriptionProviderAdapter::mismatch)
            :steps.findById(setup.step()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        requireSetupStep(command.account(),o,step,setupFingerprint(command.account(),s,o,b));
        if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED||step.providerReference()==null||!step.providerReference().matches("seti_[A-Za-z0-9]{1,250}")
            ||setup!=null&&(!setup.reference().equals(step.providerReference())||!setup.subscription().equals(s.id())||!setup.operation().equals(o.id())||!setup.account().equals(command.account().id())))throw mismatch();
        verification.verify(command.account());String method;
        try{
            var remote=client(command.account()).v1().setupIntents().retrieve(step.providerReference());requireSetupRemote(remote,s,o,b,step);
            if(setup==null)setup=proofs.recordSetup(s,o,step.id(),step.providerReference());
            if(!"succeeded".equals(remote.getStatus()))return new SetupContinuationResult(null);
            method=remote.getPaymentMethod();if(method==null||!method.matches("pm_[A-Za-z0-9]{1,252}"))throw mismatch();
            var pm=client(command.account()).v1().paymentMethods().retrieve(method);
            if(pm==null||!method.equals(pm.getId())||!"payment_method".equals(pm.getObject())||!Boolean.FALSE.equals(pm.getLivemode())||!b.providerReference().equals(pm.getCustomer())||!"card".equals(pm.getType())||pm.getCard()==null)throw mismatch();
        }catch(StripeException malformed){throw mismatch();}
        continuationGuard.requireCurrent(command,fence);setup=proofs.bindMethod(setup,method);
        if(o.kind()==SubscriptionOperation.Kind.CREATE)return new SetupContinuationResult(create(command,fence));
        String fp=methodFingerprint(command.account(),s,o,b,setup),key=o.providerOperationKey()+":METHOD";
        var update=steps.reserve(command.account(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,o.id(),key,fp);
        if(update.status()==ProviderOperation.Status.ACKNOWLEDGED){if(!s.providerReference().equals(update.providerReference()))throw mismatch();return new SetupContinuationResult(accepted(s.providerReference()));}
        String owner=UUID.randomUUID().toString();var claim=steps.claim(update.id(),owner,providerClock.instant(),Duration.ofSeconds(60));if(claim.isEmpty())return new SetupContinuationResult(unknown());update=claim.get();
        verification.verify(command.account());continuationGuard.requireCurrent(command,fence);String dispatch=guard.requireDispatch(update,command.account(),fp,providerClock.instant());
        try{
            var remote=client(command.account()).v1().subscriptions().update(s.providerReference(),com.stripe.param.SubscriptionUpdateParams.builder().setDefaultPaymentMethod(setup.method()).build(),RequestOptions.builder().setIdempotencyKey(dispatch).build());
            if(remote==null||!s.providerReference().equals(remote.getId())||!"subscription".equals(remote.getObject())||!Boolean.FALSE.equals(remote.getLivemode())||!b.providerReference().equals(remote.getCustomer())||!setup.method().equals(remote.getDefaultPaymentMethod()))throw mismatch();
        }catch(StripeException|RuntimeException uncertain){steps.unknown(update.id(),owner,update.version());return new SetupContinuationResult(unknown());}
        return new SetupContinuationResult(accepted(steps.acknowledge(update.id(),owner,update.version(),s.providerReference()).providerReference()));
    }
    private static void requireTrialRemote(com.stripe.model.Subscription r,PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,
            StripeBillingSnapshotMapper.PublishedPrice p,ProviderOperation step,SubscriptionBillingProofStore.Trial trial,SubscriptionBillingProofStore.Setup setup){
        if(r==null||r.getTrialStart()==null||r.getTrialStart()<=0||!ownershipWindow(r.getTrialStart(),step)||!Objects.equals(r.getTrialEnd(),trial.requestedEnd())
            ||r.getTrialStart()>=trial.requestedEnd()||r.getTrialSettings()==null||r.getTrialSettings().getEndBehavior()==null
            ||!"pause".equals(r.getTrialSettings().getEndBehavior().getMissingPaymentMethod())||setup!=null&&!setup.method().equals(r.getDefaultPaymentMethod()))throw mismatch();
        StripeBillingOwnership.requireRemoteShape(r,a,s,o,b,p,step);
    }
    private static boolean ownershipWindow(Long value,ProviderOperation step){return StripeBillingOwnership.inWindow(value,step);}
    public Snapshot retrieve(Lookup lookup){
        var s=ownership.owned(lookup.account(),lookup.subscriptionId());
        if(!Objects.equals(s.providerReference(),lookup.reference()))throw mismatch();
        var b=bindings.findCustomer(lookup.account().id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        ownership.requireBinding(lookup.account(),s,b);var prices=ownership.prices(lookup.account(),s);
        SubscriptionOperation o=null;ProviderOperation step=null;StripeBillingSnapshotMapper.PublishedPrice initial=null;
        StripeBillingSnapshotMapper.TrialContext trialContext=null;
        var trialRequest=proofs==null?null:proofs.trial(lookup.account().id(),s.id()).orElse(null);
        if(trialRequest!=null){
            var original=ownership.original(s,trialRequest.operation());
            if(!CreateSubscriptionFingerprint.startsTrial(original.fingerprint()))throw mismatch();
            var originalStep=steps.find(lookup.account().id(),original.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
            var setup=proofs.setup(lookup.account().id(),original.id()).orElse(null);
            var candidates=new ArrayList<StripeBillingSnapshotMapper.PublishedPrice>();
            for(var price:prices){var method=price.terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL?setup:null;
                if(method!=null&&method.method()==null)continue;
                if(ownership.trialFingerprint(lookup.account(),s,original,b,price,trialRequest,method).equals(originalStep.fingerprint()))candidates.add(price);
            }
            if(candidates.size()!=1)throw mismatch();var price=candidates.getFirst();
            var method=price.terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL?setup:null;
            ownership.requireStep(lookup.account(),s,original,originalStep,ownership.trialFingerprint(lookup.account(),s,original,b,price,trialRequest,method));
            if(originalStep.status()!=ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
            trialContext=new StripeBillingSnapshotMapper.TrialContext(trialRequest,originalStep,price,method);
        }
        if(lookup.providerOperationKey()!=null){
            // Only original CREATE keys are interpreted here. Later command proofs remain unavailable.
            String[] parts=lookup.providerOperationKey().split(":");
            if(parts.length==4&&parts[0].equals("subscription")&&parts[3].equals("CREATE")){
                try{o=ownership.original(s,UUID.fromString(parts[2]));}catch(IllegalArgumentException malformed){throw mismatch();}
                if(!o.providerOperationKey().equals(lookup.providerOperationKey()))throw mismatch();
                step=steps.find(lookup.account().id(),o.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                if(CreateSubscriptionFingerprint.startsTrial(o.fingerprint())){
                    if(trialContext==null||!trialContext.request().operation().equals(o.id()))throw mismatch();initial=trialContext.original();
                }else{
                    initial=ownership.originalPrice(lookup.account(),s,o,b,prices,step);
                    ownership.requireStep(lookup.account(),s,o,step,ownership.fingerprint(lookup.account(),s,o,b,initial));
                }
                if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED)throw mismatch();
                if(s.providerReference()!=null&&!s.providerReference().equals(step.providerReference()))throw mismatch();
                s=ownership.bound(s,step.providerReference());
            }
        }
        if(s.providerReference()==null)throw mismatch();
        verification.verify(lookup.account());
        var result=StripeBillingSnapshotMapper.retrieve(client(lookup.account()),new StripeBillingSnapshotMapper.Ownership(lookup.account(),s,b,prices,trialContext,proofs,planProofs==null?List.of():ownership.planHistory(lookup.account(),s,planProofs),planProofs==null?List.of():ownership.scheduleHistory(lookup.account(),s,planProofs)),clock.instant());
        ownership.owned(lookup.account(),s.id());
        if(o==null)return operationResult(lookup,s,b,result);
        var originalTerms=initial.terms();
        // The first read may arrive after renewal. Historical INITIAL cash still proves the original
        // CREATE, even when its period is no longer today's currentPeriod. Renewal cash alone cannot.
        boolean paid=result.trialEvidence()!=null||result.invoices().stream().anyMatch(i->i.kind()==BillingRecord.Kind.INITIAL
            &&i.settlement()==SettlementKind.CASH&&i.price().equals(originalTerms));
        return new Snapshot(result.subscriptionReference(),result.status(),result.billingPrice(),result.period(),result.trial(),result.cancellationAt(),result.invoices(),
            o.providerOperationKey(),result.paymentMethodReady(),result.providerRevision(),result.observedAt(),
            new OperationObservation(o.providerOperationKey(),paid?OperationState.APPLIED:OperationState.PENDING),result.recoveryObservations(),result.trialEvidence());
    }
    private Snapshot operationResult(Lookup lookup,Subscription s,CustomerBinding b,Snapshot result){
        if(lookup.providerOperationKey()==null||proofs==null&&planProofs==null)return result;
        var matching=operations.unresolved(s.siteId(),s.id()).stream().filter(o->lookup.providerOperationKey().equals(o.providerOperationKey())).toList();
        if(matching.size()!=1)return result;var o=matching.getFirst();boolean applied=false,declined=false;
        if(o.kind()==SubscriptionOperation.Kind.SETUP&&proofs!=null){
            var setup=proofs.setup(lookup.account().id(),o.id()).orElse(null);var update=steps.find(lookup.account().id(),o.providerOperationKey()+":METHOD").orElse(null);
            if(setup!=null&&setup.method()!=null&&update!=null&&update.status()==ProviderOperation.Status.ACKNOWLEDGED&&update.providerReference().equals(s.providerReference())&&update.fingerprint().equals(methodFingerprint(lookup.account(),s,o,b,setup))&&result.paymentMethodReady()){
                try{var remote=client(lookup.account()).v1().subscriptions().retrieve(s.providerReference());applied=remote!=null&&s.providerReference().equals(remote.getId())&&b.providerReference().equals(remote.getCustomer())&&setup.method().equals(remote.getDefaultPaymentMethod());}catch(StripeException invalid){throw mismatch();}
            }
        }else if(o.kind()==SubscriptionOperation.Kind.RESUME&&proofs!=null){
            var resume=proofs.resume(lookup.account().id(),o.id()).orElse(null);var update=steps.find(lookup.account().id(),o.providerOperationKey()).orElse(null);
            if(resume!=null&&update!=null&&update.id().equals(resume.step())&&update.status()==ProviderOperation.Status.ACKNOWLEDGED&&update.fingerprint().equals(resumeFingerprint(lookup.account(),s,o,b))&&s.providerReference().equals(resume.reference()))
                applied=result.status()==Subscription.Status.ACTIVE&&result.invoices().stream().anyMatch(i->i.reference().equals(resume.invoice())&&i.period().equals(resume.period())&&i.price().equals(s.billingPrice())&&i.settlement()==SettlementKind.CASH);
        }else if(o.kind()==SubscriptionOperation.Kind.CHANGE&&planProofs!=null){
            var original=planProofs.result(lookup.account().id(),o.id()).orElse(null);
            if(original!=null){
                var q=planProofs.quote(lookup.account().id(),original.quote()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                var invoice=result.invoices().stream().filter(i->i.reference().equals(original.invoice())).findFirst().orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                boolean originalOrLaterPeriod=result.period().equals(q.period())||!result.period().start().isBefore(q.period().end());
                applied=result.status()==Subscription.Status.ACTIVE&&result.billingPrice().equals(q.target())&&originalOrLaterPeriod
                    &&(invoice.settlement()==SettlementKind.CASH||invoice.settlement()==SettlementKind.ZERO);
                if(!applied&&invoice.settlement()==SettlementKind.VOID&&original.pendingExpiry()!=null&&providerClock.instant().getEpochSecond()>=original.pendingExpiry()&&result.billingPrice().equals(q.source())&&result.period().equals(q.period())){
                    try{var remote=client(lookup.account()).v1().subscriptions().retrieve(s.providerReference());StripeBillingOwnership.requireCurrentRemote(remote,s,b,ownership.price(lookup.account(),s,q.source()));declined=remote.getPendingUpdate()==null&&"active".equals(remote.getStatus())&&StripeBillingMutationAdapter.itemPeriod(remote).equals(result.period());}catch(StripeException invalid){throw mismatch();}
                }
            }else{
                var schedule=planProofs.schedule(lookup.account().id(),o.id()).orElse(null);
                if(schedule!=null&&schedule.updateStep()!=null&&schedule.releaseStep()==null){
                    var q=planProofs.quote(lookup.account().id(),schedule.quote()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                    StripeBillingMutationAdapter.requireScheduleSteps(new Command(lookup.account(),o,s.billingPrice(),q.target(),s.providerReference(),null,q.reference(),false,false),s,o,q,schedule,steps);
                    applied=result.invoices().stream().anyMatch(i->i.kind()==BillingRecord.Kind.RENEWAL&&i.settlement()==SettlementKind.CASH&&i.price().equals(q.target())&&i.period().start().equals(q.period().end())&&i.period().end().getEpochSecond()==schedule.targetEnd());
                    if(!applied&&providerClock.instant().isBefore(q.period().end()))try{var remote=client(lookup.account()).v1().subscriptionSchedules().retrieve(schedule.reference());StripeBillingMutationAdapter.requireSchedule(remote,q,schedule,true);applied=true;}catch(StripeException invalid){throw mismatch();}
                }
            }
        }else if(o.kind()==SubscriptionOperation.Kind.CLEAR_CHANGE&&planProofs!=null&&s.pendingChange()!=null){
            var schedule=planProofs.schedule(lookup.account().id(),s.pendingChange().operationId()).orElse(null);
            if(schedule!=null&&o.id().equals(schedule.clearOperation())&&schedule.releaseStep()!=null){
                var q=planProofs.quote(lookup.account().id(),schedule.quote()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                var original=operations.find(s.siteId(),s.id(),schedule.operation()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                StripeBillingMutationAdapter.requireScheduleSteps(new Command(lookup.account(),o,s.billingPrice(),null,s.providerReference(),null,null,false,false),s,original,q,schedule,steps);
                var release=steps.findById(schedule.releaseStep()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
                if(release.kind()!=ProviderOperation.Kind.SCHEDULE_RELEASE||release.status()!=ProviderOperation.Status.ACKNOWLEDGED||!release.accountId().equals(lookup.account().id())||!release.resourceId().equals(o.id())||!release.operationKey().equals(o.providerOperationKey()+":SCHEDULE_RELEASE")||!release.providerReference().equals(schedule.reference())||!release.externalAccountId().equals(lookup.account().externalAccountId())||!release.credentialsRef().equals(lookup.account().credentialsRef())||!release.fingerprint().equals(StripeBillingMutationAdapter.scheduleFingerprint(lookup.account(),o,q,schedule,ProviderOperation.Kind.SCHEDULE_RELEASE)))throw mismatch();
                try{
                    StripeBillingMutationAdapter.requireReleased(client(lookup.account()).v1().subscriptionSchedules().retrieve(schedule.reference()),q,schedule,release,providerClock.instant());applied=true;
                }catch(StripeException invalid){throw mismatch();}
            }
        }
        return new Snapshot(result.subscriptionReference(),result.status(),result.billingPrice(),result.period(),result.trial(),result.cancellationAt(),result.invoices(),o.providerOperationKey(),result.paymentMethodReady(),result.providerRevision(),result.observedAt(),new OperationObservation(o.providerOperationKey(),applied?OperationState.APPLIED:declined?OperationState.DECLINED:OperationState.PENDING),result.recoveryObservations(),result.trialEvidence());
    }
    private String methodFingerprint(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b,SubscriptionBillingProofStore.Setup setup){return StripeRequestFingerprint.of(digest,a,"SUBSCRIPTION_METHOD_V1",o.id().toString(),o.providerOperationKey()+":METHOD",o.fingerprint(),s.providerReference(),b.providerReference(),setup.step().toString(),setup.reference(),setup.method(),"consent:true");}
    private String resumeFingerprint(PaymentAccount a,Subscription s,SubscriptionOperation o,CustomerBinding b){return ownership.resumeFingerprint(a,s,o,b,ownership.price(a,s,s.billingPrice()));}
    /** Trusted exact-event seam for Task6; never searches events or authorizes attachment from metadata. */
    public Reply recoverCreate(PaymentAccount account,UUID subscription,UUID operation,String eventId){
        var s=ownership.owned(account,subscription);var o=ownership.original(s,operation);
        if(eventId==null||!eventId.matches("evt_[A-Za-z0-9]{1,251}"))throw mismatch();
        var b=bindings.findCustomer(account.id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        ownership.requireBinding(account,s,b);
        var step=steps.find(account.id(),o.providerOperationKey()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
        SubscriptionBillingProofStore.Trial trial=null;SubscriptionBillingProofStore.Setup method=null;
        StripeBillingSnapshotMapper.PublishedPrice p;
        if(CreateSubscriptionFingerprint.startsTrial(o.fingerprint())){
            if(proofs==null)throw mismatch();trial=proofs.trial(account.id(),s.id()).filter(v->v.operation().equals(o.id())).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
            var savedMethod=proofs.setup(account.id(),o.id()).filter(v->v.method()!=null).orElse(null);var request=trial;
            var candidates=ownership.prices(account,s).stream().filter(v->ownership.trialFingerprint(account,s,o,b,v,request,v.terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL?savedMethod:null).equals(step.fingerprint())).toList();
            if(candidates.size()!=1)throw mismatch();p=candidates.getFirst();method=p.terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL?savedMethod:null;
            if(p.terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL&&method==null)throw mismatch();
            ownership.requireStep(account,s,o,step,ownership.trialFingerprint(account,s,o,b,p,trial,method));
        }else{p=ownership.originalPrice(account,s,o,b,ownership.prices(account,s),step);ownership.requireStep(account,s,o,step,ownership.fingerprint(account,s,o,b,p));}
        if(step.firstDispatchedAt()==null||step.retryBefore()==null||step.status()==ProviderOperation.Status.FAILED
            ||step.leaseExpiresAt()!=null&&providerClock.instant().isBefore(step.leaseExpiresAt()))throw mismatch();
        verification.verify(account);String reference;
        try{
            var client=client(account);var event=client.v1().events().retrieve(eventId);
            if(event==null||!eventId.equals(event.getId())||!"customer.subscription.created".equals(event.getType())
                ||!"2026-09-30.endive".equals(event.getApiVersion())||!Boolean.FALSE.equals(event.getLivemode())
                ||event.getAccount()!=null||event.getContext()!=null||!ownership.inWindow(event.getCreated(),step)
                ||event.getRequest()==null||!step.operationKey().equals(event.getRequest().getIdempotencyKey()))throw mismatch();
            var data=event.getDataObjectDeserializer().getObject().orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
            if(!(data instanceof com.stripe.model.Subscription created))throw mismatch();
            if(trial==null)ownership.requireRemote(created,account,s,o,b,p,step);else requireTrialRemote(created,account,s,o,b,p,step,trial,method);reference=created.getId();
            var customer=client.v1().customers().retrieve(b.providerReference());
            if(customer==null||!b.providerReference().equals(customer.getId())||!"customer".equals(customer.getObject())
                ||!Boolean.FALSE.equals(customer.getLivemode())||Boolean.TRUE.equals(customer.getDeleted())||trial!=null&&customer.getTestClock()!=null)throw mismatch();
            var remote=client.v1().subscriptions().retrieve(reference);if(trial==null)ownership.requireRemote(remote,account,s,o,b,p,step);else requireTrialRemote(remote,account,s,o,b,p,step,trial,method);
            if(!reference.equals(remote.getId())||!Objects.equals(created.getCreated(),remote.getCreated()))throw mismatch();
        }catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException|RuntimeException malformed){throw mismatch();}
        ownership.owned(account,s.id());ownership.original(s,o.id());
        return accepted(steps.bindRecovered(step,reference,step.fingerprint()).providerReference());
    }
    public NextAction nextAction(Lookup lookup){ownership.owned(lookup.account(),lookup.subscriptionId());return billingActions==null?null:billingActions.get().nextAction(lookup);}
    public ProviderQuote preview(QuoteRequest request){if(planProofs==null)throw unsupported();return new StripeBillingMutationAdapter(ownership,bindings,planProofs,credentials,clients,verification,providerClock).preview(request);}
    public Reply change(Command command){if(planProofs==null)throw unsupported();return new StripeBillingMutationAdapter(ownership,bindings,planProofs,credentials,clients,verification,providerClock).change(command,steps,providerClock);}
    public Reply clearChange(Command command){if(planProofs==null)throw unsupported();return new StripeBillingMutationAdapter(ownership,bindings,planProofs,credentials,clients,verification,providerClock).clear(command,steps,providerClock);}
    public Reply continueOriginalPlan(Command command){
        var o=command.operation();
        String key=o.providerOperationKey();
        if(o.kind()==SubscriptionOperation.Kind.CLEAR_CHANGE)key+=":SCHEDULE_RELEASE";
        else if(o.kind()==SubscriptionOperation.Kind.CHANGE){
            var q=planProofs.quote(command.account().id(),StripeBillingMutationAdapter.quoteId(command.providerQuoteReference())).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);
            if(q.timing()==ChangeTiming.NEXT_PERIOD)key+=":SCHEDULE_CREATE";
        }else throw mismatch();
        var original=steps.find(command.account().id(),key).orElse(null);
        if(original==null||original.firstDispatchedAt()==null)return null;
        if(original.status()!=ProviderOperation.Status.ACKNOWLEDGED&&(!ProviderOperationGuard.withinWriteWindow(original.firstDispatchedAt(),providerClock.instant())
            ||original.retryBefore()==null||!providerClock.instant().isBefore(original.retryBefore())))return unknown();
        return o.kind()==SubscriptionOperation.Kind.CLEAR_CHANGE?clearChange(command):change(command);
    }
    public Reply cancel(Command command,boolean immediate){throw unsupported();}
    public Reply withdrawCancel(Command command){throw unsupported();}
    public Reply setup(Command command){var proof=prepareSetup(command);if(command.providerSubscriptionReference()==null)throw conflict();return accepted(command.providerSubscriptionReference());}
    public Reply recover(Command command){throw unsupported();}
    public Reply resume(Command command){
        if(proofs==null||resumeReceipts==null)throw unsupported();var a=command.account();var s=ownership.owned(a,command.operation().subscriptionId());
        var o=operations.find(s.siteId(),s.id(),command.operation().id()).orElseThrow(StripeSubscriptionProviderAdapter::conflict);
        if(!o.equals(command.operation())||o.kind()!=SubscriptionOperation.Kind.RESUME||!o.fingerprint().matches("v1\\.resume\\.none\\.[0-9a-f]{64}")||!command.recurringConsent()||command.startTrial()||!s.billingPrice().equals(command.current())||!Objects.equals(s.providerReference(),command.providerSubscriptionReference())||command.target()!=null||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null||s.providerReference()==null)throw conflict();
        var b=bindings.findCustomer(a.id(),s.siteId(),s.customerId()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);ownership.requireBinding(a,s,b);var p=ownership.price(a,s,s.billingPrice());
        String fp=resumeFingerprint(a,s,o,b);var reserved=steps.reserve(a,ProviderOperation.Kind.SUBSCRIPTION_UPDATE,o.id(),o.providerOperationKey(),fp);
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED){var saved=proofs.resume(a.id(),o.id()).orElseThrow(StripeSubscriptionProviderAdapter::mismatch);if(!saved.step().equals(reserved.id())||!saved.reference().equals(s.providerReference()))throw mismatch();return accepted(s.providerReference());}
        if(s.billingStatus()!=Subscription.Status.PAUSED||o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw conflict();
        boolean first=reserved.firstDispatchedAt()==null;String owner=UUID.randomUUID().toString();var claim=steps.claim(reserved.id(),owner,providerClock.instant(),Duration.ofSeconds(60));if(claim.isEmpty())return unknown();var step=claim.get();
        verification.verify(a);
        try{
            var current=client(a).v1().subscriptions().retrieve(s.providerReference());StripeBillingOwnership.requireCurrentRemote(current,s,b,p);
            if(first){
                if(!"paused".equals(current.getStatus())||current.getDefaultPaymentMethod()==null)throw mismatch();String method=current.getDefaultPaymentMethod();
                var pm=client(a).v1().paymentMethods().retrieve(method);if(pm==null||!method.equals(pm.getId())||!"payment_method".equals(pm.getObject())||!Boolean.FALSE.equals(pm.getLivemode())||!b.providerReference().equals(pm.getCustomer())||!"card".equals(pm.getType())||pm.getCard()==null)throw mismatch();
            }
            proofs.authorizeResume(s,o);String key=guard.requireDispatch(step,a,fp,providerClock.instant());
            var response=client(a).v1().subscriptions().resume(s.providerReference(),com.stripe.param.SubscriptionResumeParams.builder()
                .setBillingCycleAnchor(com.stripe.param.SubscriptionResumeParams.BillingCycleAnchor.builder().setType(com.stripe.param.SubscriptionResumeParams.BillingCycleAnchor.Type.NOW).build())
                .setPaymentBehavior(com.stripe.param.SubscriptionResumeParams.PaymentBehavior.RESUME_ON_PAYMENT_SUCCESS).setProrationBehavior(com.stripe.param.SubscriptionResumeParams.ProrationBehavior.NONE).build(),RequestOptions.builder().setIdempotencyKey(key).build());
            StripeBillingOwnership.requireCurrentRemote(response,s,b,p);String invoice=response.getLatestInvoice();if(invoice==null||!invoice.matches("in_[A-Za-z0-9]{1,252}"))throw mismatch();
            var result=client(a).v1().invoices().retrieve(invoice);
            if(result==null||!invoice.equals(result.getId())||!"invoice".equals(result.getObject())||!Boolean.FALSE.equals(result.getLivemode())||!b.providerReference().equals(result.getCustomer())||!"subscription_cycle".equals(result.getBillingReason())||!"charge_automatically".equals(result.getCollectionMethod())||!s.billingPrice().regularAmount().currency().equalsIgnoreCase(result.getCurrency())||!Objects.equals(result.getAmountDue(),s.billingPrice().regularAmount().minorUnits())||!StripeBillingOwnership.inWindow(result.getCreated(),step)||result.getParent()==null||!"subscription_details".equals(result.getParent().getType())||result.getParent().getSubscriptionDetails()==null||!s.providerReference().equals(result.getParent().getSubscriptionDetails().getSubscription()))throw mismatch();
            var lines=client(a).v1().invoices().lineItems().list(invoice,com.stripe.param.InvoiceLineItemListParams.builder().setLimit(10L).build());
            if(lines==null||!"list".equals(lines.getObject())||!Boolean.FALSE.equals(lines.getHasMore())||lines.getData()==null||lines.getData().size()!=1)throw mismatch();var line=lines.getData().getFirst();
            if(!"line_item".equals(line.getObject())||!Boolean.FALSE.equals(line.getLivemode())||!invoice.equals(line.getInvoice())||!s.providerReference().equals(line.getSubscription())||!Objects.equals(line.getQuantity(),1L)||!Objects.equals(line.getAmount(),s.billingPrice().regularAmount().minorUnits())||!s.billingPrice().regularAmount().currency().equalsIgnoreCase(line.getCurrency())||line.getPeriod()==null||line.getPeriod().getStart()==null||line.getPeriod().getEnd()==null||line.getPeriod().getStart()<step.firstDispatchedAt().getEpochSecond()||line.getPeriod().getStart()>providerClock.instant().getEpochSecond()||line.getPeriod().getEnd()<=line.getPeriod().getStart()||line.getParent()==null||line.getParent().getSubscriptionItemDetails()==null||!s.providerReference().equals(line.getParent().getSubscriptionItemDetails().getSubscription())||!current.getItems().getData().getFirst().getId().equals(line.getParent().getSubscriptionItemDetails().getSubscriptionItem())||!Boolean.FALSE.equals(line.getParent().getSubscriptionItemDetails().getProration())||line.getPricing()==null||line.getPricing().getPriceDetails()==null||!p.providerPriceReference().equals(line.getPricing().getPriceDetails().getPrice())||!p.providerProductReference().equals(line.getPricing().getPriceDetails().getProduct()))throw mismatch();
            var period=new Subscription.Period(Instant.ofEpochSecond(line.getPeriod().getStart()),Instant.ofEpochSecond(line.getPeriod().getEnd()));
            return accepted(resumeReceipts.record(s,o,step,owner,invoice,period).providerReference());
        }catch(StripeException|RuntimeException uncertain){steps.unknown(step.id(),owner,step.version());return unknown();}
    }
    public Reply confirmAction(Command command,String actionToken){if(billingActions==null)throw unsupported();return billingActions.get().confirmAction(command,actionToken);}
    public OwnedSetupReceipt confirmPreparedSetup(Command command,String token){if(billingActions==null)throw unsupported();return billingActions.get().confirmPreparedSetup(command,token);}
    public PortalNavigation portal(Command command,String allowedReturnUrl){throw unsupported();}
    private StripeClient client(PaymentAccount account){return clients.create(credentials.resolve(account));}
    private static Reply accepted(String reference){if(reference==null||!reference.matches("sub_[A-Za-z0-9]{1,251}"))throw mismatch();return new Reply(Outcome.ACCEPTED,reference,null);}
    private static Reply unknown(){return new Reply(Outcome.UNKNOWN,null,null);}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request");}
    private static PaymentApplicationException mismatch(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
    private static PaymentApplicationException unsupported(){return new PaymentApplicationException("PROVIDER_CAPABILITY_UNAVAILABLE",409,"Provider capability unavailable");}
}
