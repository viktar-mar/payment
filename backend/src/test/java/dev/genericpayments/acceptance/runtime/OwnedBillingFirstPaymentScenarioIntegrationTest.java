package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.stripe.StripeClient;
import com.stripe.net.*;
import com.stripe.exception.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.*;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Hosted only. Original initial-only assertions remain binding. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
class OwnedBillingFirstPaymentScenarioIntegrationTest extends OwnedBillingFirstPaymentScenarioFixture {
    @Test void actualSdkKeySelectsThenConfirmsOnceAndFreshCashProducesSignedDeliveredCallbacks() throws Exception {
        callbacks.configure(site.value(),"http://callback-receiver:8080/callback",true);
        prepared=privateRequest("POST", "/initial-payment/prepare", prepareRequest);
        assertThat(prepared.getAsJsonObject("action").get("secret").getAsString()).startsWith("bga1_");
        noMoney();
        assertThat(privateRequest("POST", "/initial-payment/confirm", confirmRequest()).get("state").getAsString()).isEqualTo("OBSERVE");
        noMoney();
        paidGraph();
        var proof=privateRequest("POST", "/initial-payment/observe", new JsonObject());assertThat(proof.get("phase").getAsString()).isEqualTo("SETTLED_LOCAL");
        assertThat(proof.get("paid_through").getAsString()).isEqualTo("2023-12-15T22:13:20Z");assertThat(proof.get("access_valid_until").getAsString()).isEqualTo("2023-11-14T22:18:20Z");
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("subscription_billing_records")).isEqualTo(1);assertThat(count("subscription_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records",String.class)).isEqualTo("CONFIRMED_CASH");assertThat(jdbc.queryForObject("select provider_payment_reference from payments",String.class)).isEqualTo("pi_owned");assertThat(jdbc.queryForObject("select event_type from payment_events",String.class)).isEqualTo("PAYMENT_SUCCEEDED");assertThat(jdbc.queryForObject("select event_type from subscription_events",String.class)).isEqualTo("PERIOD_PAID");
        var snapshots=jdbc.queryForList("select id,payload::text from outbox_events order by id");assertThat(snapshots).hasSize(2);
        observe();assertThat(jdbc.queryForList("select id,payload::text from outbox_events order by id")).isEqualTo(snapshots);assertThat(posts()).isEqualTo(4);
        var claims=outbox.claimAutomatic("first-payment",10,OwnedBillingFirstPaymentRuntimeTest.callbackClaimUtc(Instant.now()),Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        for(var claim:claims) {
            new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,bytes)->{
                long timestamp=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(Instant.ofEpochSecond(timestamp)).isBetween(began,Instant.now());
                assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret())),timestamp,bytes));
                var json=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();assertThat(json.get("subscription_id").getAsString()).isEqualTo(prepared.get("subscription_id").getAsString());return CallbackHttpPort.Result.success(204);
            },Clock.systemUTC()).deliver(claim);
        }
        assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");
        assertThat(scenario.status().toString()).doesNotContain("ctoken","bca2_","bga1_","secret","customer_token");
        verifyOwnedInitialSql();
    }

    @ParameterizedTest @ValueSource(strings={"requires_action","requires_payment_method","processing","disconnect"})
    void anyNonObserveOrLostResponseIsTerminalWithoutMoneyOrRedispatch(String outcome) {
        this.outcome=outcome;prepare();assertThatThrownBy(()->scenario.confirm(confirmRequest())).hasMessage("INITIAL_PAYMENT_STOPPED");
        long posts=posts();assertThat(posts).isEqualTo(4);assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThatThrownBy(this::observe).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(posts);noMoney();
    }
    @Test void duplicatePrepareAndRestartCannotAdoptExistingOwner() {
        prepare();long posts=posts();assertThatThrownBy(()->scenario.prepare(prepareRequest)).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(posts);
        var admission=new OwnedBillingFirstPaymentRuntime.Admission(run,"a".repeat(40),FROZEN,"q".repeat(43),8099,"127.0.0.1");
        var restarted=new OwnedBillingFirstPaymentScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(60));assertThatThrownBy(()->restarted.prepare(prepareRequest)).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(posts);noMoney();
    }
    @ParameterizedTest @ValueSource(strings={"price","account","session","source","callback","mapping"})
    void preflightRejectsBeforeAnyProviderMutation(String drift) {
        switch(drift){case "mapping"->jdbc.update("delete from provider_price_mappings where price_id=?",createCommand.priceId().value());case "price"->prepareRequest.addProperty("price_id",originalPrice.toString());case "account"->jdbc.update("update payment_accounts set external_account_id='acct_foreign'");case "session"->prepareRequest.addProperty("customer_token","bad");case "source"->prepareRequest.addProperty("source_sha","b".repeat(40));case "callback"->callbacks.configure(site.value(),"https://example.com/callback",false);}
        assertThatThrownBy(()->scenario.prepare(prepareRequest)).hasMessage("INITIAL_PAYMENT_STOPPED");assertThat(requests).isEmpty();assertThat(count("stripe_billing_clock_intents")).isZero();noMoney();
    }
    @Test void revokedSessionBeforeConfirmAndExplicitAbortNeverDispatch() {
        prepare();var context=sessions.resolveRequired(prepareRequest.get("customer_token").getAsString(),"https://shop.test");sessions.logout(context);
        assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(3);scenario.abort();assertThatThrownBy(this::observe).isInstanceOf(RuntimeException.class);noMoney();
    }
    @Test void driftAfterProviderPostRetainsConsumedAttemptWithoutAnotherPost() {
        prepare();driftAfterConfirm=true;assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(4);noMoney();
    }
    @ParameterizedTest @ValueSource(strings={"revision","fence"}) void staleObservationCannotBeRebasedOntoFreshFence(String drift) {
        prepare();scenario.confirm(confirmRequest());paidGraph();staleDuringRead=drift.equals("revision");consumeDuringRead=drift.equals("fence");
        assertThatThrownBy(this::observe).hasMessage("INITIAL_PAYMENT_STOPPED");noMoney();assertThatThrownBy(this::observe).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(4);
    }
    @Test void lostLocalConfirmationAcknowledgementStaysDispatchingAndCannotRepeat() {
        prepare();jdbc.execute("create function first_payment_reject_ack() returns trigger language plpgsql as $$ begin if new.state='OBSERVE' then raise exception 'synthetic'; end if; return new; end; $$");jdbc.execute("create trigger first_payment_reject_ack before update on subscription_confirmation_attempts for each row execute function first_payment_reject_ack()");
        try{assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("drop trigger first_payment_reject_ack on subscription_confirmation_attempts");jdbc.execute("drop function first_payment_reject_ack()");}
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(4);noMoney();
    }
}
