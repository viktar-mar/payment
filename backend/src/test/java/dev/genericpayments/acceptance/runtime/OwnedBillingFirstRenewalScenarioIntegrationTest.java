package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.stripe.net.*;
import com.stripe.exception.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.support.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort;
import dev.genericpayments.platform.webhooks.application.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: genuine original confirmation, both durable cash facts and signed callback delivery. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
@Import(StripeBillingFirstRenewalCompositionFixture.RenewalTimeConfiguration.class)
class OwnedBillingFirstRenewalScenarioIntegrationTest extends OwnedBillingFirstPaymentScenarioFixture {
    private final Deque<String> renewalReplies=new ArrayDeque<>();
    private final List<String> delivered=new ArrayList<>();
    private long end,next;
    private String failure;
    private boolean sampledBeforeReady;
    private java.util.concurrent.CountDownLatch stageEntered,releaseStage;
    @Override protected OwnedBillingFirstPaymentRuntime.Operations createScenario(OwnedBillingFirstPaymentRuntime.Admission admission) {
        return new OwnedBillingFirstRenewalScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(120));
    }
    @Override protected Object runtimeBean(Class<?> type) {
        if(type==dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.class){
            var delegate=application.getBean(dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.class);
            return (dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase)command->{refreshObservationClock();delegate.apply(command);};
        }
        return super.runtimeBean(type);
    }
    private OwnedBillingFirstRenewalScenario combined(){return (OwnedBillingFirstRenewalScenario)scenario;}
    @Override protected StripeResponse renewalResponse(StripeRequest request) throws StripeException {
        if(renewalReplies.isEmpty())return null;
        if(stageEntered!=null){
            stageEntered.countDown();
            try{if(!releaseStage.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("inert stage release timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ApiConnectionException("inert interrupted");}
        }
        if(!sampledBeforeReady){
            sampledBeforeReady=true;
            assertThatThrownBy(()->application.getBean(dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase.class).get(createCommand.context().actor(),site,UUID.fromString(prepared.get("subscription_id").getAsString()))).isInstanceOf(RuntimeException.class);
        }
        if("lost_ack".equals(failure)&&request.url().getPath().endsWith("/advance")&&request.method().name().equals("POST"))throw new ApiConnectionException("inert lost acknowledgement");
        if("stale_fence".equals(failure)&&request.url().getPath().equals("/v1/subscriptions/sub_owned")){
            jdbc.update("update subscriptions set revision=revision+1 where id=?",UUID.fromString(prepared.get("subscription_id").getAsString()));failure=null;
        }
        return new StripeResponse(200,HttpHeaders.of(Map.of()),renewalReplies.removeFirst());
    }
    private void initial() throws Exception {
        prepare();noMoney();scenario.confirm(confirmRequest());noMoney();paidGraph();
        var settled=observe();assertThat(settled.get("phase").getAsString()).isEqualTo("SETTLED_LOCAL");
        end=Instant.parse(settled.get("paid_through").getAsString()).getEpochSecond();next=end+31*86400;
        deliver();verifyOwnedInitialSql();assertThat(count("payments")).isEqualTo(1);assertThat(posts()).isEqualTo(4);
    }
    private String clock(long frozen,String status){var c=JsonParser.parseString(clockJson(owner)).getAsJsonObject();c.addProperty("frozen_time",frozen);c.addProperty("status",status);return c.toString();}
    private void advanceReplies(boolean finalization){long before=finalization?end:FROZEN,target=finalization?end+3600:end;renewalReplies.add(clock(before,"ready"));renewalReplies.add(clock(before,"advancing"));renewalReplies.add(clock(target,"ready"));}
    private void graphReplies(boolean finalization,boolean paid) {
        long at=finalization?end+3600:end;
        var renewal=graph.invoice.deepCopy();renewal.addProperty("id","in_renewal");renewal.addProperty("billing_reason","subscription_cycle");renewal.addProperty("status",paid?"paid":"draft");renewal.addProperty("amount_paid",paid?2000:0);renewal.addProperty("amount_remaining",paid?0:2000);renewal.addProperty("amount_paid_off_stripe",0);
        var line=graph.line.deepCopy();line.addProperty("id","il_renewal");line.addProperty("invoice","in_renewal");line.addProperty("subscription","sub_owned");line.addProperty("subscription_item","si_owned");line.getAsJsonObject("period").addProperty("start",end);line.getAsJsonObject("period").addProperty("end",next);
        graph.item().addProperty("current_period_start",end);graph.item().addProperty("current_period_end",next);
        Collections.addAll(renewalReplies,clock(at,"ready"),customerJson("clock_owned"),graph.remote.toString(),StripeBillingOwnedSubscriptionFixture.page(graph.invoice,renewal),StripeBillingOwnedSubscriptionFixture.page(graph.line),StripeBillingOwnedSubscriptionFixture.page(graph.allocation),graph.pi.toString(),graph.charge.toString(),StripeBillingOwnedSubscriptionFixture.page(graph.allocation),StripeBillingOwnedSubscriptionFixture.page(line));
        if(paid){
            var allocation=graph.allocation.deepCopy();allocation.addProperty("id","inpay_renewal");allocation.addProperty("invoice","in_renewal");allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");allocation.getAsJsonObject("status_transitions").addProperty("paid_at",end+11);
            var pi=graph.pi.deepCopy();pi.addProperty("id","pi_renewal");pi.addProperty("latest_charge","ch_renewal");var charge=graph.charge.deepCopy();charge.addProperty("id","ch_renewal");charge.addProperty("payment_intent","pi_renewal");
            Collections.addAll(renewalReplies,StripeBillingOwnedSubscriptionFixture.page(allocation),pi.toString(),charge.toString(),StripeBillingOwnedSubscriptionFixture.page(allocation));
        }else renewalReplies.add(StripeBillingOwnedSubscriptionFixture.page());
        renewalReplies.add(clock(at,"ready"));
    }
    private JsonObject stage(String path) throws Exception {
        refreshObservationClock();var x=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST",path,"{}",true);
        new OwnedBillingFirstRenewalRuntime.Handler(combined(),"q".repeat(43)).handle(x);assertThat(x.code).as(x.output.toString()).isEqualTo(200);return JsonParser.parseString(x.output.toString()).getAsJsonObject();
    }
    private void deliver() {
        var claims=outbox.claimAutomatic("combined",10,OwnedBillingFirstPaymentRuntimeTest.callbackClaimUtc(Instant.now()),Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        for(var claim:claims)new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,bytes)->{
            long timestamp=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(Instant.ofEpochSecond(timestamp)).isBetween(began,Instant.now());
            assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret())),timestamp,bytes));
            var json=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();assertThat(json.get("subscription_id").getAsString()).isEqualTo(prepared.get("subscription_id").getAsString());delivered.add(json.toString());return CallbackHttpPort.Result.success(204);
        },Clock.systemUTC()).deliver(claim);
    }
    @Test void genuineInitialAndExactlyOneRenewalPreserveOriginalLineageAndDeliverFourSignedCallbacks() throws Exception {
        initial();var original=jdbc.queryForList("select id,payload::text from outbox_events order by id");
        advanceReplies(false);graphReplies(false,false);var b=stage("/first-renewal/boundary");
        assertThat(b.keySet()).containsExactlyInAnyOrder("phase","code","run_id","account_id","site_id","customer_id","price_id","subscription_id","operation_id","renewal_admission_id","boundary_time","period_start","period_end");
        assertThat(b.get("phase").getAsString()).isEqualTo("RENEWAL_BOUNDARY");assertThat(b.get("boundary_time")).isEqualTo(b.get("period_start"));
        assertThat(count("payments")).isEqualTo(1);assertThat(jdbc.queryForList("select id,payload::text from outbox_events order by id")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select paid_through from subscriptions",java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.ofEpochSecond(end));
        advanceReplies(true);graphReplies(true,true);var done=stage("/first-renewal/finalization");
        assertThat(done.keySet()).containsExactlyInAnyOrder("phase","code","run_id","account_id","site_id","customer_id","price_id","subscription_id","operation_id","renewal_admission_id","publication_id","period_start","paid_through","access_evaluated_at","access_valid_until");
        assertThat(done.get("phase").getAsString()).isEqualTo("RENEWAL_SETTLED_LOCAL");assertThat(done.get("renewal_admission_id")).isEqualTo(b.get("renewal_admission_id"));assertThat(done.get("publication_id")).isEqualTo(done.get("renewal_admission_id"));
        assertThat(done.get("paid_through").getAsString()).isEqualTo(Instant.ofEpochSecond(next).toString());assertThat(done.get("access_evaluated_at").getAsString()).isEqualTo(Instant.ofEpochSecond(end+3600).toString());assertThat(done.get("access_valid_until").getAsString()).isEqualTo(Instant.ofEpochSecond(end+3900).toString());
        assertThat(jdbc.queryForList("select amount_minor from payments",Long.class)).containsExactlyInAnyOrder(2000L,2000L);assertThat(jdbc.queryForObject("select sum(amount_minor) from payments",Long.class)).isEqualTo(4000);
        assertThat(jdbc.queryForList("select provider_payment_reference from payments",String.class)).containsExactlyInAnyOrder("pi_owned","pi_renewal");
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);assertThat(count("stripe_billing_first_renewal_boundary")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        assertThat(jdbc.queryForList("select target from stripe_billing_first_renewal_steps order by ordinal",Long.class)).containsExactly(end,end+3600);
        assertThat(count("payment_events")).isEqualTo(2);assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactly("PERIOD_PAID","PERIOD_PAID");
        assertThat(requests.stream().filter(r->r.method().name().equals("POST")&&r.url().getPath().endsWith("/advance")).map(r->Long.parseLong(r.params().get("frozen_time").toString())).toList()).containsExactly(end,end+3600);
        assertThat(posts()).isEqualTo(6);assertThat(renewalReplies).isEmpty();deliver();assertThat(delivered).hasSize(4);assertThat(new HashSet<>(delivered)).hasSize(4);assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");
        assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(posts()).isEqualTo(6);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"lost_ack","stale_fence","authority_drift"})
    void failedBoundaryCannotFinalizeOrAdvanceAgain(String fault) throws Exception {
        initial();failure=fault;advanceReplies(false);graphReplies(false,false);
        if(fault.equals("authority_drift"))jdbc.update("update payment_accounts set status='DISABLED' where id=?",billing.id().value());
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");var posts=posts();
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");
        assertThat(posts()).isEqualTo(posts);assertThat(posts).isLessThanOrEqualTo(5);assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }
    @Test void concurrentFinalizationBurnsBoundaryBeforeAnyAdvancePostAndStopsDurableAuthority() throws Exception {
        initial();advanceReplies(false);graphReplies(false,false);stageEntered=new java.util.concurrent.CountDownLatch(1);releaseStage=new java.util.concurrent.CountDownLatch(1);
        var result=new java.util.concurrent.FutureTask<JsonObject>(combined()::boundary);Thread.ofVirtual().start(result);
        try{
            assertThat(stageEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");
        }finally{releaseStage.countDown();}
        assertThatThrownBy(()->result.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(combined().status().get("phase").getAsString()).isEqualTo("STOPPED");assertThat(posts()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select stopped_at is not null from stripe_billing_first_renewals",Boolean.class)).isTrue();
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(count("payments")).isEqualTo(1);
    }
    @Test void failedOriginalConfirmationCannotEnterRenewal() {
        prepare();outcome="disconnect";assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(posts()).isEqualTo(4);assertThat(count("stripe_billing_first_renewals")).isZero();noMoney();
    }
    @Test void pendingFinalizationConsumesAuthorityAndCannotThirdAdvance() throws Exception {
        initial();advanceReplies(false);graphReplies(false,false);stage("/first-renewal/boundary");advanceReplies(true);for(int i=0;i<3;i++)graphReplies(true,false);
        assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(combined()::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(posts()).isEqualTo(6);assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }
}
