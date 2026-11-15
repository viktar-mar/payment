package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.stripe.net.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.support.*;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult.AuthenticatedCustomer;
import dev.genericpayments.platform.customers.application.model.CustomerSessionCreation;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.application.service.CustomerMailDeliveryService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.provider.adapter.out.persistence.OwnedFailedGraceBillingTime;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

/** Hosted-only genuine constructor/transaction regression; only provider HTTP replies are inert. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
@Import({OwnedBillingFailedRenewalRuntime.BillingTimeConfiguration.class,OwnedBillingFailedRenewalScenarioIntegrationTest.TimeSelection.class})
class OwnedBillingFailedRenewalScenarioIntegrationTest extends OwnedBillingFirstPaymentScenarioFixture {
    private final Deque<String> boundaryReplies=new ArrayDeque<>();
    private boolean boundaryStage;
    private long end,next,after,amount;
    private DebtWire debt;
    private JsonObject setup;
    private CustomerSessionCreation originalSession;

    @Override protected OwnedBillingFirstPaymentRuntime.Operations createScenario(OwnedBillingFirstPaymentRuntime.Admission admission) {
        return new OwnedBillingFailedRenewalSandboxScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(120));
    }

    @Override protected Object runtimeBean(Class<?> type) {
        if(type==ApplySubscriptionSnapshotUseCase.class){
            var actual=application.getBean(ApplySubscriptionSnapshotUseCase.class);
            return (ApplySubscriptionSnapshotUseCase)command->{refreshObservationClock();actual.apply(command);};
        }
        return super.runtimeBean(type);
    }

    @Override protected StripeResponse renewalResponse(StripeRequest request) {
        if(!boundaryStage)return null;
        assertThat(transaction.active()).as("No caller transaction may span genuine SDK work").isFalse();
        if(request.method().name().equals("POST")&&request.url().getPath().equals("/v1/setup_intents"))
            return new StripeResponse(200,HttpHeaders.of(Map.of()),setupReply(request).toString());
        assertThat(boundaryReplies).as("Every SDK reply is planned in memory").isNotEmpty();
        return new StripeResponse(200,HttpHeaders.of(Map.of()),boundaryReplies.removeFirst());
    }

    @Test void actualComponentsPreserveTheManagedRenewalStoreAcrossInitialSettlementAndBoundary() {
        // This is the actual factory constructor, with real application stores/EMF/proofs.
        // Assertions follow the actual scenario path so an unproxied store cannot be hidden
        // by testing only the managed bean in isolation.
        var components=new OwnedBillingFailedRenewalComponents(this::runtimeBean,Clock.systemUTC());
        prepare();noMoney();scenario.confirm(confirmRequest());noMoney();paidGraph();
        var initial=observe();assertThat(initial.get("phase").getAsString()).isEqualTo("SETTLED_LOCAL");
        long end=Instant.parse(initial.get("paid_through").getAsString()).getEpochSecond();
        var moneyBefore=financialRows();int before=requests.size();
        queueBoundary(end);refreshObservationClock();
        assertThat(transaction.active()).isFalse();
        var result=((OwnedBillingFailedRenewalSandboxScenario)scenario).boundary();
        assertThat(transaction.active()).as("Short renewal transactions have returned before caller continuation").isFalse();
        assertThat(result.get("phase").getAsString()).isEqualTo("RENEWAL_BOUNDARY");
        assertThat(result.get("boundary_time").getAsString()).isEqualTo(Instant.ofEpochSecond(end).toString());
        assertThat(boundaryReplies).isEmpty();
        assertThat(components.renewals).as("Actual Components must preserve the original transaction-enabled store")
            .isSameAs(application.getBean(OwnedFirstRenewalStore.class));
        assertThat(financialRows()).as("BOUNDARY creates no renewal bill, cash, event or callback").isEqualTo(moneyBefore);
        assertThat(count("stripe_billing_first_renewals")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_steps")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_acks")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_ready")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_boundary")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(count("stripe_billing_failed_renewals")).isZero();
        assertThat(jdbc.queryForList("select target from stripe_billing_first_renewal_steps",Long.class)).containsExactly(end);
        assertThat(requests.subList(before,requests.size()).stream().filter(r->r.method().name().equals("POST")))
            .singleElement().satisfies(post->{
                assertThat(post.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned/advance");
                assertThat(post.params()).containsOnlyKeys("frozen_time").containsEntry("frozen_time",end);
            });
        // Scenario cleanup exercises the same store's independently committed stop.
        scenario.abort();
        assertThat(jdbc.queryForObject("select stopped_at is not null from stripe_billing_first_renewals",Boolean.class)).isTrue();
        assertThat(transaction.active()).isFalse();
    }

    private Map<String,List<Map<String,Object>>> financialRows(){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("payments","payment_events","subscription_billing_records","subscription_events","outbox_events","outbox_deliveries","outbox_retry_requests"))
            result.put(table,jdbc.queryForList("select row_to_json(e)::text as row from "+table+" e order by row_to_json(e)::text"));
        return result;
    }

    private String clock(long at,String status){
        var value=JsonParser.parseString(clockJson(owner)).getAsJsonObject();value.addProperty("frozen_time",at);value.addProperty("status",status);return value.toString();
    }

    @Override protected CustomerId createFixtureCustomer() {
        refreshObservationClock();
        // OTP issuance computes the session's scopes from the enabled subscription policy.
        super.prepareFixturePolicy();
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?, 'EMAIL_OTP')",site.value());
        application.getBean(IdentityProviderConfigurationStore.class).save(IdentityProviderConfiguration.create(site,
            "email-provider",new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        String siteKey=sites.findById(site).orElseThrow().key().value();
        String origin="https://shop.test";
        var challenge=application.getBean(RequestEmailOtpUseCase.class).request(new RequestEmailOtpUseCase.Command(
            siteKey,origin,AuthenticationIntent.AUTHENTICATE,"owner@example.test","203.0.113.8"));
        var mailbox=new ArrayList<CustomerMailDeliveryPort.Message>();
        var mail=new CustomerMailDeliveryService(application.getBean(CustomerMailOutboxPort.class),mailbox::add,
            application.getBean(EmailOtpChallengeStore.class),encryption,application.getBean(EmailOtpPolicyPort.class),Clock.systemUTC());
        assertThat(mail.deliverReady("failed-renewal-otp")).isOne();
        assertThat(mailbox).hasSize(1);
        var code=java.util.regex.Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(mailbox.getFirst().body());
        assertThat(code.find()).isTrue();
        var result=(AuthenticatedCustomer)application.getBean(VerifyEmailOtpUseCase.class).verify(
            new VerifyEmailOtpUseCase.Command(challenge.challengeId(),siteKey,origin,code.group()));
        originalSession=result.session();
        assertThat(originalSession.scopes()).contains(CustomerSessionScope.SUBSCRIPTION_MANAGE);
        return result.customerId();
    }
    @Override protected void prepareFixturePolicy() {
        assertThat(policies.load(site).graceDays()).isEqualTo(3);
    }
    @Override protected CustomerSessionCreation fixtureSession() {return originalSession;}

    @TestConfiguration(proxyBeanMethods=false)
    static class TimeSelection {
        @Bean static BeanFactoryPostProcessor selectRuntimeTime() {
            return factory->factory.getBeanDefinition("ownedSubscriptionTime").setPrimary(false);
        }
    }

    private OwnedBillingFailedRenewalSandboxScenario combined(){return (OwnedBillingFailedRenewalSandboxScenario)scenario;}

    private JsonObject stage(String route,String phase) throws Exception {
        refreshObservationClock();
        var exchange=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST","/failed-renewal/"+route,"{}",true);
        new OwnedBillingFailedRenewalRuntime.Handler(combined(),"q".repeat(43)).handle(exchange);
        assertThat(exchange.code).as("fixed route %s, checkpoint %s",route,combined().failureStage()).isEqualTo(200);
        assertThat(exchange.responses.getFirst("Cache-Control")).isEqualTo("no-store");
        var result=JsonParser.parseString(exchange.output.toString(java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(result.get("phase").getAsString()).isEqualTo(phase);
        assertThat(boundaryReplies).as("complete provider replies for %s",route).isEmpty();
        return result;
    }

    private void queueAdvance(long previous,long target) {
        Collections.addAll(boundaryReplies,clock(previous,"ready"),clock(target,"advancing"),clock(target,"ready"));
    }
    private String customerBody(){return customerJson("clock_owned");}
    private void queueGraph(long target,boolean allocation) {
        boundaryReplies.addAll(debt.bodies(clock(target,"ready"),customerBody(),allocation));
    }
    private void queueBoundary(long end){
        boundaryStage=true;
        this.end=end;
        amount=graph.price().get("unit_amount").getAsLong();
        next=end+31*86400L;
        debt=new DebtWire(graph,end,next,amount,false);
        debt.renewalInvoice.add("ending_balance",JsonNull.INSTANCE);
        queueAdvance(FROZEN,end);
        queueGraph(end,false);
    }
    private JsonObject settledBoundary() throws Exception {
        publishCatalogOperations();
        callbacks.configure(site.value(),"http://callback-receiver:8080/callback",true);
        prepare();noMoney();
        assertThat(prepared.getAsJsonObject("session").get("protocol").getAsString()).isEqualTo("SERVER_CONFIRM_V2");
        scenario.confirm(confirmRequest());noMoney();paidGraph();
        var initial=observe();
        deliverSigned(2);
        verifyOwnedInitialSql();
        queueBoundary(Instant.parse(initial.get("paid_through").getAsString()).getEpochSecond());
        return stage("boundary","RENEWAL_BOUNDARY");
    }
    private void armBookend(String defaultMethod) {
        var original=graph.remote.deepCopy();
        original.addProperty("latest_invoice",debt.renewalInvoice.get("id").getAsString());
        original.addProperty("default_payment_method",defaultMethod);
        Collections.addAll(boundaryReplies,clock(end,"ready"),customerBody(),original.toString(),debt.renewalInvoice.toString());
    }
    private JsonObject card(String id) {
        var card=new JsonObject();
        card.addProperty("id",id);card.addProperty("object","payment_method");card.addProperty("livemode",false);
        card.addProperty("customer",graph.remote.get("customer").getAsString());card.addProperty("type","card");
        card.add("card",JsonParser.parseString("{\"last4\":\"4242\"}"));
        return card;
    }
    private void queueArm() {
        armBookend("pm_original");
        boundaryReplies.add(card("pm_decline").toString());
        armBookend("pm_original");
        armBookend("pm_original");
        var updated=graph.remote.deepCopy();
        updated.addProperty("latest_invoice",debt.renewalInvoice.get("id").getAsString());
        updated.addProperty("default_payment_method","pm_decline");
        boundaryReplies.add(updated.toString());
        armBookend("pm_decline");
    }
    private JsonObject failure() throws Exception {
        var before=financialRows();
        queueArm();stage("arm-decline","DECLINE_ARMED");
        assertThat(financialRows()).isEqualTo(before);
        assertThat(count("stripe_billing_failed_renewals")).isZero();
        graph.remote.addProperty("default_payment_method","pm_decline");
        debt=new DebtWire(graph,end,next,amount,true);
        queueAdvance(end,end+3600);queueGraph(end+3600,true);
        var failure=stage("failed","FAILED_RENEWAL");
        deliverSigned(1);
        assertThat(count("payments")).isOne();
        assertThat(count("payment_events")).isOne();
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        return failure;
    }

    private void graceAndMethod(JsonObject failure) throws Exception {
        long deadline=Instant.parse(failure.get("grace_deadline").getAsString()).getEpochSecond();
        long previous=end+3600;
        var routes=List.of("grace-last-second","grace-at","grace-after");
        var phases=List.of("GRACE_LAST_SECOND","GRACE_AT","GRACE_AFTER");
        for(int i=0;i<3;i++){
            long target=deadline-1+i;
            queueAdvance(previous,target);
            var reply=stage(routes.get(i),phases.get(i));
            assertThat(reply.get("access_allowed").getAsBoolean()).isEqualTo(i==0);
            exactReads(target,i==0?"GRACE":"EXPIRED",i==0?deadline:null);
            previous=target;
        }
        after=previous;
        var before=financialRows();
        stage("method/prepare","METHOD_PREPARED");
        assertThat(jdbc.queryForObject("select browser_session_id from stripe_billing_failed_method_reservations",UUID.class))
            .isEqualTo(originalSession.sessionId().value());
        var pending=setup.deepCopy();pending.addProperty("client_secret","seti_saved_secret_INERT");boundaryReplies.add(pending.toString());
        var session=stage("method/select","METHOD_SELECTED").getAsJsonObject("session");
        assertThat(session.get("protocol").getAsString()).isEqualTo("BROWSER_CONFIRM_V1");
        assertThat(session.get("flow").getAsString()).isEqualTo("SETUP");
        assertThat(stage("method/confirm","METHOD_CONSUMED").get("browser_confirm_allowed").getAsBoolean()).isTrue();
        // Hosted SDK wire after the customer's single permitted confirmation. Binding must independently retrieve both objects.
        setup.addProperty("status","succeeded");setup.addProperty("payment_method","pm_saved");
        boundaryReplies.add(setup.toString());boundaryReplies.add(card("pm_saved").toString());
        graph.remote.addProperty("default_payment_method","pm_saved");boundaryReplies.add(graph.remote.toString());
        methodGraph();
        var method=stage("method/apply","METHOD_READY");
        assertThat(method.get("recovery_bill_id")).isEqualTo(failure.get("failed_bill_id"));
        assertThat(method.get("recovery_payment_intent_id")).isEqualTo(failure.get("failed_payment_intent_id"));
        assertThat(financialRows()).isEqualTo(before);
        assertThat(count("stripe_billing_failed_method_completions")).isOne();
        missingReads();
    }

    private void methodGraph() {
        queueGraph(after,true);
        var endClock=boundaryReplies.removeLast();
        Collections.addAll(boundaryReplies,setup.toString(),card("pm_saved").toString(),endClock);
    }
    private void acknowledgeRecovery() throws Exception {
        var before=financialRows();
        methodGraph();
        var pi=debt.renewalPi.deepCopy();
        pi.addProperty("amount_received",amount);pi.addProperty("amount_capturable",0);
        pi.addProperty("status","succeeded");pi.addProperty("payment_method","pm_saved");
        pi.add("last_payment_error",JsonNull.INSTANCE);
        boundaryReplies.add(pi.toString());
        var result=stage("recovery/confirm","RECOVERY_ACKNOWLEDGED");
        assertThat(result.get("recovery_confirm_status").getAsString()).isEqualTo("ACKNOWLEDGED");
        assertThat(financialRows()).isEqualTo(before);
        assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isOne();
        assertThat(count("stripe_billing_failed_method_publications")).isZero();
        missingReads();
    }
    private void queuePaid() {
        graph.remote.addProperty("status","active");
        debt.renewalInvoice.addProperty("status","paid");debt.renewalInvoice.addProperty("amount_paid",amount);
        debt.renewalInvoice.addProperty("amount_remaining",0);debt.renewalInvoice.addProperty("ending_balance",0);
        debt.renewalAllocation.addProperty("status","paid");debt.renewalAllocation.addProperty("amount_paid",amount);
        debt.renewalAllocation.getAsJsonObject("status_transitions").addProperty("paid_at",after);
        debt.renewalPi.addProperty("status","succeeded");debt.renewalPi.addProperty("amount_received",amount);
        debt.renewalPi.remove("last_payment_error");
        debt.renewalCharge.addProperty("status","succeeded");debt.renewalCharge.addProperty("paid",true);
        debt.renewalCharge.addProperty("captured",true);debt.renewalCharge.addProperty("amount_captured",amount);
        debt.renewalCharge.remove("failure_code");debt.renewalCharge.remove("outcome");
        queueGraph(after,true);
    }

    private JsonObject setupReply(StripeRequest request) {
        var operation=jdbc.queryForMap("select resource_id,first_dispatched_at from provider_operations where kind='SETUP_INTENT_CREATE' and status='DISPATCHING'");
        var result=new JsonObject();result.addProperty("id","seti_saved");result.addProperty("object","setup_intent");
        result.addProperty("livemode",false);result.addProperty("customer",request.params().get("customer").toString());
        result.addProperty("status","requires_payment_method");result.addProperty("usage","off_session");
        result.addProperty("created",((java.sql.Timestamp)operation.get("first_dispatched_at")).toInstant().getEpochSecond());
        result.add("payment_method_types",JsonParser.parseString("[\"card\"]"));
        var metadata=new Gson().toJsonTree(request.params().get("metadata")).getAsJsonObject();
        assertThat(metadata.get("operation_id").getAsString()).isEqualTo(operation.get("resource_id").toString());
        result.add("metadata",metadata);setup=result;
        return result;
    }

    private Map<String,List<String>> retainedState() {
        var state=new TreeMap<String,List<String>>();
        var tables=jdbc.queryForList("""
            select tablename from pg_tables where schemaname=current_schema()
            and (tablename like 'stripe_%' or tablename like 'subscription%' or tablename like 'payment%'
            or tablename like 'outbox_%' or tablename in ('provider_operations','provider_inbox')) order by tablename
            """,String.class);
        for(var table:tables){
            assertThat(table).matches("[a-z_0-9]+");
            state.put(table,jdbc.queryForList("select row_to_json(t)::text as body from "+table+" t order by body",String.class));
        }
        return state;
    }
    private void exactReads(long target,String reason,Long until) {
        var snapshot=retainedState();
        var selector=application.getBean(OwnedFailedGraceBillingTime.class);
        for(int i=0;i<2;i++) transaction.required(()->{
            var xid=jdbc.queryForObject("select pg_current_xact_id()::text",String.class);
            var holder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            var view=queries.get(createCommand.context().actor(),site,id(prepared,"subscription_id"));
            assertThat(selector.sampledConnectionHolder).isSameAs(holder);
            assertThat(selector.sampledTransaction).isEqualTo(xid);
            var access=queries.access(createCommand.context().actor(),site,customer,view.subscription().productId());
            assertThat(selector.sampledConnectionHolder).isSameAs(holder);
            assertThat(selector.sampledTransaction).isEqualTo(xid);
            assertThat(access).isEqualTo(view.access());
            assertThat(access.reason().name()).isEqualTo(reason);
            assertThat(access.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(target));
            assertThat(access.validUntil()).isEqualTo(until==null?null:Instant.ofEpochSecond(until));
            return null;
        });
        assertThat(retainedState().equals(snapshot)).as("All receipt, business, journal, outbox, delivery and retry rows survived repeated reads").isTrue();
    }
    private void missingReads(){
        var snapshot=retainedState();
        var sub=subscriptions.find(site,id(prepared,"subscription_id")).orElseThrow();
        assertThatThrownBy(()->queries.get(createCommand.context().actor(),site,sub.id())).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThatThrownBy(()->queries.access(createCommand.context().actor(),site,customer,sub.productId())).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThat(retainedState().equals(snapshot)).isTrue();
    }
    private static UUID id(JsonObject row,String key){return UUID.fromString(row.get(key).getAsString());}
    @Test void originalFailureGraceBrowserMethodAndRecoveryProduceTheFiveSignedEventsAndRejectAQueuedRetry() throws Exception {
        var boundary=settledBoundary();
        var originalPayment=jdbc.queryForMap("select * from payments where id=?",id(boundary,"initial_payment_id"));
        var originalBill=jdbc.queryForMap("select * from subscription_billing_records where id=?",id(boundary,"initial_bill_id"));
        var failed=failure();
        graceAndMethod(failed);
        acknowledgeRecovery();
        queuePaid();
        var paid=stage("recovery/observe","RECOVERED_SETTLED_LOCAL");
        assertThat(paid.get("recovery_publication_bill_id")).isEqualTo(failed.get("failed_bill_id"));
        assertThat(jdbc.queryForMap("select * from payments where id=?",id(boundary,"initial_payment_id"))).isEqualTo(originalPayment);
        assertThat(jdbc.queryForMap("select * from subscription_billing_records where id=?",id(boundary,"initial_bill_id"))).isEqualTo(originalBill);
        assertThat(jdbc.queryForList("select amount_minor from payments order by id",Long.class)).containsExactly(2000L,2000L);
        assertThat(jdbc.queryForList("select provider_payment_reference from payments",String.class)).containsExactlyInAnyOrder(
            boundary.get("initial_payment_intent_id").getAsString(),failed.get("failed_payment_intent_id").getAsString());
        assertThat(count("customer_sessions")).isOne();
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        verifyFixedMutations(paid);

        var pending=proofRow(paid);
        var claims=outbox.claimAutomatic("failed-renewal-composition",10,Instant.now(),Duration.ofSeconds(30));
        assertThat(claims).hasSize(2).allSatisfy(c->{
            assertThat(c.mode()).isEqualTo(OutboxPort.Mode.AUTOMATIC);
            assertThat(c.attempt()).isOne();
        });
        // Shipped SQL treats the outstanding first automatic delivery as pending before and after claim.
        assertThat(callbackMarkers(pending)).containsExactlyInAnyOrder("true","true","true","false","false");
        assertThat(callbackMarkers(proofRow(paid))).containsExactlyInAnyOrder("true","true","true","false","false");
        deliverSigned(claims);
        assertThat(delivered).hasSize(5);
        exactReads(after,"PAID",Math.min(after+300,next));
        var good=proofRow(paid);
        assertThat(callbackMarkers(good)).containsOnly("true").hasSize(5);
        var first=claims.getFirst().eventId();
        var history=jdbc.queryForList("select * from outbox_deliveries order by id");
        var eventsBefore=jdbc.queryForList("select * from outbox_events order by id");
        assertThat(jdbc.queryForMap("select status,attempt_count from outbox_events where id=?",first))
            .containsEntry("status","DELIVERED").containsEntry("attempt_count",1);
        assertThat(jdbc.queryForList("select * from outbox_deliveries where outbox_event_id=?",first)).singleElement().satisfies(d->
            assertThat(d).containsEntry("delivery_mode","AUTOMATIC").containsEntry("attempt_number",1)
                .containsEntry("status","SUCCEEDED").containsEntry("http_status",204));
        int calls=requests.size();
        var retry=outbox.enqueue(first,OutboxPort.Mode.SNAPSHOT_RETRY,Instant.now());
        assertThat(outbox.retry(retry.id())).isEqualTo(retry);
        assertThat(retry.status()).isEqualTo("PENDING");
        var rejected=proofRow(paid);
        assertThat(callbackMarkers(rejected)).containsExactlyInAnyOrder("true","true","true","true","null");
        assertThat(jdbc.queryForList("select * from outbox_events order by id")).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForList("select * from outbox_deliveries order by id")).isEqualTo(history);
        verifyActualConsumer(paid,good,rejected,first);
        assertThat(count("payments")).isEqualTo(2);
        assertThat(requests).hasSize(calls);
    }

    @Test void originalObserverRollsBackCashAndJournalAndAloneStopsItsFailedPublication() throws Exception {
        settledBoundary();var failed=failure();graceAndMethod(failed);acknowledgeRecovery();
        var before=financialRows();
        var ack=jdbc.queryForMap("select * from stripe_billing_failed_recovery_confirm_acks");
        rejectJournal(dev.genericpayments.platform.payments.domain.PaymentEventType.PAYMENT_SUCCEEDED);
        queuePaid();long writes=posts();
        assertThatThrownBy(combined()::observeRecovery).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(boundaryReplies).as("entire independent recovered graph was read before rollback").isEmpty();
        assertThat(count("stripe_billing_failed_method_publications")).isZero();
        assertThat(jdbc.queryForMap("select * from stripe_billing_failed_recovery_confirm_acks")).isEqualTo(ack);
        assertThat(financialRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records where id=?",String.class,id(failed,"failed_bill_id"))).isEqualTo("OPEN");
        assertThat(count("stripe_billing_failed_renewal_stops")).isOne();
        assertThat(count("stripe_billing_failed_grace_successor_terminals")).isOne();
        assertThat(jdbc.queryForObject("""
            select count(*) from stripe_billing_failed_grace_successor_terminals t
            join stripe_billing_failed_method_reads q on (q.id,q.reservation_id)=(t.origin_id,t.reservation_id)
            where t.origin='RECOVERED_READ' and q.phase='RECOVERED' and q.successor_owner_digest=t.successor_owner_digest
            """,Integer.class)).isOne();
        var stopped=retainedState();
        combined().abort();
        assertThatThrownBy(combined()::confirmRecovery).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThatThrownBy(combined()::observeRecovery).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(retainedState().equals(stopped)).isTrue();
        assertThat(posts()).isEqualTo(writes);
    }

    @ParameterizedTest @ValueSource(strings={"before-attach","between-writes","higher-prepared","duplicate","expired-session"})
    void armCannotContinueAfterTheOriginalBoundaryLosesAuthority(String fault) throws Exception {
        settledBoundary();
        var before=financialRows();long writes=posts();
        long expected=0;
        if(fault.equals("higher-prepared")){
            var c=new OwnedBillingFailedRenewalComponents(this::runtimeBean,Clock.systemUTC());
            var admission=c.admit(owned.requireReceipt(billing.id(),id(prepared,"subscription_id")),"a".repeat(40));
            c.renewals.prepare(admission,OwnedFirstRenewal.Step.FINALIZATION,c.renewals.boundary(admission),Instant.now());
        }else if(fault.equals("expired-session")){
            // Operational time is independently checked by the original session resolver.
            TestClock.set(originalSession.expiresAt().plusSeconds(1));
        }else if(fault.equals("duplicate")){
            queueArm();stage("arm-decline","DECLINE_ARMED");expected=2;
        }else{
            if(fault.equals("between-writes")){
                armBookend("pm_original");boundaryReplies.add(card("pm_decline").toString());expected=1;
            }
            boundaryReplies.add(clock(end+1,"ready"));
        }
        assertThatThrownBy(combined()::armDecline).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThatThrownBy(combined()::failedRenewal).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(posts()).isEqualTo(writes+expected);
        assertThat(combined().status().get("phase").getAsString()).isEqualTo("STOPPED");
        assertThat(count("stripe_billing_failed_renewals")).isZero();
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(financialRows()).isEqualTo(before);
    }

    @Test void wrongSourceStopsBeforeCreatingAnyRemoteResource(){
        prepareRequest.addProperty("source_sha","b".repeat(40));
        assertThatThrownBy(this::prepare).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThatThrownBy(combined()::boundary).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(requests).isEmpty();noMoney();
    }

    @Test void aPaidInsteadOfFailedOriginalInvoiceDoesNotFallBackToTheFirstRenewalPublisher() throws Exception {
        settledBoundary();queueArm();stage("arm-decline","DECLINE_ARMED");
        var before=financialRows();
        debt=new DebtWire(graph,end,next,amount,true);
        queueAdvance(end,end+3600);after=end+3600;queuePaid();
        assertThatThrownBy(combined()::failedRenewal).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThatThrownBy(combined()::graceLastSecond).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(count("stripe_billing_failed_renewal_failures")).isZero();
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(financialRows()).isEqualTo(before);
    }

    private void verifyFixedMutations(JsonObject paid) {
        var writes=requests.stream().filter(r->r.method().name().equals("POST")).toList();
        var sub="/v1/subscriptions/"+graph.remote.get("id").getAsString();
        var advance="/v1/test_helpers/test_clocks/clock_owned/advance";
        assertThat(writes.stream().map(r->r.url().getPath())).containsExactly(
            "/v1/test_helpers/test_clocks","/v1/customers","/v1/subscriptions","/v1/payment_intents/"+paid.get("initial_payment_intent_id").getAsString()+"/confirm",
            advance,"/v1/payment_methods/pm_card_chargeCustomerFail/attach",sub,advance,advance,advance,advance,"/v1/setup_intents",sub,
            "/v1/payment_intents/"+paid.get("failed_payment_intent_id").getAsString()+"/confirm");
        assertThat(writes.stream().filter(r->r.url().getPath().equals(advance)).map(r->Long.valueOf(r.params().get("frozen_time").toString())))
            .containsExactly(end,end+3600,after-2,after-1,after);
        assertThat(writes.get(5).params()).containsOnlyKeys("customer").containsEntry("customer",graph.remote.get("customer").getAsString());
        assertThat(writes.get(6).params()).containsOnlyKeys("default_payment_method","proration_behavior").containsEntry("proration_behavior","none").containsEntry("default_payment_method","pm_decline");
        assertThat(writes.get(12).params()).containsOnlyKeys("default_payment_method").containsEntry("default_payment_method","pm_saved");
        assertThat(writes.getLast().params()).containsOnlyKeys("payment_method","off_session","error_on_requires_action")
            .containsEntry("payment_method","pm_saved").containsEntry("off_session",true).containsEntry("error_on_requires_action",true);
    }
    private List<String> callbackMarkers(JsonObject row) {
        return row.getAsJsonArray("callbacks").asList().stream().map(e->e.getAsJsonObject().get("delivered").toString()).toList();
    }
    private UUID product(){return jdbc.queryForObject("select product_id from prices where id=?",UUID.class,createCommand.priceId().value());}
    private JsonObject proofRow(JsonObject paid) throws Exception {
        var sql=java.nio.file.Files.readString(java.nio.file.Path.of("../infrastructure/stripe-billing-failed-renewal-proof.sql")).replaceAll("\\$[0-9]+","?");
        var sha=java.security.MessageDigest.getInstance("SHA-256");
        Object[] args={billing.id().value(),site.value(),customer.value(),id(prepared,"subscription_id"),id(prepared,"operation_id"),
            id(prepared.getAsJsonObject("session"),"attempt_id"),product(),createCommand.priceId().value(),
            HexFormat.of().formatHex(sha.digest("original-create".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            HexFormat.of().formatHex(sha.digest("sdk-generated-pay-key".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            "https://shop.test",run,"a".repeat(40),FROZEN,id(paid,"renewal_admission_id")};
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement(sql)){
                statement.setQueryTimeout(5);
                for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
                try(var rows=statement.executeQuery()){
                    assertThat(rows.next()).isTrue();var row=new JsonObject();
                    for(var key:List.of("owner_run","source_sha","frozen_time"))row.addProperty(key,rows.getString(key));
                    for(var key:List.of("lineage","proof","callbacks"))row.add(key,JsonParser.parseString(rows.getString(key)));
                    assertThat(rows.next()).isFalse();return row;
                }
            }finally{connection.rollback();}
        }
    }
    private void verifyActualConsumer(JsonObject paid,JsonObject row,JsonObject rejected,UUID retryEvent) throws Exception {
        var scope=new JsonObject();
        scope.addProperty("runId",run.toString());scope.addProperty("expectedSource","a".repeat(40));
        scope.addProperty("accountId",billing.id().value().toString());scope.addProperty("siteId",site.value().toString());
        scope.addProperty("customerId",customer.value().toString());scope.addProperty("productId",product().toString());
        scope.addProperty("priceId",createCommand.priceId().value().toString());
        scope.add("subscriptionId",prepared.get("subscription_id"));scope.add("operationId",prepared.get("operation_id"));
        var config=new JsonObject();config.add("runId",scope.get("runId"));config.add("expectedSource",scope.get("expectedSource"));config.addProperty("frozenTime",FROZEN);
        var input=new JsonObject();input.add("scope",scope);input.add("configuration",config);input.add("settlement",paid);input.add("row",row);
        input.add("queuedRetryRow",rejected);input.addProperty("retryEvent",retryEvent.toString());
        var signed=new JsonArray();delivered.forEach(value->signed.add(JsonParser.parseString(value)));input.add("delivered",signed);
        var builder=new ProcessBuilder("node","../infrastructure/fixtures/stripe-billing-failed-renewal-row-consumer.mjs").redirectError(ProcessBuilder.Redirect.DISCARD);
        var path=Objects.requireNonNull(System.getenv("PATH"));builder.environment().clear();builder.environment().put("PATH",path);
        builder.environment().put("LANG","C");builder.environment().put("LC_ALL","C");
        OwnedBillingFirstRenewalEvidenceConsumer.verify(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),builder::start,Duration.ofSeconds(10));
    }
}
