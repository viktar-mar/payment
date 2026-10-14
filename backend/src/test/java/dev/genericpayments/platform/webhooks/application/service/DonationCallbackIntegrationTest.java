package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.donations.application.service.DonationFinancialTestFixture;
import dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.beans.factory.annotation.Autowired;

@ExtendWith(OutputCaptureExtension.class)
class DonationCallbackIntegrationTest extends DonationFinancialTestFixture {
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired CallbackPayloadPort payload;
    @Autowired OutboxPort outbox;
    @Autowired PaymentPreparationCoordinator preparation;
    @Autowired ProviderInboxService inbox;
    @Autowired ProviderInboxWorker worker;
    @Autowired RefundCoordinator refunds;
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired Clock clock;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    private final ObjectMapper mapper=new ObjectMapper();
    private static final Set<String> V1=Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at");
    private static final Set<String> V2=Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","donation_id","campaign_key","customer_id");
    @ParameterizedTest @EnumSource(value=PaymentSource.class,names={"CATALOG","DYNAMIC","DONATION"})
    void immutableSourceSelectsExactFieldSet(PaymentSource source) throws Exception {
        var p=source(source);callbacks.configure(site.value(),"https://example.com/callback",true);confirmed(p);
        var claim=claim(INITIAL_TIME);var node=mapper.readTree(payload.bytes(claim));
        var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isEqualTo(source==PaymentSource.DONATION?V2:V1);
        assertThat(node.get("schema_version").intValue()).isEqualTo(source==PaymentSource.DONATION?2:1);
        if(source==PaymentSource.DONATION) {
            assertThat(node.get("donation_id").asText()).isEqualTo(p.donationId().value().toString());
            assertThat(node.get("campaign_key").asText()).isEqualTo("help-now");
            assertThat(node.get("customer_id").isNull()).isTrue();assertThat(node.get("external_order_id").isNull()).isTrue();
        } else assertThat(node.get("external_order_id").isNull()).isEqualTo(source==PaymentSource.CATALOG);
    }
    @Test void retrySignsStoredSnapshotAfterCampaignEditAndCustomerRedactionWithoutLeaks(CapturedOutput output) throws Exception {
        var customer=customer();var p=donation(customer,MESSAGE);callbacks.configure(site.value(),"https://example.com/callback",true);confirmed(p);
        var first=claim(INITIAL_TIME);byte[] original=payload.bytes(first);
        assertThat(mapper.readTree(original).path("customer_id").asText()).isEqualTo(customer.id().value().toString());
        outbox.complete(first,CallbackHttpPort.Result.failed(503,CallbackHttpPort.ErrorCategory.HTTP_STATUS),INITIAL_TIME);
        campaigns.update(campaign.updateConfiguration("Changed",null,"EUR",List.of(100L),100,200,true,true,INITIAL_TIME),campaign.version());
        anonymizer.anonymize(new AnonymizeCustomerUseCase.Command(site,customer.id(),customer.id().value(),"task5-erase","ADMIN","safe-actor","task5-erase","127.0.0.1"));
        var retry=claim(INITIAL_TIME.plusSeconds(60));assertThat(retry.eventId()).isEqualTo(first.eventId());
        assertThat(payload.bytes(retry)).isEqualTo(original);
        new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{
            assertThat(body).isEqualTo(original);
            assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(Base64.getUrlDecoder().decode(encryption.decrypt(retry.secret())),INITIAL_TIME.plusSeconds(60).getEpochSecond(),body));
            return CallbackHttpPort.Result.success(204);
        },Clock.fixed(INITIAL_TIME.plusSeconds(60),ZoneOffset.UTC)).deliver(retry);
        String finite=jdbc.queryForList("select technical_metadata::text from payment_events")+jdbc.queryForList("select payload::text from outbox_events").toString()
            +jdbc.queryForList("select * from audit_log")+metrics.getMeters().stream().map(m->m.getId().toString()).toList()+output.getAll()+new String(original,StandardCharsets.UTF_8);
        for(String secret:List.of(MESSAGE,EMAIL,SUBJECT))assertThat(finite.contains(secret)).as("finite financial/callback/audit/meter/log fixture scan").isFalse();
        assertThat(jdbc.queryForObject("select status from outbox_events",String.class)).isEqualTo("DELIVERED");
    }
    void confirmed(Payment p) {
        var prepared=preparation.prepare(prepare(p));
        inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"task5-success",prepared.providerPaymentReference(),PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));worker.runOnce();
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
    @Test void donationSnapshotRetainsSelectedSecretAcrossRotationAndCleanup() {
        var p=donation(null,MESSAGE);callbacks.configure(site.value(),"https://example.com/callback",true);confirmed(p);
        var original=claim(INITIAL_TIME);byte[] bytes=payload.bytes(original);
        outbox.complete(original,CallbackHttpPort.Result.failed(500,CallbackHttpPort.ErrorCategory.HTTP_STATUS),INITIAL_TIME);
        callbacks.rotate(site.value());
        assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(86400))).isZero();
        var retry=claim(INITIAL_TIME.plusSeconds(60));
        assertThat(retry.secretVersion()).isEqualTo(1);assertThat(payload.bytes(retry)).isEqualTo(bytes);
        assertThat(encryption.decrypt(retry.secret())).isEqualTo(encryption.decrypt(original.secret()));
        outbox.complete(retry,CallbackHttpPort.Result.success(204),INITIAL_TIME.plusSeconds(60));
        assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(86400))).isEqualTo(1);
    }
    @Test void partialAndFullRefundCallbacksKeepDonationContextAndPaymentProjection() throws Exception {
        var p=donation(null,MESSAGE);callbacks.configure(site.value(),"https://example.com/callback",true);confirmed(p);
        long refunded=0;
        for(long amount:new long[]{700,1300}) {
            var refund=refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,p.id(),UUID.randomUUID(),amount,"EUR","REQUESTED_BY_CUSTOMER",null,UUID.randomUUID().toString(),"task5-refund"));
            inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"refund-"+amount,refund.providerRefundReference(),PaymentEventType.REFUND_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));worker.runOnce();
            refunded+=amount;
            String json=jdbc.queryForObject("select payload::text from outbox_events where payment_id=? and callback_type='refund.succeeded' and (payload->>'amount_minor')::bigint=?",String.class,p.id().value(),amount);
            var node=mapper.readTree(json);
            assertThat(node.path("schema_version").intValue()).isEqualTo(2);
            assertThat(node.path("donation_id").asText()).isEqualTo(p.donationId().value().toString());
            assertThat(node.path("campaign_key").asText()).isEqualTo("help-now");
            assertThat(node.path("customer_id").isNull()).isTrue();assertThat(node.path("external_order_id").isNull()).isTrue();
            assertThat(node.path("payment_status").asText()).isEqualTo(refunded==2000?"REFUNDED":"PARTIALLY_REFUNDED");
            assertThat(node.path("currency").asText()).isEqualTo("EUR");
        }
        var claims=outbox.claimAutomatic("task5-refunds",20,INITIAL_TIME,Duration.ofSeconds(30));
        // Preparation appends its journal fact but does not snapshot an extra callback.
        assertThat(claims).hasSize(5);
        assertThat(claims.stream().map(claim-> {
            try{return mapper.readTree(claim.payload()).path("event_type").asText();}
            catch(Exception invalid){throw new AssertionError(invalid);}
        }).toList()).containsExactlyInAnyOrder("payment.succeeded","refund.processing","refund.succeeded","refund.processing","refund.succeeded");
        for(var claim:claims)assertThatCode(()->payload.bytes(claim)).doesNotThrowAnyException();
    }
    OutboxPort.Claim claim(Instant at){return outbox.claimAutomatic("task5-worker",1,at,Duration.ofSeconds(30)).getFirst();}
}
