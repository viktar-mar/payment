package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** One original no-trial payment. Durable adapters own every mutating provider claim. */
public final class OwnedBillingFirstPaymentScenario implements OwnedBillingFirstPaymentRuntime.Operations {
    private final OwnedBillingFirstPaymentRuntime.Admission admission;
    private final Function<Class<?>,Object> beans;
    private final Clock utc;
    private final OwnedBillingFirstPaymentRuntime.State state;
    private String prepareFailureStage;
    private PaymentAccount account;
    private SiteId site;private CustomerId customer;private PriceId price;
    private String token,origin,createKey,originalInvoice,originalIntent;
    private CustomerSessionContext originalSession;
    private ClockOwner owner;
    private SubscriptionCommandTransaction.ReservedCreate reserved;
    private OwnedClockSubscriptionReceipt receipt;
    private SubscriptionConfirmationUseCase.Session selected;
    private StripeBillingClockSubscriptionAdapter adapter;

    public OwnedBillingFirstPaymentScenario(OwnedBillingFirstPaymentRuntime.Admission admission,Function<Class<?>,Object> beans,Clock utc,Instant deadline) {
        this.admission=Objects.requireNonNull(admission);this.beans=Objects.requireNonNull(beans);this.utc=Objects.requireNonNull(utc);
        if(!utc.getZone().equals(ZoneOffset.UTC))throw OwnedBillingFirstPaymentRuntime.denied();
        this.state=new OwnedBillingFirstPaymentRuntime.State(utc,deadline);
    }
    private <T>T bean(Class<T> type){return type.cast(beans.apply(type));}
    @Override public synchronized String prepareFailureStage(){return prepareFailureStage==null?"UNCLASSIFIED":prepareFailureStage;}
    @Override public synchronized JsonObject prepare(JsonObject request) {
        String checkpoint="REQUEST";
        StripeBillingClockActionAdapter actionAdapter=null;
        try {
            state.begin("NEW","PREPARING");
            OwnedBillingFirstPaymentRuntime.fields(request,Set.of("run_id","source_sha","account_id","site_id","customer_id","price_id","origin","create_key","customer_token"));
            require(admission.run().toString().equals(text(request,"run_id"))&&admission.source().equals(text(request,"source_sha")));
            site=new SiteId(uuid(request,"site_id"));customer=new CustomerId(uuid(request,"customer_id"));price=new PriceId(uuid(request,"price_id"));
            origin=text(request,"origin");token=text(request,"customer_token");createKey=key(text(request,"create_key"));
            var accountId=new PaymentAccountId(uuid(request,"account_id"));
            checkpoint="PREFLIGHT";
            account=bean(LoadPaymentAccountPort.class).findById(accountId).orElseThrow();
            require(account.provider()==PaymentProviderType.STRIPE&&account.mode()==PaymentAccountMode.SANDBOX&&account.status()==PaymentAccountStatus.ACTIVE
                &&account.externalAccountId().equals("acct_1UPiMpELswAd8tpR"));
            originalSession=bean(CustomerSessionResolver.class).resolveRequired(token,origin);
            var context=context(createKey);
            // All preconditions are checked before even the clock POST. reserveCreate repeats authority/catalog checks.
            bean(SubscriptionTransactionPort.class).required(()->{bean(SubscriptionAuthorityPort.class).revalidate(context,true);return null;});
            var actualSite=bean(LoadSitePort.class).findById(site).orElseThrow();require(actualSite.paymentAccountId().equals(account.id()));
            var terms=bean(SubscriptionCatalogPort.class).published(site,price);
            require(terms.regularAmount().minorUnits()==2000&&terms.regularAmount().currency().equals("EUR")&&terms.interval()==BillingInterval.MONTH&&terms.trialDays()==0&&terms.taxMode()==TaxMode.INCLUSIVE);
            var mappings=bean(dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort.class);
            require(mappings.findProduct(account.id(),terms.productId()).isPresent()&&mappings.findPrice(account.id(),price).isPresent());
            require(bean(SubscriptionPolicyPort.class).load(site).enabled());
            var callbacks=bean(CallbackConfigurationPort.class).find(site.value()).orElseThrow();require(callbacks.enabled()&&callbacks.secretId()!=null);
            bean(Stage2TelemetryPort.class).requireContext(new Stage2TelemetryPort.Context(actualSite.key().value(),Stage2TelemetryPort.Provider.STRIPE));
            var grants=bean(StripeBillingGrantTokens.class);require(grants.enabled());
            var credentials=bean(StripeCredentialResolver.class);var clients=bean(StripeClientFactory.class);var verification=bean(PaymentAccountVerificationPort.class);
            credentials.resolve(account);
            var operations=bean(ProviderOperationStore.class);var clocks=bean(StripeBillingClockStore.class);
            checkpoint="CLOCK_ADMISSION";
            state.check();owner=clocks.admitFresh(account,site,customer,admission.run(),StripeBillingTestClockAdapter.API_VERSION,admission.frozen(),utc.instant());
            checkpoint="CLOCK_CREATE";
            var clockAdapter=new StripeBillingTestClockAdapter(credentials,clients,verification,operations,clocks,bean(DigestPort.class));
            state.check();clockAdapter.createFresh(account,owner);
            checkpoint="CUSTOMER_CREATE";
            state.check();bean(StripeBillingCustomerAdapter.class).ensureOwnedCustomer(account,owner);
            checkpoint="LOCAL_RESERVE";
            state.check();reserved=bean(SubscriptionCommandTransaction.class).reserveCreate(new CreateSubscriptionUseCase.Command(context(createKey),price,true));
            require(reserved.owner()&&reserved.account().equals(account));
            checkpoint="SUBSCRIPTION_CREATE";
            adapter=new StripeBillingClockSubscriptionAdapter(credentials,clients,verification,operations,clocks,bean(StripeBillingClockSubscriptionStore.class),bean(StripeBillingBindingPort.class),bean(SubscriptionStore.class),bean(SubscriptionOperationStore.class),utc);
            var command=new Command(account,reserved.operation(),reserved.subscription().billingPrice(),null,null,null,null,true,false);
            state.check();var reply=adapter.createOwned(command,owner);require(reply.outcome()==Outcome.ACCEPTED&&reply.action()==null);
            checkpoint="LOCAL_RECORD";
            bean(SubscriptionCommandTransaction.class).recordReply(reserved.operation().id(),reply);
            checkpoint="RECEIPT";
            receipt=bean(StripeBillingClockSubscriptionStore.class).requireReceipt(account.id(),reserved.subscription().id());
            // Selection has its own request key; the SDK's later confirm key is not known yet.
            checkpoint="ACTION_SCOPE";
            var scope=scope("initial-select-"+admission.run());
            checkpoint="ACTION_SELECT";
            actionAdapter=new StripeBillingClockActionAdapter(receipt,bean(SubscriptionInteractionTransaction.class),bean(SubscriptionActionStore.class),grants,credentials,clients,verification,utc);
            state.check();var next=actionAdapter.nextAction(scope);
            checkpoint="ACTION_RESULT";
            require(next!=null&&next.kind()==ActionKind.PROVIDER_CLIENT);
            checkpoint="ACTION_BINDING";
            var binding=bean(SubscriptionTransactionPort.class).required(()->bean(SubscriptionActionStore.class).billing(StripeBillingGrantTokens.hash(next.secret())).orElseThrow().binding());
            originalInvoice=binding.invoiceReference();originalIntent=binding.intentReference();require(originalInvoice!=null&&originalIntent!=null);
            checkpoint="SESSION_SELECT";
            state.check();selected=service(scope).select(scope,next.secret());
            require(selected.protocol().equals("SERVER_CONFIRM_V2")&&selected.flow()==SubscriptionClientSession.Flow.PAYMENT&&selected.attemptId()!=null&&selected.actionToken()==null);
            checkpoint="RESPONSE_BUILD";
            state.finish("PREPARING","PREPARED");
            var result=status();var actionJson=new JsonObject();actionJson.addProperty("kind",next.kind().name());actionJson.addProperty("secret",next.secret());actionJson.addProperty("expires_at",next.expiresAt().toString());result.add("action",actionJson);
            var session=new JsonObject();session.addProperty("protocol",selected.protocol());session.addProperty("provider",selected.provider());session.addProperty("flow",selected.flow().name());
            session.addProperty("publishable_key",selected.publishableKey());session.addProperty("client_secret",selected.clientSecret());session.add("action_token",JsonNull.INSTANCE);
            session.addProperty("attempt_id",selected.attemptId().toString());session.addProperty("capability",selected.capability());session.addProperty("expires_at",selected.expiresAt().toString());result.add("session",session);
            return result;
        }catch(RuntimeException invalid){if(prepareFailureStage==null)prepareFailureStage=actionFailureStage(checkpoint,checkpoint.equals("ACTION_SELECT")&&actionAdapter!=null?actionAdapter.selectionFailureStage():null);abort();throw OwnedBillingFirstPaymentRuntime.denied();}
    }
    /** Adapter metadata refines only the selection boundary; no external detail becomes a stage. */
    static String actionFailureStage(String checkpoint,String adapterStage) {
        if("ACTION_SELECT".equals(checkpoint)&&adapterStage!=null&&Set.of("ACTION_AUTHORITY","ACTION_VERIFICATION","ACTION_GRAPH","ACTION_GRAPH_LINE_ASSOCIATION","ACTION_GRAPH_INVOICE_SETTLEMENT","ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_FLAG","ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_KIND","ACTION_GRAPH_INVOICE_SETTLEMENT_COLLECTION_METHOD","ACTION_GRAPH_INVOICE_SETTLEMENT_PAID_OFF_STRIPE","ACTION_GRAPH_INVOICE_SETTLEMENT_OVERPAID","ACTION_GRAPH_INVOICE_SETTLEMENT_STARTING_BALANCE","ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE","ACTION_GRAPH_INVOICE_SETTLEMENT_PRE_CREDIT_NOTES","ACTION_GRAPH_INVOICE_SETTLEMENT_POST_CREDIT_NOTES","ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNTS","ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNT_AMOUNTS","ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_PRETAX_CREDITS","ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_DISCOUNT_AMOUNTS","ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_PRETAX_CREDITS","ACTION_GRAPH_INVOICE_SETTLEMENT_TOTAL","ACTION_GRAPH_INVOICE_SETTLEMENT_SUBTOTAL","ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_AMOUNT","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_POSITIVE","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_CATALOG","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_PAID","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_REMAINING","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_STATUS","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_PAYMENT","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_TYPE","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_INTENT","ACTION_GRAPH_INVOICE_SETTLEMENT_SNAPSHOT_UNSUPPORTED","ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN_CASH","ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN","ACTION_GRAPH_PAYMENT_ALLOCATION","ACTION_GRAPH_PAYMENT_ALLOCATION_STATUS_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_STATUS_NOT_OPEN","ACTION_GRAPH_PAYMENT_ALLOCATION_DEFAULT_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_DEFAULT_NOT_TRUE","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_REQUESTED_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_REQUESTED_MISMATCH","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_NONZERO","ACTION_GRAPH_PAYMENT_ALLOCATION_PAYMENT_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_TYPE_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_TYPE_UNEXPECTED","ACTION_RESERVATION").contains(adapterStage))return adapterStage;
        return checkpoint;
    }
    @Override public synchronized JsonObject confirm(JsonObject request) {
        try {
            state.begin("PREPARED","CONFIRMING");
            OwnedBillingFirstPaymentRuntime.fields(request,Set.of("attempt_id","capability","confirmation_token","idempotency_key"));
            require(selected.attemptId().equals(uuid(request,"attempt_id")));
            var actualKey=key(text(request,"idempotency_key"));require(!actualKey.equals(createKey));
            // Resolve the retained real customer token again; provider and service bind THIS actual request Scope.
            var scope=scope(actualKey);var result=service(scope).confirm(scope,selected.attemptId(),text(request,"capability"),text(request,"confirmation_token"));
            require(result.status().state()==SubscriptionConfirmationAttempt.State.OBSERVE&&!result.status().retryAllowed()&&result.status().successorAttemptId()==null&&!result.nextActionAllowed());
            state.finish("CONFIRMING","OBSERVE");
            var response=status();response.addProperty("protocol","SERVER_CONFIRM_V2");response.addProperty("attempt_id",selected.attemptId().toString());response.addProperty("state","OBSERVE");response.addProperty("retry_allowed",false);response.add("successor_attempt_id",JsonNull.INSTANCE);response.addProperty("next_action_allowed",false);response.addProperty("expires_at",result.status().expiresAt().toString());return response;
        }catch(RuntimeException invalid){abort();throw OwnedBillingFirstPaymentRuntime.denied();}
    }
    private SubscriptionConfirmationService service(SubscriptionConfirmationUseCase.Scope scope) {
        var phases=bean(SubscriptionInteractionTransaction.class);var roots=bean(SubscriptionActionStore.class);var confirmations=bean(SubscriptionConfirmationStore.class);
        var provider=new StripeBillingClockConfirmationAdapter(receipt,scope,phases,roots,confirmations,bean(StripeBillingGrantTokens.class),bean(StripeCredentialResolver.class),bean(StripeClientFactory.class),bean(PaymentAccountVerificationPort.class),utc);
        var factory=new StaticListableBeanFactory();factory.addBean("ownedInitial",provider);
        return new SubscriptionConfirmationService(phases,roots,confirmations,factory.getBeanProvider(SubscriptionConfirmationProvider.class),bean(CreateSubscriptionClientSessionUseCase.class),utc,receipt);
    }
    @Override public synchronized JsonObject observe() {
        try {
            state.observe();var context=context("initial-observe-"+admission.run());
            bean(SubscriptionTransactionPort.class).required(()->{bean(SubscriptionAuthorityPort.class).revalidate(context,true);return null;});
            var subscription=current();var operation=bean(SubscriptionReadPreparation.class).financial(subscription);
            var fence=new ApplySubscriptionSnapshotUseCase.Fence(subscription.revision(),operation==null?null:operation.id(),operation==null?null:operation.version(),null,context,bean(SubscriptionObservationPort.class).captureFence(subscription));
            // Every read receives its own fence BEFORE retrieval. No observation is ever rebased.
            var observation=adapter.retrieveOwned(new Lookup(account,subscription.id(),receipt.reference(),reserved.operation().providerOperationKey()),owner);
            state.check();
            var period=new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd()));
            require(observation.subscriptionReference().equals(receipt.reference())&&observation.billingPrice().equals(subscription.billingPrice())&&observation.trial()==null);
            if(observation.invoices().stream().noneMatch(i->i.settlement()==SettlementKind.CASH)){require(observation.invoices().stream().allMatch(i->i.settlement()==SettlementKind.OPEN));return status();}
            require(observation.invoices().size()==1&&period.equals(observation.period()));
            var invoice=observation.invoices().getFirst();require(invoice.kind()==BillingRecord.Kind.INITIAL&&invoice.period().equals(period)&&invoice.amountDueMinor()==2000&&invoice.currency().equals("EUR")&&invoice.settlement()==SettlementKind.CASH&&invoice.cashObjects().size()==1&&invoice.reference().equals(originalInvoice)&&invoice.cashObjects().getFirst().reference().equals(originalIntent));
            bean(SubscriptionTransactionPort.class).required(()->{bean(ApplySubscriptionSnapshotUseCase.class).apply(new ApplySubscriptionSnapshotUseCase.Command(account,subscription.id(),observation,SubscriptionEvent.Source.RECONCILIATION,"initial-payment-"+admission.run(),admission.run().toString(),fence));return null;});
            var updated=current();var create=bean(SubscriptionOperationStore.class).find(site,subscription.id(),reserved.operation().id()).orElseThrow();
            require(updated.billingStatus()==Subscription.Status.ACTIVE&&period.equals(updated.currentPeriod())&&period.end().equals(updated.paidThrough())&&updated.billingPrice().equals(updated.entitledPrice())&&create.status()==SubscriptionOperation.Status.SUCCEEDED);
            var access=bean(QuerySubscriptionsUseCase.class).get(context.actor(),site,subscription.id()).access();
            require(access.allowed()&&access.reason()==AccessDecision.Reason.PAID&&access.evaluatedAt().equals(Instant.ofEpochSecond(admission.frozen()))&&access.validUntil().equals(Instant.ofEpochSecond(admission.frozen()).plusSeconds(300)));
            state.settled();var response=status();response.addProperty("period_start",period.start().toString());response.addProperty("paid_through",period.end().toString());response.addProperty("access_valid_until",access.validUntil().toString());response.addProperty("access_evaluated_at",access.evaluatedAt().toString());return response;
        }catch(RuntimeException invalid){abort();throw OwnedBillingFirstPaymentRuntime.denied();}
    }
    private Subscription current(){return bean(SubscriptionStore.class).find(site,reserved.subscription().id()).orElseThrow();}
    private SubscriptionCommandContext context(String key) {
        var session=bean(CustomerSessionResolver.class).resolveRequired(token,origin);
        require(session.siteId().equals(site)&&session.customerId().equals(customer)&&session.origin().equals(origin)&&session.sessionId().equals(originalSession.sessionId())&&session.scopes().equals(originalSession.scopes())&&session.expiresAt().equals(originalSession.expiresAt()));
        return new SubscriptionCommandContext(new SubscriptionActor.Customer(session),site,customer,origin,key,admission.run().toString());
    }
    private SubscriptionConfirmationUseCase.Scope scope(String key){return new SubscriptionConfirmationUseCase.Scope(context(key),reserved.subscription().id(),reserved.operation().id());}
    @Override public synchronized JsonObject status() {
        var result=new JsonObject();result.addProperty("phase",state.phase());result.addProperty("code",state.phase().equals("STOPPED")?"INITIAL_PAYMENT_STOPPED":"OK");result.addProperty("run_id",admission.run().toString());
        if(account!=null)result.addProperty("account_id",account.id().value().toString());if(site!=null)result.addProperty("site_id",site.value().toString());if(customer!=null)result.addProperty("customer_id",customer.value().toString());if(price!=null)result.addProperty("price_id",price.value().toString());
        if(reserved!=null){result.addProperty("subscription_id",reserved.subscription().id().toString());result.addProperty("operation_id",reserved.operation().id().toString());}return result;
    }
    @Override public synchronized void abort(){state.abort();token=null;selected=null;if(owner!=null)try{bean(StripeBillingClockStore.class).stop(owner,StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,utc.instant());}catch(RuntimeException ignored){/* Already stopped/rotated remains denied; no recovery. */}}
    private static void require(boolean valid){if(!valid)throw OwnedBillingFirstPaymentRuntime.denied();}
    private static String text(JsonObject request,String name){var value=request.get(name);require(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString());String text=value.getAsString();require(!text.isBlank()&&text.length()<=4096);return text;}
    private static UUID uuid(JsonObject request,String name){var text=text(request,name);var id=UUID.fromString(text);require(id.toString().equals(text));return id;}
    private static String key(String value){require(value.length()<=255&&value.codePoints().noneMatch(Character::isISOControl));return value;}
}
