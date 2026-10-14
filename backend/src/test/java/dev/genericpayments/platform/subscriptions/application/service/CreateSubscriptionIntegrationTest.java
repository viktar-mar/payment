package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.lang.reflect.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CreateSubscriptionIntegrationTest extends PostgresIntegrationTest {
    static final String ORIGIN="https://subscriptions.example.test";
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerStore customers;
    @Autowired CustomerSessionService sessions;
    @Autowired SubscriptionPolicyPort policies;
    @Autowired SubscriptionStore subscriptions;
    @Autowired SubscriptionOperationStore operations;
    @Autowired TrialEligibilityStore trials;
    @Autowired SubscriptionTransactionPort tx;
    @Autowired SubscriptionProviderRegistry providers;
    @Autowired CreateSubscriptionUseCase create;
    @Autowired SubscriptionCommandTransaction commands;
    @Autowired LoadPaymentAccountPort accounts;
    SiteId site;
    Customer customer;
    SubscriptionActor actor;
    PaymentAccount account;
    PriceId price;
    @BeforeEach void fixture() {
        clearDatabase();site=new SiteId(UUID.randomUUID());UUID accountId=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'sub-create','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",accountId);
        jdbc.update("insert into sites values (?,'sub-create','Subscriptions',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),accountId);
        jdbc.update("insert into site_allowed_origins values (?,?)",site.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site.value());
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        policies.save(new SubscriptionPolicy(site,0,true,3,false,false,false,Set.of()),0);
        var session=sessions.issue(site,customer.id(),ORIGIN);
        actor=new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),ORIGIN));
        account=accounts.findById(new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(accountId)).orElseThrow();
        price=price(0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
    }
    PriceId price(int days,TrialPaymentMethodPolicy policy) {
        UUID product=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Plan','ACTIVE',?,?)",product,site.value(),"product-"+product,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',2000,'EUR','MONTH',?,?,'NONE','PUBLISHED',?,?,?)",id,site.value(),product,"price-"+id,days,policy.name(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        return new PriceId(id);
    }
    CreateSubscriptionUseCase.Command command(String key) {return command(key,price,true);}
    CreateSubscriptionUseCase.Command command(String key,PriceId price,boolean consent) {
        return new CreateSubscriptionUseCase.Command(new SubscriptionCommandContext(actor,site,customer.id(),ORIGIN,key,"request"),price,consent);
    }
    Subscription sub(SubscriptionOperationResult result) {return subscriptions.find(site,result.subscriptionId()).orElseThrow();}
    SubscriptionOperation op(SubscriptionOperationResult result) {return tx.required(()->operations.lock(result.operationId()));}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.status()).isEqualTo(409));
    }
    @Test void providerCallIsOutsideTransactionAfterDurableReservationAndReceiptDoesNotGrantAccess() {
        var calls=new AtomicInteger();var delegate=providers.forAccount(account);
        var observed=(SubscriptionProviderPort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SubscriptionProviderPort.class},(proxy,method,args)->{
            if(method.getName().equals("create")) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var request=(Command)args[0];
                assertThat(jdbc.queryForObject("select subscription_id from subscription_operations where id=?",UUID.class,request.operation().id())).isEqualTo(request.operation().subscriptionId());
                calls.incrementAndGet();
            }
            try {return method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var service=new CreateSubscriptionService(commands,a->observed);
        var result=service.create(command("durable"));
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(sub(result).billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(sub(result).entitledPrice()).isNull();
        assertThat(sub(result).paidThrough()).isNull();
        assertThat(op(result).providerOperationKey()).isEqualTo("subscription:"+account.id().value()+":"+result.operationId()+":CREATE");
        assertThat(sub(result).providerReference()).isEqualTo(op(result).providerReference()).isNotNull();
        assertThat(service.create(command("durable")).operationId()).isEqualTo(result.operationId());
        assertThat(calls).hasValue(1);
    }
    @Test void changedPayloadConflictsButPolicyChangeAndArchivedPriceReplayOriginalOperation() {
        var result=create.create(command("replay"));
        conflict(()->create.create(command("replay",price,false)));
        conflict(()->create.create(command("replay",price(0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL),true)));
        var policy=policies.load(site);
        policies.save(new SubscriptionPolicy(site,policy.version(),true,9,false,false,false,Set.of()),policy.version());
        jdbc.update("update prices set status='ARCHIVED' where id=?",price.value());
        assertThat(create.create(command("replay")).operationId()).isEqualTo(result.operationId());
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(1);
    }
    @Test void unknownTransportFailureSurvivesReplayAndBlocksAnotherKey() {
        price=price(3,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
        var observed=(SubscriptionProviderPort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SubscriptionProviderPort.class},(p,m,a)->{throw new IllegalStateException("transport");});
        var service=new CreateSubscriptionService(commands,a->observed);
        var result=service.create(command("unknown"));
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThat(op(result).providerReference()).isNull();
        assertThat(create.create(command("unknown")).operationId()).isEqualTo(result.operationId());
        conflict(()->create.create(command("other-key")));
        assertThat(sub(result).nonTerminal()).isTrue();
        assertThat(jdbc.queryForObject("select reserved_by_operation from subscription_trial_eligibility",UUID.class)).isEqualTo(result.operationId());
        assertThat(jdbc.queryForObject("select consumed_at from subscription_trial_eligibility",Timestamp.class)).isNull();
    }
    @Test void freshAuthenticationReplaysStableCustomerPrincipalButRevokedSessionCannot() {
        var first=create.create(command("fresh-auth"));
        var original=((SubscriptionActor.Customer)actor).session();
        actor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN));
        assertThat(create.create(command("fresh-auth")).operationId()).isEqualTo(first.operationId());
        sessions.logout(original);
        actor=new SubscriptionActor.Customer(original);
        assertThatThrownBy(()->create.create(command("fresh-auth"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(1);
    }
    @Test void ambientTransactionIsRejectedBeforeProviderOrReservation() {
        assertThatThrownBy(()->tx.required(()->create.create(command("ambient")))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations",Integer.class)).isZero();
    }
    @Test void concurrentSameKeyCallsShareOneOperation() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<UUID>>();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->{start.await();return create.create(command("same")).operationId();}));
            start.countDown();var ids=new HashSet<UUID>();
            for(var task:tasks)ids.add(task.get(30,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from subscriptions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentDifferentKeysCannotBypassTheProductSlotAndDifferentProductsWork() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){String key="different-"+i;tasks.add(pool.submit(()->{
                start.await();try{create.create(command(key));return true;}
                catch(PaymentApplicationException e){assertThat(e.status()).isEqualTo(409);return false;}
            }));}
            start.countDown();int owners=0;for(var task:tasks)if(task.get(30,TimeUnit.SECONDS))owners++;
            assertThat(owners).isEqualTo(1);
        }
        create.create(command("second-product",price(0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL),true));
        assertThat(jdbc.queryForObject("select count(*) from subscriptions",Integer.class)).isEqualTo(2);
    }
    @Test void requiredConsentAndAnonymousOrSubstitutedCustomerNeverCreate() {
        assertThatThrownBy(()->create.create(command("consent",price,false))).isInstanceOf(PaymentApplicationException.class);
        actor=null;assertThatThrownBy(()->create.create(command("anonymous"))).isInstanceOf(PaymentApplicationException.class);
        actor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN));
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        assertThatThrownBy(()->create.create(command("substituted"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations",Integer.class)).isZero();
    }
    @Test void trialsAreReservedUntilAuthoritativeFactsAndActionNeverReplaysSecret() {
        price=price(3,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
        var result=create.create(command("required"));
        assertThat(result.nextAction().kind()).isEqualTo(ActionKind.FAKE_CONFIRM_SETUP);
        assertThat(result.toString()).doesNotContain(result.nextAction().secret());
        assertThat(create.create(command("required")).nextAction()).isNull();
        assertThat(jdbc.queryForObject("select consumed_at from subscription_trial_eligibility",Timestamp.class)).isNull();
        assertThat(sub(result).trial()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations",Integer.class)).isEqualTo(1);
        var optional=create.create(command("optional",price(3,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL),true));
        assertThat(optional.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(sub(optional).trial()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from subscription_trial_eligibility where consumed_at is null and reserved_by_operation is not null",Integer.class)).isEqualTo(2);
    }
    @Test void consumedTrialAllowsPaidRepurchaseWithExactPublishedTermsAndStableReplay() {
        price=price(3,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
        var first=create.create(command("first"));
        tx.required(()->{
            var oldOp=operations.lock(first.operationId());
            trials.consume(first.operationId(),INITIAL_TIME);
            var old=subscriptions.lock(site,first.subscriptionId());subscriptions.save(old.cancel("CONFIRMED",INITIAL_TIME),old.revision());
            operations.save(new SubscriptionOperation(oldOp.id(),site,customer.id(),oldOp.subscriptionId(),oldOp.kind(),SubscriptionOperation.Status.SUCCEEDED,oldOp.authorityScope(),oldOp.keyDigest(),oldOp.fingerprint(),oldOp.providerOperationKey(),oldOp.expectedRevision(),oldOp.policyVersion(),oldOp.providerReference(),null,oldOp.createdAt(),INITIAL_TIME,oldOp.version()+1),oldOp.version());
            return null;
        });
        var second=create.create(command("repurchase"));
        var facts=providers.forAccount(account).retrieve(new Lookup(account,second.subscriptionId(),null,op(second).providerOperationKey()));
        assertThat(facts.trial()).isNull();assertThat(facts.invoices()).hasSize(1);
        assertThat(facts.billingPrice().trialDays()).isEqualTo(3);
        assertThat(sub(second).billingPrice().trialDays()).isEqualTo(3);
        var policy=policies.load(site);
        policies.save(new SubscriptionPolicy(site,policy.version(),true,7,false,false,false,Set.of()),policy.version());
        jdbc.update("update prices set status='ARCHIVED' where id=?",price.value());
        assertThat(create.create(command("first")).operationId()).isEqualTo(first.operationId());
        assertThat(create.create(command("repurchase")).operationId()).isEqualTo(second.operationId());
    }
    @Test void definitiveNoResourceDeclineReleasesSlotButReferencedDeclineAndUnknownRetainIt() {
        price=price(3,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
        var reserved=commands.reserveCreate(command("declined"));
        var result=commands.recordReply(reserved.operation().id(),new Reply(Outcome.DECLINED,null,null));
        assertThat(sub(result).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(sub(result).terminalReason()).isEqualTo("CREATE_REJECTED");
        assertThat(jdbc.queryForObject("select reserved_by_operation from subscription_trial_eligibility",UUID.class)).isNull();
        var next=commands.reserveCreate(command("retry"));
        var rejected=commands.recordReply(next.operation().id(),new Reply(Outcome.DECLINED,"real-resource",null));
        assertThat(sub(rejected).nonTerminal()).isTrue();
        assertThat(jdbc.queryForObject("select reserved_by_operation from subscription_trial_eligibility",UUID.class)).isEqualTo(next.operation().id());
        conflict(()->commands.recordReply(next.operation().id(),new Reply(Outcome.DECLINED,null,null)));
        conflict(()->create.create(command("third")));
    }
    @Test void duplicateDefinitiveRejectionReceiptReturnsStoredFailureWithoutHidingContradiction() {
        var reserved=commands.reserveCreate(command("duplicate-rejection"));
        var result=commands.recordReply(reserved.operation().id(),new Reply(Outcome.DECLINED,null,null));
        assertThat(commands.recordReply(reserved.operation().id(),new Reply(Outcome.DECLINED,null,null))).isEqualTo(result);
        conflict(()->commands.recordReply(reserved.operation().id(),new Reply(Outcome.ACCEPTED,"contradictory-resource",null)));
    }
    @Test void anotherOutstandingTrialReservationIsNotTreatedAsConsumed() {
        price=price(3,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
        var first=commands.reserveCreate(command("pending"));
        tx.required(()->{var old=subscriptions.lock(site,first.subscription().id());subscriptions.save(old.cancel("CONFIRMED",INITIAL_TIME),old.revision());return null;});
        conflict(()->commands.reserveCreate(command("competing")));
        assertThat(jdbc.queryForObject("select reserved_by_operation from subscription_trial_eligibility",UUID.class)).isEqualTo(first.operation().id());
    }
    @Test void definitiveNoResourceDeclineCannotTerminalizeObservedLifecycleState() {
        var reserved=commands.reserveCreate(command("observed-state"));
        tx.required(()->{
            var stored=subscriptions.lock(site,reserved.subscription().id());
            subscriptions.save(stored.pause(INITIAL_TIME),stored.revision());return null;
        });
        conflict(()->commands.recordReply(reserved.operation().id(),new Reply(Outcome.DECLINED,null,null)));
        assertThat(subscriptions.find(site,reserved.subscription().id()).orElseThrow().billingStatus()).isEqualTo(Subscription.Status.PAUSED);
    }
    @Test void malformedFingerprintCannotFallBackToFreshEligibility() {
        var result=create.create(command("malformed"));
        // Immutable columns intentionally forbid tampering in SQL; test the codec directly.
        for(String value:List.of("legacy","v2.trial."+"a".repeat(64),"v1.trial.short")) {
            conflict(()->CreateSubscriptionFingerprint.startsTrial(value));
        }
        assertThat(create.create(command("malformed")).operationId()).isEqualTo(result.operationId());
    }
}
