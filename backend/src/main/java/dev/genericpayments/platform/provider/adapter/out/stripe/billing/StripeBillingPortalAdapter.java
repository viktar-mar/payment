package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import java.time.Clock;
import java.time.*;
import java.util.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.billingportal.*;
import com.fasterxml.jackson.databind.JsonNode;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionPortalService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership.*;
import org.springframework.transaction.annotation.*;

/** Provider HTTP is outside all application and persistence transactions. */
@Transactional(propagation=Propagation.NEVER)
public class StripeBillingPortalAdapter {
    private final StripeBillingOwnership ownership;private final StripePortalConfigurationPort proofs;private final ProviderOperationStore steps;
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;private final PaymentAccountVerificationPort verification;
    private final Clock clock;private final SubscriptionObjectPort objects;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String FEATURES="invoice_history:true;cancel:at_period_end;proration:none;reason:false;customer:false;method:false;update:false;retention:none;login:false";
    public StripeBillingPortalAdapter(StripeBillingOwnership ownership,StripePortalConfigurationPort proofs,
        ProviderOperationStore steps,StripeCredentialResolver credentials,StripeClientFactory clients,
        PaymentAccountVerificationPort verification,Clock clock,SubscriptionObjectPort objects){
        this.ownership=ownership;this.proofs=proofs;this.steps=steps;this.credentials=credentials;this.clients=clients;this.verification=verification;this.clock=clock;this.objects=objects;
    }
    public PortalNavigation create(Command command,String returnUrl){
        var scope=proofs.capture(command,returnUrl);var local=ownership.owned(command.account(),command.operation().subscriptionId());
        if(!scope.account().equals(command.account())||!scope.operation().equals(command.operation())||!scope.members().contains(local)
            ||!ownership.lookupOperation(new Lookup(command.account(),local.id(),local.providerReference(),command.operation().providerOperationKey())).equals(command.operation())
            ||command.operation().kind()!=SubscriptionOperation.Kind.PORTAL||!local.billingPrice().equals(command.current())
            ||!Objects.equals(local.providerReference(),command.providerSubscriptionReference())||command.target()!=null||command.startTrial()||command.recurringConsent()
            ||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null||!returnUrl.equals(scope.returnUrl()))throw unavailable();
        SubscriptionPortalService.requireAllowedReturn(scope.policy(),returnUrl);requireBinding(scope.account(),local,scope.customer());
        try{
            verification.verify(scope.account());var client=clients.create(credentials.resolve(scope.account()));
            inventory(client,scope);
            var config=configuration(client,scope);
            recognize(client.v1().billingPortal().configurations().retrieve(config.reference()),config,scope);
            proofs.authorize(scope);
            var proposed=new Session(scope.operation().id(),config,scope,sessionFingerprint(scope,config),null,null,null);
            var intent=proofs.session(proposed);
            if(!intent.fingerprint().equals(proposed.fingerprint())||!intent.configuration().equals(config)||!intent.operation().equals(scope.operation().id()))throw unavailable();
            var reserved=steps.reserve(scope.account(),ProviderOperation.Kind.PORTAL_SESSION_CREATE,scope.operation().id(),scope.operation().providerOperationKey()+":SESSION",intent.fingerprint());
            if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED)throw unavailable();
            var owner=UUID.randomUUID().toString();var step=steps.claim(reserved.id(),owner,clock.instant(),Duration.ofSeconds(60)).orElseThrow(StripeBillingPortalAdapter::unavailable);
            try{
                proofs.authorize(scope);var key=new ProviderOperationGuard().requireDispatch(step,scope.account(),intent.fingerprint(),clock.instant());
                var response=client.v1().billingPortal().sessions().create(sessionParams(scope,config),RequestOptions.builder().setIdempotencyKey(key).build());
                recognize(response,scope,config,step);
                steps.acknowledge(step.id(),owner,step.version(),response.getId());
                var expiry=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(300);
                proofs.record(intent,step.id(),response.getId(),expiry);
                return new PortalNavigation(response.getUrl(),expiry,false);
            }catch(StripeException|RuntimeException uncertain){retainUnknown(step);throw unavailable();}
        }catch(StripeException uncertain){throw unavailable();}
    }
    private Configuration configuration(StripeClient client,Scope scope)throws StripeException{
        var id=UUID.randomUUID();var digest=policyDigest(scope.policy());var key="portal_config_"+id;
        var proposed=new Configuration(id,scope.account().id(),scope.policy().siteId(),scope.policy().version(),digest,scope.operation().id(),key,
            configFingerprint(scope,id,scope.operation().id(),key,digest),null,null);
        var intent=proofs.intent(proposed);
        if(!intent.account().equals(scope.account().id())||!intent.site().equals(scope.policy().siteId())||intent.policy()!=scope.policy().version()||!intent.digest().equals(digest)
            ||!intent.fingerprint().equals(configFingerprint(scope,intent.id(),intent.owner(),intent.key(),digest)))throw unavailable();
        var reserved=steps.reserve(scope.account(),ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,intent.id(),intent.key(),intent.fingerprint());
        if(reserved.status()==ProviderOperation.Status.ACKNOWLEDGED){
            if(intent.reference()!=null&&(!intent.reference().equals(reserved.providerReference())||!Objects.equals(intent.step(),reserved.id())))throw unavailable();
            if(intent.reference()!=null)return intent;
            var bound=new Configuration(intent.id(),intent.account(),intent.site(),intent.policy(),intent.digest(),intent.owner(),intent.key(),intent.fingerprint(),reserved.providerReference(),reserved.id());
            recognize(client.v1().billingPortal().configurations().retrieve(bound.reference()),bound,scope);
            return proofs.bind(intent,reserved.id(),reserved.providerReference());
        }
        if(intent.reference()!=null)throw unavailable();
        var owner=UUID.randomUUID().toString();var step=steps.claim(reserved.id(),owner,clock.instant(),Duration.ofSeconds(60)).orElseThrow(StripeBillingPortalAdapter::unavailable);
        try{
            proofs.authorize(scope);var original=new ProviderOperationGuard().requireDispatch(step,scope.account(),intent.fingerprint(),clock.instant());
            var response=client.v1().billingPortal().configurations().create(configurationParams(scope),RequestOptions.builder().setIdempotencyKey(original).build());
            if(!inWindow(response.getCreated(),step)||response.getCreated()>clock.instant().getEpochSecond())throw unavailable();
            var bound=new Configuration(intent.id(),intent.account(),intent.site(),intent.policy(),intent.digest(),intent.owner(),intent.key(),intent.fingerprint(),response.getId(),step.id());
            recognize(response,bound,scope);steps.acknowledge(step.id(),owner,step.version(),response.getId());
            return proofs.bind(intent,step.id(),response.getId());
        }catch(StripeException|RuntimeException uncertain){retainUnknown(step);throw unavailable();}
    }
    private void retainUnknown(ProviderOperation step){
        var current=steps.findById(step.id()).orElseThrow(StripeBillingPortalAdapter::unavailable);
        if(current.status()==ProviderOperation.Status.DISPATCHING&&current.version()==step.version()&&Objects.equals(current.leaseOwner(),step.leaseOwner()))steps.unknown(step.id(),step.leaseOwner(),step.version());
    }
    private void inventory(StripeClient client,Scope scope)throws StripeException{
        var customer=client.v1().customers().retrieve(scope.customer().providerReference());
        if(!scope.customer().providerReference().equals(customer.getId())||!"customer".equals(customer.getObject())||!Boolean.FALSE.equals(customer.getLivemode())||Boolean.TRUE.equals(customer.getDeleted()))throw unavailable();
        var locals=new HashMap<String,Subscription>();
        for(var s:scope.members())if(!s.paymentAccountId().equals(scope.account().id())||!s.siteId().equals(scope.customer().siteId())||!s.customerId().equals(scope.customer().customerId())
            ||s.providerReference()==null||locals.put(s.providerReference(),s)!=null)throw unavailable();
        var seen=new HashSet<String>();String after=null;boolean complete=false;
        for(int page=0;page<10;page++){
            var values=client.v1().subscriptions().list(com.stripe.param.SubscriptionListParams.builder().setCustomer(customer.getId()).setStatus(com.stripe.param.SubscriptionListParams.Status.ALL).setLimit(100L).setStartingAfter(after).build());
            if(values==null||!"list".equals(values.getObject())||values.getHasMore()==null||values.getData()==null)throw unavailable();
            for(var r:values.getData()){
                var s=locals.get(r.getId());if(s==null||!seen.add(r.getId())||!"subscription".equals(r.getObject())||!Boolean.FALSE.equals(r.getLivemode())||!customer.getId().equals(r.getCustomer()))throw unavailable();
                if(!s.nonTerminal()){if(!Set.of("canceled","incomplete_expired").contains(r.getStatus()))throw unavailable();continue;}
                requireCurrentRemote(r,s,scope.customer(),ownership.price(scope.account(),s,s.billingPrice()));
                var boundary=SubscriptionCancellationRules.liveBoundary(s,clock.instant());
                if(s.billingStatus()!=Subscription.Status.ACTIVE||!"active".equals(r.getStatus())||r.getCancelAtPeriodEnd()==null||s.trial()!=null||r.getTrialStart()!=null||r.getTrialEnd()!=null||s.debt()!=null||s.pendingChange()!=null
                    ||r.getSchedule()!=null||r.getPendingUpdate()!=null||s.currentPeriod()==null||boundary==null||!boundary.equals(s.currentPeriod().end())||!boundary.equals(s.paidThrough())
                    ||!StripeBillingMutationAdapter.itemPeriod(r).equals(s.currentPeriod())||s.entitledPrice()==null||!s.entitledPrice().equals(s.billingPrice())||s.billingPrice().regularAmount().minorUnits()<=0)throw unavailable();
                var nativeCancellation=r.getCancelAt()!=null?Instant.ofEpochSecond(r.getCancelAt()):Boolean.TRUE.equals(r.getCancelAtPeriodEnd())?boundary:null;
                if(!Objects.equals(s.cancellation()==null?null:s.cancellation().effectiveAt(),nativeCancellation))throw unavailable();
                // The resulting schedule is withdrawable strictly before the same captured boundary.
                if(s.cancellation()!=null)SubscriptionCancellationRules.requireWithdrawable(s,clock.instant());
                if(!clock.instant().isBefore(boundary))throw unavailable();
            }
            if(!values.getHasMore()){complete=true;break;}if(values.getData().isEmpty())throw unavailable();after=values.getData().getLast().getId();
        }
        if(!complete||!seen.equals(locals.keySet()))throw unavailable();
        seen.clear();after=null;complete=false;
        for(int page=0;page<10;page++){
            var values=client.v1().invoices().list(com.stripe.param.InvoiceListParams.builder().setCustomer(customer.getId()).setLimit(100L).setStartingAfter(after).build());
            if(values==null||!"list".equals(values.getObject())||values.getHasMore()==null||values.getData()==null)throw unavailable();
            for(var r:values.getData()){
                if(r.getId()==null||!r.getId().matches("in_[A-Za-z0-9]{1,252}")||!seen.add(r.getId())||!"invoice".equals(r.getObject())||!Boolean.FALSE.equals(r.getLivemode())||!customer.getId().equals(r.getCustomer())
                    ||r.getStatus()==null||!Set.of("paid","void","open","draft","uncollectible").contains(r.getStatus()))throw unavailable();
                var owner=objects.resolve(dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE,scope.account().id(),SubscriptionObjectPort.Kind.INVOICE,r.getId()).orElseThrow(StripeBillingPortalAdapter::unavailable);
                var s=scope.members().stream().filter(m->m.id().equals(owner)).findFirst().orElseThrow(StripeBillingPortalAdapter::unavailable);
                var parent=r.getParent();if(parent!=null&&parent.getSubscriptionDetails()!=null&&!s.providerReference().equals(parent.getSubscriptionDetails().getSubscription()))throw unavailable();
                // The initial supported action scope has no outstanding collectible/debt invoice.
                if(!Set.of("paid","void").contains(r.getStatus()))throw unavailable();
            }
            if(!values.getHasMore()){complete=true;break;}if(values.getData().isEmpty())throw unavailable();after=values.getData().getLast().getId();
        }
        if(!complete)throw unavailable();
    }
    public static String policyDigest(SubscriptionPolicy p){return StripeBillingGrantTokens.hash(FEATURES+"|"+p.siteId()+"|"+p.version()+"|"+p.enabled()+"|"+p.graceDays()+"|"+p.allowImmediateCancellation()+"|"+p.portalEnabled()+"|"+p.deferUpgrades()+"|"+new TreeSet<>(p.portalReturnUrls()));}
    public static String configFingerprint(Scope s,UUID id,UUID owner,String key,String digest){return StripeRequestFingerprint.of(StripeBillingGrantTokens::hash,s.account(),"PORTAL_CONFIG_V1",id.toString(),owner.toString(),key,s.policy().siteId().value().toString(),Long.toString(s.policy().version()),digest);}
    public static String sessionFingerprint(Scope s,Configuration c){return StripeRequestFingerprint.of(StripeBillingGrantTokens::hash,s.account(),"PORTAL_SESSION_V1",s.operation().id().toString(),s.operation().providerOperationKey(),s.operation().fingerprint(),s.customer().id().toString(),s.customer().providerReference(),c.id().toString(),c.reference(),c.digest(),s.returnUrl(),"subscription_cancel:redirect",s.operation().providerReference(),s.members().stream().sorted(Comparator.comparing(Subscription::id)).map(Subscription::toString).toList().toString());}
    private static String defaultReturn(Scope s){return s.policy().portalReturnUrls().stream().sorted().findFirst().orElseThrow(StripeBillingPortalAdapter::unavailable);}
    private static ConfigurationCreateParams configurationParams(Scope s){
        return ConfigurationCreateParams.builder().setDefaultReturnUrl(defaultReturn(s)).setLoginPage(ConfigurationCreateParams.LoginPage.builder().setEnabled(false).build())
            .setFeatures(ConfigurationCreateParams.Features.builder().setInvoiceHistory(ConfigurationCreateParams.Features.InvoiceHistory.builder().setEnabled(true).build())
                .setCustomerUpdate(ConfigurationCreateParams.Features.CustomerUpdate.builder().setEnabled(false).build()).setPaymentMethodUpdate(ConfigurationCreateParams.Features.PaymentMethodUpdate.builder().setEnabled(false).build())
                .setSubscriptionUpdate(ConfigurationCreateParams.Features.SubscriptionUpdate.builder().setEnabled(false).build())
                .setSubscriptionCancel(ConfigurationCreateParams.Features.SubscriptionCancel.builder().setEnabled(true).setMode(ConfigurationCreateParams.Features.SubscriptionCancel.Mode.AT_PERIOD_END)
                    .setProrationBehavior(ConfigurationCreateParams.Features.SubscriptionCancel.ProrationBehavior.NONE).setCancellationReason(ConfigurationCreateParams.Features.SubscriptionCancel.CancellationReason.builder().setEnabled(false).build()).build()).build()).build();
    }
    private static SessionCreateParams sessionParams(Scope s,Configuration c){return SessionCreateParams.builder().setCustomer(s.customer().providerReference()).setConfiguration(c.reference()).setReturnUrl(s.returnUrl())
        .setFlowData(SessionCreateParams.FlowData.builder().setType(SessionCreateParams.FlowData.Type.SUBSCRIPTION_CANCEL).setSubscriptionCancel(SessionCreateParams.FlowData.SubscriptionCancel.builder().setSubscription(s.operation().providerReference()).build())
            .setAfterCompletion(SessionCreateParams.FlowData.AfterCompletion.builder().setType(SessionCreateParams.FlowData.AfterCompletion.Type.REDIRECT).setRedirect(SessionCreateParams.FlowData.AfterCompletion.Redirect.builder().setReturnUrl(s.returnUrl()).build()).build()).build()).build();}
    private static JsonNode raw(com.stripe.model.StripeObject value){try{if(value.getLastResponse()==null)throw unavailable();return JSON.readTree(value.getLastResponse().body());}catch(java.io.IOException bad){throw unavailable();}}
    private static void keys(JsonNode value,String... allowed){if(value==null||!value.isObject())throw unavailable();var permitted=Set.of(allowed);value.fieldNames().forEachRemaining(k->{if(!permitted.contains(k))throw unavailable();});}
    private static void flag(JsonNode node,String name,boolean expected){if(node==null||!node.path(name).isBoolean()||node.path(name).booleanValue()!=expected)throw unavailable();}
    private static void empty(JsonNode node,String field){var v=node.get(field);if(v!=null&&!v.isNull()&&(!v.isArray()||!v.isEmpty()))throw unavailable();}
    private static void recognize(com.stripe.model.billingportal.Configuration r,Configuration c,Scope s){
        if(r==null||c.reference()==null||!c.reference().matches("bpc_[A-Za-z0-9]{1,251}")||!c.reference().equals(r.getId())||!"billing_portal.configuration".equals(r.getObject())
            ||!Boolean.TRUE.equals(r.getActive())||!Boolean.FALSE.equals(r.getLivemode())||!defaultReturn(s).equals(r.getDefaultReturnUrl()))throw unavailable();
        var n=raw(r);var f=n.get("features");keys(f,"invoice_history","customer_update","payment_method_update","subscription_update","subscription_cancel");
        keys(f.get("invoice_history"),"enabled");flag(f.get("invoice_history"),"enabled",true);
        keys(f.get("customer_update"),"enabled","allowed_updates");flag(f.get("customer_update"),"enabled",false);empty(f.get("customer_update"),"allowed_updates");
        keys(f.get("payment_method_update"),"enabled");flag(f.get("payment_method_update"),"enabled",false);
        keys(f.get("subscription_update"),"enabled","default_allowed_updates","products");flag(f.get("subscription_update"),"enabled",false);empty(f.get("subscription_update"),"default_allowed_updates");empty(f.get("subscription_update"),"products");
        var cancel=f.get("subscription_cancel");keys(cancel,"enabled","mode","proration_behavior","cancellation_reason");flag(cancel,"enabled",true);
        if(!"at_period_end".equals(cancel.path("mode").asText())||!"none".equals(cancel.path("proration_behavior").asText()))throw unavailable();
        keys(cancel.get("cancellation_reason"),"enabled","options");flag(cancel.get("cancellation_reason"),"enabled",false);empty(cancel.get("cancellation_reason"),"options");
        keys(n.get("login_page"),"enabled","url");flag(n.get("login_page"),"enabled",false);if(!n.path("login_page").path("url").isMissingNode()&&!n.path("login_page").path("url").isNull())throw unavailable();
    }
    private void recognize(com.stripe.model.billingportal.Session r,Scope s,Configuration c,ProviderOperation step){
        if(r==null||r.getId()==null||!r.getId().matches("bps_[A-Za-z0-9]{1,251}")||!"billing_portal.session".equals(r.getObject())||!Boolean.FALSE.equals(r.getLivemode())
            ||!s.customer().providerReference().equals(r.getCustomer())||!c.reference().equals(r.getConfiguration())||!s.returnUrl().equals(r.getReturnUrl())||!inWindow(r.getCreated(),step)||r.getCreated()>clock.instant().getEpochSecond()||!validNavigation(r.getUrl()))throw unavailable();
        var n=raw(r);if(!n.path("on_behalf_of").isMissingNode()&&!n.path("on_behalf_of").isNull()||!n.path("customer_account").isMissingNode()&&!n.path("customer_account").isNull())throw unavailable();
        var flow=n.get("flow");keys(flow,"type","subscription_cancel","after_completion");if(!"subscription_cancel".equals(flow.path("type").asText()))throw unavailable();
        var cancel=flow.get("subscription_cancel");keys(cancel,"subscription","retention");if(!s.operation().providerReference().equals(cancel.path("subscription").asText())||cancel.hasNonNull("retention"))throw unavailable();
        var done=flow.get("after_completion");keys(done,"type","redirect");keys(done.get("redirect"),"return_url");if(!"redirect".equals(done.path("type").asText())||!s.returnUrl().equals(done.path("redirect").path("return_url").asText()))throw unavailable();
    }
    /** Deliberately supported documented sandbox URL subset; never an arbitrary HTTPS redirect. */
    public static boolean validNavigation(String value){return dev.genericpayments.platform.provider.application.model.StripePortalNavigationUrl.valid(value);}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("SUBSCRIPTION_PORTAL_UNAVAILABLE",409,"Portal unavailable");}
}
