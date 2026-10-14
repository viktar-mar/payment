package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.CallbackSigner;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DonationSettledRedactionIntegrationTest extends DonationQueryTestSupport {
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired CallbackPayloadPort payload;
    @Autowired OutboxPort outbox;
    @Autowired PaymentPreparationCoordinator preparation;
    @Autowired ProviderInboxService inbox;
    @Autowired ProviderInboxWorker worker;

    @Test void explicitRedactionAfterSettlementPreservesEveryFinancialAndCallbackFact() throws Exception {
        var payment=donation(customer(),MESSAGE);
        callbacks.configure(site.value(),"https://example.com/callback",true);
        var prepared=preparation.prepare(prepare(payment));
        inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"task9-settlement",prepared.providerPaymentReference(),
            PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));worker.runOnce();
        assertThat(load.findById(site,payment.id()).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        var claim=outbox.claimAutomatic("task9-delivery",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        byte[] bytes=payload.bytes(claim);
        byte[] signingKey=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));
        String signature=new CallbackSigner().sign(signingKey,INITIAL_TIME.getEpochSecond(),bytes);
        assertThat(outbox.complete(claim,CallbackHttpPort.Result.success(204),INITIAL_TIME)).isTrue();
        var retry=outbox.enqueue(claim.eventId(),OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);
        var before=financial();
        for(String table:List.of("payments","payment_events","outbox_events","outbox_deliveries","outbox_retry_requests"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isPositive();
        var id=payment.donationId().value();
        redact(id,id.toString(),"settled-redaction").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        // Compare booleans so a mismatch cannot print full financial payloads or encrypted tuples.
        assertThat(financial().equals(before)).as("complete settled financial snapshots unchanged").isTrue();
        assertThat(donations.findEncryptedMessage(site,payment.donationId())).isEmpty();
        assertThat(jdbc.queryForObject("select message_state='REDACTED' and message_ciphertext is null and message_nonce is null and message_key_version is null from donations where id=?",Boolean.class,id)).isTrue();
        assertThat(Arrays.equals(payload.bytes(claim),bytes)).isTrue();
        assertThat(new CallbackSigner().sign(signingKey,INITIAL_TIME.getEpochSecond(),payload.bytes(claim)).equals(signature)).isTrue();
        assertThat(outbox.retry(retry.id())).isEqualTo(retry);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='DONATION_MESSAGE_REDACTED'",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where idempotency_key='settled-redaction' and status='COMPLETED'",Integer.class)).isOne();
        redact(id,id.toString(),"settled-redaction").andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertThat(financial().equals(before)).isTrue();
        String finite=before.toString()+jdbc.queryForList("select row_to_json(t)::text from audit_log t")+jdbc.queryForList("select row_to_json(t)::text from idempotency_records t");
        for(String value:List.of(MESSAGE,EMAIL,SUBJECT))assertThat(finite.contains(value)).as("finite settled sink scan").isFalse();
    }
    private List<Object> financial(){
        var facts=new ArrayList<Object>();
        for(String table:List.of("payments","payment_events","outbox_events","outbox_deliveries","outbox_retry_requests"))
            facts.add(jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by id"));
        return facts;
    }
}
