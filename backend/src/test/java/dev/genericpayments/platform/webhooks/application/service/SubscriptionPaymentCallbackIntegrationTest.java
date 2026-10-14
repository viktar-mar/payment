package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionPaymentCallbackIntegrationTest extends SubscriptionCashFixture {
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired CallbackPayloadPort payload;
    @Autowired OutboxPort outbox;
    @Autowired RefundCoordinator refunds;
    @Autowired ProviderInboxService inbox;
    @Autowired ProviderInboxWorker worker;
    private final ObjectMapper mapper=new ObjectMapper();
    private static final Set<String> V3=Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","subscription_id","billing_record_id","customer_id","product_id","billing_kind","price_id","price_version","period_start","period_end");
    @Test void partialAndFullRefundsKeepV3ContextAndDoNotChangeSubscription() throws Exception {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        providerCash("cash-refund",PaymentStatus.SUCCEEDED);
        var p=stored(record("cash-refund",PaymentStatus.SUCCEEDED,"invoice-paid").paymentId());
        long total=0;
        for(long amount:new long[]{200,500}) {
            var r=refunds.create(new CreateRefundCommand(site,p.id(),UUID.randomUUID(),amount,"EUR","REQUESTED_BY_CUSTOMER",null,UUID.randomUUID().toString(),"subscription-refund"));
            assertThat(r.status()).isEqualTo(RefundStatus.PROCESSING);
            assertThat(fake.retrieveRefund(ProviderRefundLookup.forRefund(account,p.id(),r.id(),r.providerRefundReference())).status()).isEqualTo(ProviderState.Status.PROCESSING);
            inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"refund-"+amount,r.providerRefundReference(),PaymentEventType.REFUND_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));worker.runOnce();
            total+=amount;
            assertThat(stored(p.id()).status()).isEqualTo(total==700?PaymentStatus.REFUNDED:PaymentStatus.PARTIALLY_REFUNDED);
            var node=mapper.readTree(jdbc.queryForObject("select payload::text from outbox_events where callback_type='refund.succeeded' and (payload->>'amount_minor')::bigint=?",String.class,amount));
            assertThat(node.path("amount_minor").longValue()).isEqualTo(amount);
            assertThat(node.path("payment_status").asText()).isEqualTo(total==700?"REFUNDED":"PARTIALLY_REFUNDED");
        }
        var claims=outbox.claimAutomatic("subscription-callback",20,INITIAL_TIME,Duration.ofSeconds(30));
        assertThat(claims).hasSize(5);
        for(var claim:claims) {
            var node=mapper.readTree(payload.bytes(claim));var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).isEqualTo(V3);assertThat(node.path("schema_version").intValue()).isEqualTo(3);
            assertThat(node.path("subscription_id").asText()).isEqualTo(subscription.id().toString());
            assertThat(node.path("billing_record_id").asText()).isEqualTo(bill.id().toString());
            assertThat(node.path("customer_id").asText()).isEqualTo(customer.id().value().toString());
            assertThat(node.path("product_id").asText()).isEqualTo(terms.productId().value().toString());
            assertThat(node.path("billing_kind").asText()).isEqualTo("PRORATION");
            assertThat(node.path("price_id").asText()).isEqualTo(terms.priceId().value().toString());
            assertThat(node.path("price_version").intValue()).isEqualTo(1);
            assertThat(node.path("period_start").asText()).isEqualTo(bill.period().start().toString());
            assertThat(node.path("period_end").asText()).isEqualTo(bill.period().end().toString());
            assertThat(node.path("external_order_id").isNull()).isTrue();
        }
        assertThat(subscriptions.find(site,subscription.id()).orElseThrow()).isEqualTo(subscription);
        assertThat(jdbc.queryForObject("select count(*) from fake_provider_objects where object_type='PAYMENT'",Integer.class)).isZero();
    }
    @Test void callbackFailureRollsBackCashJournalAndLifecycleTogether() {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        jdbc.execute("create function task5_reject_callback() returns trigger language plpgsql as $$ begin raise exception 'injected callback failure' using errcode='23514'; end; $$");
        jdbc.execute("create trigger task5_reject_callback before insert on outbox_events for each row execute function task5_reject_callback()");
        try {
            assertThatThrownBy(()->tx.required(()->{
                subscriptions.save(subscription.cancel("CONFIRMED",INITIAL_TIME),0);
                return cash.record(command("atomic",PaymentStatus.SUCCEEDED,"paid"));
            })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("injected callback failure");
            assertThat(count("payments")).isZero();assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
            assertThat(subscriptions.find(site,subscription.id()).orElseThrow()).isEqualTo(subscription);
        } finally {jdbc.execute("drop trigger task5_reject_callback on outbox_events");jdbc.execute("drop function task5_reject_callback()");}
    }
    @Test void canonicalV3RejectsMissingExtraOrInvalidContextAndZeroMoney() throws Exception {
        callbacks.configure(site.value(),"https://example.com/callback",true);record("cash-validation",PaymentStatus.SUCCEEDED,"paid");
        var claim=outbox.claimAutomatic("subscription-validation",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        for(String field:V3) {
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(claim.payload());node.remove(field);
            assertThatThrownBy(()->payload.bytes(withPayload(claim,node.toString()))).isInstanceOf(IllegalArgumentException.class);
        }
        for(var patch:List.of(Map.of("customer_id","bad"),Map.of("billing_kind","OTHER"),Map.of("price_version",0),Map.of("amount_minor",0),Map.of("external_order_id","order"),Map.of("provider_invoice_reference","private"),Map.of("period_end",INITIAL_TIME.minusSeconds(1).toString()))) {
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(claim.payload());
            patch.forEach((k,v)->node.set(k,mapper.valueToTree(v)));
            assertThatThrownBy(()->payload.bytes(withPayload(claim,node.toString()))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    private OutboxPort.Claim withPayload(OutboxPort.Claim c,String value) {
        return new OutboxPort.Claim(c.eventId(),c.requestId(),c.siteId(),c.endpoint(),c.secretVersion(),c.secret(),value,c.attempt(),c.owner(),c.startedAt(),c.createdAt(),c.mode(),c.context());
    }
}
