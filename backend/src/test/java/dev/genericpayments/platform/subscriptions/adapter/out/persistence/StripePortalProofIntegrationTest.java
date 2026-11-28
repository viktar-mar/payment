package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingPortalAdapter;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Hosted PostgreSQL only. No external provider calls; tests exercise configured transactional stores. */
class StripePortalProofIntegrationTest extends Task10StripeProofFixture {
    @Autowired StripePortalConfigurationPort portals;
    @Autowired SubscriptionInteractionTransaction interactions;

    @Test void competingConfigEnsureReusesOneOriginalIntentAndCannotReplaceItsDigest() {
        var scope=scope(local,original.subscription(),RETURN);
        var first=portals.intent(configuration(scope));
        var second=portals.intent(configuration(scope));
        assertThat(second).isEqualTo(first);
        assertThat(count("stripe_portal_configuration_intents")).isEqualTo(1);
        var changed=new Configuration(UUID.randomUUID(),first.account(),first.site(),first.policy(),"b".repeat(64),first.owner(),"portal_config_drift","task10-drift",null,null);
        unavailable(() -> portals.intent(changed));
        assertThat(count("stripe_portal_configuration_intents")).isEqualTo(1);
        assertThat(portals.intent(configuration(scope))).isEqualTo(first);
    }

    @Test void onlyExactTypedAcknowledgedConfigStepCanBindTheOriginalIntent() {
        var scope=scope(local,original.subscription(),RETURN);
        var c=portals.intent(configuration(scope));
        var step=claim(steps.reserve(account,ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,c.id(),c.key(),c.fingerprint()));
        unavailable(() -> portals.bind(c,step.id(),"bpc_Unacknowledged"));
        assertThat(count("stripe_portal_configurations")).isZero();
        var ack=acknowledge(step,"bpc_ExactConfiguration");
        var saved=portals.bind(c,ack.id(),ack.providerReference());
        assertThat(saved.reference()).isEqualTo("bpc_ExactConfiguration");
        assertThat(saved.step()).isEqualTo(ack.id());
        unavailable(() -> portals.bind(c,original.creationStep().id(),original.subscription().providerReference()));
        unavailable(() -> portals.bind(c,ack.id(),"bpc_Different"));
        assertThat(portals.intent(configuration(scope))).isEqualTo(saved);
        assertThat(count("stripe_portal_configurations")).isEqualTo(1);
    }

    @Test void completeMembershipAndEveryRevisionAreCapturedRatherThanOnlySelectedSubscription() {
        var second=paidOriginal(new Owner(site,customer,account,actor,price(0),local.origin()));
        var scope=scope(local,original.subscription(),RETURN);
        assertThat(scope.members()).extracting(Subscription::id).containsExactlyInAnyOrder(original.subscription().id(),second.subscription().id());
        portals.authorize(scope);
        jdbc.update("update subscriptions set revision=revision+1 where id=?",second.subscription().id());
        unavailable(() -> portals.authorize(scope));
        assertThat(sub(original.subscription().id())).isEqualTo(original.subscription());
    }

    @Test void newlyInsertedMembershipInvalidatesAlreadyCapturedScope() {
        var scope=scope(local,original.subscription(),RETURN);
        var second=paidOriginal(new Owner(site,customer,account,actor,price(0),local.origin()));
        assertThat(second.subscription().customerId()).isEqualTo(customer.id());
        unavailable(() -> portals.authorize(scope));
    }

    @Test void policyDriftRefusesDispatchWithNoSessionReceipt() {
        var scope=scope(local,original.subscription(),RETURN);
        var p=policies.load(site);
        policies.save(new SubscriptionPolicy(site,p.version(),true,3,true,false,false,Set.of(RETURN)),p.version());
        unavailable(() -> portals.authorize(scope));
        assertThat(count("stripe_portal_session_receipts")).isZero();
    }

    @Test void foreignCustomerAndForeignReturnCannotCaptureScope() {
        var s=original.subscription();
        var state=reserve(local,s);
        var o=state.operation();
        var wrong=new SubscriptionOperation(o.id(),o.siteId(),foreign.customer().id(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),o.version());
        unavailable(() -> portals.capture(command(state.account(),s,wrong),RETURN));
        unavailable(() -> portals.capture(command(state.account(),s,o),"https://foreign-portal.test/return"));
    }

    @Test void sessionReceiptRequiresOwnAcknowledgedStepAndPublishesOnlyExactSafeProof() {
        var prepared=prepared();
        unavailable(() -> portals.record(prepared.session(),prepared.step().id(),"bps_Unacknowledged",prepared.expiry()));
        assertThat(count("stripe_portal_session_receipts")).isZero();
        var ack=acknowledge(prepared.step(),"bps_ExactSession");
        var saved=portals.record(prepared.session(),ack.id(),ack.providerReference(),prepared.expiry());
        assertThat(saved.reference()).isEqualTo("bps_ExactSession");
        assertThat(saved.step()).isEqualTo(ack.id());
        assertThat(saved.expiresAt()).isEqualTo(prepared.expiry());
        publish(prepared);
        unavailable(() -> requirePublication(prepared,RETURN,prepared.expiry().plusSeconds(1)));
        unavailable(() -> requirePublication(prepared,"https://foreign-portal.test/return",prepared.expiry()));
        assertThat(sub(original.subscription().id())).isEqualTo(original.subscription());
        assertThat(count("payments")).isZero(); assertThat(count("refunds")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
    }

    @Test void publicationRefusesFullMembershipDriftWhileRetainingActualProviderAckAndSafeReceipt() {
        var prepared=prepared();
        acknowledge(prepared.step(),"bps_AlreadyCreated");
        portals.record(prepared.session(),prepared.step().id(),"bps_AlreadyCreated",prepared.expiry());
        paidOriginal(new Owner(site,customer,account,actor,price(0),local.origin()));
        unavailable(() -> publish(prepared));
        assertThat(steps.findById(prepared.step().id())).hasValueSatisfying(s->assertThat(s.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED));
        assertThat(count("stripe_portal_session_receipts")).isEqualTo(1);
        assertThat(count("subscription_portal_sessions")).isZero();
    }

    @Test void failedReceiptWriteRollsBackItsRowButRetainsIndependentlyCommittedProviderAck() {
        var prepared=prepared();
        acknowledge(prepared.step(),"bps_RollbackSession");
        jdbc.execute("create function reject_task10_portal_receipt() returns trigger language plpgsql as $task10$ begin raise exception 'task10 forced failure' using errcode='23514'; end; $task10$");
        jdbc.execute("create trigger reject_task10_portal_receipt before insert on stripe_portal_session_receipts for each row execute function reject_task10_portal_receipt()");
        try {
            assertThatThrownBy(() -> portals.record(prepared.session(),prepared.step().id(),"bps_RollbackSession",prepared.expiry())).isInstanceOf(RuntimeException.class);
            assertThat(count("stripe_portal_session_receipts")).isZero();
            assertThat(steps.findById(prepared.step().id())).hasValueSatisfying(s->assertThat(s.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED));
        } finally {
            jdbc.execute("drop trigger reject_task10_portal_receipt on stripe_portal_session_receipts");
            jdbc.execute("drop function reject_task10_portal_receipt()");
        }
        portals.record(prepared.session(),prepared.step().id(),"bps_RollbackSession",prepared.expiry());
        assertThat(count("stripe_portal_session_receipts")).isEqualTo(1);
    }

    @Test void sqlGroupsSessionWithItsExactConfigAccountOperationAndAcknowledgedStep() {
        var prepared=prepared();
        var row=sessionRow(prepared,"bps_GroupedSession");
        sqlState("23503",()->insert("stripe_portal_session_receipts",row));
        acknowledge(prepared.step(),"bps_GroupedSession");
        reject("stripe_portal_session_receipts",row,"payment_account_id",foreign.account().id().value(),"23503");
        reject("stripe_portal_session_receipts",row,"configuration_id",UUID.randomUUID(),"23503");
        reject("stripe_portal_session_receipts",row,"configuration_reference","bpc_Foreign","23503");
        reject("stripe_portal_session_receipts",row,"operation_id",original.operation().id(),"23503");
        reject("stripe_portal_session_receipts",row,"creation_step",original.creationStep().id(),"23503");
        reject("stripe_portal_session_receipts",row,"step_kind","SUBSCRIPTION_CREATE","23514");
        reject("stripe_portal_session_receipts",row,"session_reference","bpc_WrongObject","23514");
        insert("stripe_portal_session_receipts",row);
        publish(prepared);
    }

    @Test void sqlConfigurationBindingRejectsAnyMixedIntentOrStepTuple() {
        var scope=scope(local,original.subscription(),RETURN);var c=portals.intent(configuration(scope));
        var step=claim(steps.reserve(account,ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,c.id(),c.key(),c.fingerprint()));
        acknowledge(step,"bpc_GroupedConfig");
        var row=new LinkedHashMap<String,Object>();
        row.put("intent_id",c.id());row.put("payment_account_id",account.id().value());row.put("site_id",site.value());
        row.put("policy_version",c.policy());row.put("policy_digest",c.digest());row.put("configuration_reference","bpc_GroupedConfig");row.put("creation_step",step.id());
        reject("stripe_portal_configurations",row,"payment_account_id",foreign.account().id().value(),"23503");
        reject("stripe_portal_configurations",row,"site_id",foreign.site().value(),"23503");
        reject("stripe_portal_configurations",row,"policy_version",c.policy()+1,"23503");
        reject("stripe_portal_configurations",row,"policy_digest","b".repeat(64),"23503");
        reject("stripe_portal_configurations",row,"creation_step",original.creationStep().id(),"23503");
        reject("stripe_portal_configurations",row,"configuration_reference","bps_WrongObject","23514");
        insert("stripe_portal_configurations",row);
        sqlState("23514",()->jdbc.update("update stripe_portal_configurations set configuration_reference='bpc_Replaced' where intent_id=?",c.id()));
    }

    @Test void unknownStepKeepsOriginalIdentityAndStaleOwnerCannotAcknowledgeReplacement() {
        var prepared=prepared();var originalStep=prepared.step();
        var unknown=steps.unknown(originalStep.id(),OWNER,originalStep.version());
        var again=steps.reserve(account,ProviderOperation.Kind.PORTAL_SESSION_CREATE,prepared.session().operation(),originalStep.operationKey(),originalStep.fingerprint());
        assertThat(again).isEqualTo(unknown);
        var reclaimed=steps.claim(unknown.id(),"new-owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(originalStep.id());
        assertThat(reclaimed.operationKey()).isEqualTo(originalStep.operationKey());
        assertThat(reclaimed.firstDispatchedAt()).isEqualTo(originalStep.firstDispatchedAt());
        assertThat(reclaimed.retryBefore()).isEqualTo(originalStep.retryBefore());
        assertThatThrownBy(()->steps.acknowledge(originalStep.id(),OWNER,originalStep.version(),"bps_StaleOwner")).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("stripe_portal_session_receipts")).isZero();
        steps.acknowledge(reclaimed.id(),"new-owner",reclaimed.version(),"bps_NewOwner");
        portals.record(prepared.session(),reclaimed.id(),"bps_NewOwner",prepared.expiry());
        assertThat(count("stripe_portal_session_receipts")).isEqualTo(1);
    }

    @Test void immutableSessionIntentCannotReplaceItsCapturedMemberDescriptor() {
        var prepared=prepared();
        sqlState("23514",()->jdbc.update("update stripe_portal_session_intents set descriptor='{}'::jsonb where operation_id=?",prepared.session().operation()));
        sqlState("23514",()->jdbc.update("update stripe_portal_session_intents set fingerprint='replacement' where operation_id=?",prepared.session().operation()));
        assertThat(portals.session(prepared.session())).isEqualTo(prepared.session());
    }

    @Test void receiptDoesNotPersistNavigationSecretAndExpiresForPublication() {
        var prepared=prepared();
        acknowledge(prepared.step(),"bps_NoNavigationSecret");
        var expired=Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        portals.record(prepared.session(),prepared.step().id(),"bps_NoNavigationSecret",expired);
        unavailable(()->requirePublication(prepared,RETURN,expired));
        String rows=jdbc.queryForObject("select jsonb_agg(to_jsonb(t))::text from stripe_portal_session_intents t",String.class);
        assertThat(rows).doesNotContain("billing.stripe.com","secret=test_","client_secret");
        String receipts=jdbc.queryForObject("select jsonb_agg(to_jsonb(t))::text from stripe_portal_session_receipts t",String.class);
        assertThat(receipts).doesNotContain("billing.stripe.com","secret=test_","client_secret");
    }

    @Test void realPublicationPersistsSafeNavigationReceiptWithoutItsMemoryOnlyUrl() {
        var p=prepared();acknowledge(p.step(),"bps_MemoryOnly");
        portals.record(p.session(),p.step().id(),"bps_MemoryOnly",p.expiry());
        var navigation=new PortalNavigation("https://billing.stripe.com/p/session?secret=test_Task10MemoryOnly",p.expiry(),false);
        var scope=p.session().scope();
        var state=new SubscriptionInteractionTransaction.State(original.subscription(),account,scope.policy(),scope.operation(),true);
        var returned=interactions.portal(context(local,"publish-"+UUID.randomUUID()),state,navigation,RETURN);
        assertThat(returned.navigationUrl()).isEqualTo("https://billing.stripe.com/p/session?secret=test_Task10MemoryOnly");
        assertThat(returned.simulated()).isFalse();
        assertThat(op(scope.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("subscription_portal_sessions")).isEqualTo(1);
        for(String table:List.of("subscription_portal_sessions","stripe_portal_session_intents","stripe_portal_session_receipts","stripe_portal_configuration_intents","stripe_portal_configurations","provider_operations","subscription_operations","subscription_events","outbox_events")) {
            var persisted=jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t))::text,'[]') from "+table+" t",String.class);
            assertThat(persisted).as(table).doesNotContain("test_Task10MemoryOnly","billing.stripe.com/p/session","?secret=");
        }
        assertThat(sub(original.subscription().id())).isEqualTo(original.subscription());
        assertThat(count("payments")).isZero();assertThat(count("refunds")).isZero();
    }

    @Test void concurrentEnsureCallsKeepOneDurableConfigIntentAcrossDifferentPortalOwners() throws Exception {
        var first=scope(local,original.subscription(),RETURN);var second=scope(local,original.subscription(),RETURN);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return portals.intent(configuration(first));});
            var b=pool.submit(()->{start.await();return portals.intent(configuration(second));});
            start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        }
        assertThat(count("stripe_portal_configuration_intents")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(distinct operation_key) from stripe_portal_configuration_intents",Integer.class)).isEqualTo(1);
    }

    @Test void publicationWaitsOnCustomerThenRereadsSiblingRevisionBeforeAuthorizing() throws Exception {
        var sibling=paidOriginal(new Owner(site,customer,account,actor,price(0),local.origin()));
        var p=prepared();acknowledge(p.step(),"bps_LockRace");
        portals.record(p.session(),p.step().id(),"bps_LockRace",p.expiry());
        try(var blocker=jdbc.getDataSource().getConnection();var probe=jdbc.getDataSource().getConnection();var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);probe.setAutoCommit(false);
            lock(blocker,"select id from customers where id=? for update",customer.id().value());
            try(var update=blocker.prepareStatement("update subscriptions set revision=revision+1 where id=?")) {update.setObject(1,sibling.subscription().id());assertThat(update.executeUpdate()).isEqualTo(1);}
            int pid=backendPid(blocker);
            var result=pool.submit(()->publish(p));
            try {
                awaitBlockedQuery(pid,"customers");
                lock(probe,"select id from payment_accounts where id=? for update nowait",account.id().value());
                probe.rollback();assertThat(result.isDone()).isFalse();
            } finally {probe.rollback();blocker.commit();}
            assertThatThrownBy(()->result.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PaymentApplicationException.class);
        }
        assertThat(steps.findById(p.step().id())).hasValueSatisfying(s->assertThat(s.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED));
        assertThat(count("subscription_portal_sessions")).isZero();
    }

    private static void lock(Connection connection,String sql,UUID id)throws SQLException {try(var query=connection.prepareStatement(sql)){query.setObject(1,id);try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();}}}
    private static int backendPid(Connection c)throws SQLException {try(var q=c.createStatement();var r=q.executeQuery("select pg_backend_pid()")){assertThat(r.next()).isTrue();return r.getInt(1);}}
    private void awaitBlockedQuery(int blocker,String table)throws InterruptedException {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        do {var waiting=jdbc.queryForList("select pid from pg_stat_activity where wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and query like ?",Integer.class,blocker,"%"+table+"%");if(!waiting.isEmpty())return;Thread.sleep(10);}while(System.nanoTime()<until);
        throw new AssertionError("Expected actual PostgreSQL lock wait on "+table);
    }

    @Test void configUnknownReusesOnlyItsOriginalIntentKeyAndWindow() {
        var scope=scope(local,original.subscription(),RETURN);var c=portals.intent(configuration(scope));
        var step=claim(steps.reserve(account,ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,c.id(),c.key(),c.fingerprint()));
        var unknown=steps.unknown(step.id(),OWNER,step.version());
        var reused=portals.intent(configuration(scope));
        assertThat(reused).isEqualTo(c);
        var original=steps.reserve(account,ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,reused.id(),reused.key(),reused.fingerprint());
        assertThat(original).isEqualTo(unknown);
        var current=steps.claim(original.id(),"config-replay-owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        assertThat(current.firstDispatchedAt()).isEqualTo(step.firstDispatchedAt());
        assertThat(current.retryBefore()).isEqualTo(step.retryBefore());
        assertThat(current.operationKey()).isEqualTo(c.key());
        steps.acknowledge(current.id(),"config-replay-owner",current.version(),"bpc_OriginalAfterUnknown");
        var recovered=portals.bind(reused,current.id(),"bpc_OriginalAfterUnknown");
        assertThat(recovered.id()).isEqualTo(c.id());
        assertThat(count("stripe_portal_configuration_intents")).isEqualTo(1);
        assertThat(count("stripe_portal_configurations")).isEqualTo(1);
    }

    @Test void accountRotationBlocksPublicationButDoesNotEraseAlreadyReceivedProviderResult() {
        var p=prepared();acknowledge(p.step(),"bps_AccountDrift");
        portals.record(p.session(),p.step().id(),"bps_AccountDrift",p.expiry());
        jdbc.update("update payment_accounts set credentials_ref='rotated-task10-credential-alias' where id=?",account.id().value());
        unavailable(()->publish(p));
        assertThat(steps.findById(p.step().id())).hasValueSatisfying(s->assertThat(s.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED));
        assertThat(count("stripe_portal_session_receipts")).isEqualTo(1);
        assertThat(count("subscription_portal_sessions")).isZero();
    }

    private record Prepared(Session session,ProviderOperation step,Instant expiry) {}
    private Prepared prepared() {
        var scope=scope(local,original.subscription(),RETURN);var c=portals.intent(configuration(scope));
        var step=acknowledge(steps.reserve(account,ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE,c.id(),c.key(),c.fingerprint()),nativeReference("bpc_"));
        c=portals.bind(c,step.id(),step.providerReference());
        var session=portals.session(new Session(scope.operation().id(),c,scope,StripeBillingPortalAdapter.sessionFingerprint(scope,c),null,null,null));
        var dispatched=claim(steps.reserve(account,ProviderOperation.Kind.PORTAL_SESSION_CREATE,session.operation(),scope.operation().providerOperationKey()+":SESSION",session.fingerprint()));
        return new Prepared(session,dispatched,Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS));
    }
    private SubscriptionInteractionTransaction.State reserve(Owner owner,Subscription s) {
        var c=context(owner,"portal-"+UUID.randomUUID());var candidate=interactions.candidate(c,s.id());
        return interactions.reserve(c,s.id(),SubscriptionOperation.Kind.PORTAL,"task10-request-"+UUID.randomUUID(),candidate);
    }
    private Scope scope(Owner owner,Subscription s,String url) {var state=reserve(owner,s);return portals.capture(command(state.account(),s,state.operation()),url);}
    private Command command(PaymentAccount a,Subscription s,SubscriptionOperation o) {return new Command(a,o,s.billingPrice(),null,s.providerReference(),null,null,false,false);}
    private Configuration configuration(Scope s) {var id=UUID.randomUUID();var key="portal_config_"+id;var digest=StripeBillingPortalAdapter.policyDigest(s.policy());return new Configuration(id,s.account().id(),s.policy().siteId(),s.policy().version(),digest,s.operation().id(),key,StripeBillingPortalAdapter.configFingerprint(s,id,s.operation().id(),key,digest),null,null);}
    private void publish(Prepared p) {requirePublication(p,RETURN,p.expiry());}
    private void requirePublication(Prepared p,String url,Instant expiry) {tx.requiresNew(()->{portals.requirePublication(account,original.subscription(),p.session().scope().operation(),url,expiry);return null;});}
    private Map<String,Object> sessionRow(Prepared p,String ref) {var r=new LinkedHashMap<String,Object>();r.put("operation_id",p.session().operation());r.put("payment_account_id",account.id().value());r.put("configuration_id",p.session().configuration().id());r.put("configuration_reference",p.session().configuration().reference());r.put("session_reference",ref);r.put("creation_step",p.step().id());r.put("expires_at",Timestamp.from(p.expiry()));return r;}
    private void insert(String table,Map<String,Object> row) {jdbc.update("insert into "+table+"("+String.join(",",row.keySet())+") values ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    private void reject(String table,Map<String,Object> row,String column,Object value,String state) {var changed=new LinkedHashMap<>(row);changed.put(column,value);sqlState(state,()->insert(table,changed));}
    private static void unavailable(ThrowingCallable action) {assertThatThrownBy(action).isInstanceOf(PaymentApplicationException.class);}

}

/** Shared honest source fixture; actual owner/operation/step stores establish all durable identities. */
abstract class Task10StripeProofFixture extends SubscriptionFactFixture {
    @Autowired protected ProviderOperationStore steps;
    @Autowired protected StripeBillingBindingPort bindings;
    protected static final String OWNER="task10-sql-proof-owner";
    protected static final String RETURN="https://facts.test/billing/return";
    protected Owner local,foreign;
    protected Original original,other;
    protected Instant boundary;

    @BeforeEach void stripeProofFixture() {
        boundary=Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_PortalProofs',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        account=accounts.findById(account.id()).orElseThrow();
        var p=policies.load(site);
        policies.save(new SubscriptionPolicy(site,p.version(),true,3,true,true,false,Set.of(RETURN)),p.version());
        local=new Owner(site,customer,account,actor,price,"https://facts.test");
        foreign=foreignOwner(); original=paidOriginal(local); other=paidOriginal(foreign);
    }
    protected record Owner(SiteId site,Customer customer,PaymentAccount account,SubscriptionActor actor,PriceId price,String origin) {}
    protected record Original(Subscription subscription,SubscriptionOperation operation,ProviderOperation creationStep) {}
    protected Owner foreignOwner() {
        var sid=new SiteId(UUID.randomUUID());var aid=new PaymentAccountId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,?,'STRIPE','SANDBOX','ACTIVE','acct_ForeignPortal','stripe-sandbox-default',now(),0)",aid.value(),"account-"+aid.value());
        jdbc.update("insert into sites values (?,?, 'Foreign Portal',?,'ACTIVE','AUTOMATIC',now(),now(),0)",sid.value(),"site-"+sid.value(),aid.value());
        String origin="https://foreign-portal.test";
        jdbc.update("insert into site_allowed_origins values (?,?)",sid.value(),origin);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",sid.value());
        var c=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),sid,null,null,INITIAL_TIME));
        policies.save(new SubscriptionPolicy(sid,0,true,3,true,true,false,Set.of(origin+"/return")),0);
        var a=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(sid,c.id(),origin).rawToken(),origin));
        var product=UUID.randomUUID();var pid=new PriceId(UUID.randomUUID());
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Foreign plan','ACTIVE',?,?)",product,sid.value(),"p-"+product,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',2000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",pid.value(),sid.value(),product,"p-"+pid.value(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        return new Owner(sid,c,accounts.findById(aid).orElseThrow(),a,pid,origin);
    }
    protected Original paidOriginal(Owner owner) {
        String customerKey="customer:"+owner.account().id().value()+":"+owner.customer().id().value();
        var binding=bindings.findCustomer(owner.account().id(),owner.site(),owner.customer().id());
        if(binding.isEmpty()) {
            var step=steps.reserve(owner.account(),ProviderOperation.Kind.CUSTOMER_CREATE,owner.customer().id().value(),customerKey,"task10-customer-"+owner.customer().id().value());
            var ack=acknowledge(step,nativeReference("cus_"));
            bindings.bindCustomer(owner.account().id(),owner.site(),owner.customer().id(),ack.providerReference(),ack.id());
        }
        var r=commands.reserveCreate(new CreateSubscriptionUseCase.Command(context(owner,"create-"+UUID.randomUUID()),owner.price(),true));
        var step=steps.reserve(owner.account(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,r.operation().id(),r.operation().providerOperationKey(),"task10-create-"+r.operation().id());
        var ack=acknowledge(step,nativeReference("sub_"));
        commands.recordReply(r.operation().id(),new Reply(Outcome.ACCEPTED,ack.providerReference(),null));
        var incomplete=subscriptions.find(owner.site(),r.subscription().id()).orElseThrow();
        // Seed an already paid source period. This does not claim cash mapping or Stripe normalization.
        subscriptions.save(incomplete.confirmPaid(incomplete.billingPrice(),new Subscription.Period(INITIAL_TIME.minusSeconds(86400),boundary),INITIAL_TIME),incomplete.revision());
        finish(r.operation());
        return new Original(subscriptions.find(owner.site(),incomplete.id()).orElseThrow(),op(r.operation().id()),ack);
    }
    protected SubscriptionCommandContext context(Owner o,String key) {return new SubscriptionCommandContext(o.actor(),o.site(),o.customer().id(),o.origin(),key,"task10-sql");}
    protected ProviderOperation claim(ProviderOperation s) {return steps.claim(s.id(),OWNER,Instant.now(),Duration.ofSeconds(60)).orElseThrow();}
    protected ProviderOperation acknowledge(ProviderOperation s,String reference) {var c=s.status()==ProviderOperation.Status.DISPATCHING?s:claim(s);return steps.acknowledge(c.id(),OWNER,c.version(),reference);}
    protected Instant received(ProviderOperation s) {
        var at=Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(at).isAfterOrEqualTo(s.firstDispatchedAt()).isBefore(s.retryBefore()).isBefore(boundary);return at;
    }
    protected void finish(SubscriptionOperation o) {tx.required(()->{var c=operations.lock(o.id());return operations.save(copy(c,SubscriptionOperation.Status.SUCCEEDED),c.version());});}
    protected static SubscriptionOperation copy(SubscriptionOperation o,SubscriptionOperation.Status status) {return new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),o.version()+1);}
    protected Snapshot admissionSnapshot(Subscription s) {
        var cash=new CashObject("pi_FixturePaid",2000,"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME);
        var invoice=new Invoice("in_FixturePaid",BillingRecord.Kind.INITIAL,s.billingPrice(),null,s.currentPeriod(),2000,"EUR",SettlementKind.CASH,List.of(cash));
        return new Snapshot(s.providerReference(),s.billingStatus(),s.billingPrice(),s.currentPeriod(),null,s.cancellation()==null?null:s.cancellation().effectiveAt(),List.of(invoice),null,true,0,INITIAL_TIME);
    }
    protected static String nativeReference(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
    protected static void evidenceConflict(ThrowingCallable action) {assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));}
    protected static void sqlState(String expected,ThrowingCallable action) {assertThatThrownBy(action).isInstanceOfSatisfying(DataIntegrityViolationException.class,e->assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,s->assertThat(s.getSQLState()).isEqualTo(expected)));}
}
