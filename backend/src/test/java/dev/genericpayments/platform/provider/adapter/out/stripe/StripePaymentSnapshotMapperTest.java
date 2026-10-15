package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import com.stripe.model.*;
import com.stripe.net.ApiResource;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StripePaymentSnapshotMapperTest {
    final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"stripe-test",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
    final Payment payment=Payment.dynamic(new PaymentId(UUID.randomUUID()),new SiteId(UUID.randomUUID()),account.id(),"order",Money.of(2000,"EUR"),TaxMode.NONE,Map.of(),Instant.EPOCH).withProviderPaymentReference("pi_fixture",Instant.EPOCH);
    final String secret="generated_test_sentinel_"+UUID.randomUUID();
    final String intentJson="""
        {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"succeeded",
         "amount":2000,"amount_received":2000,"currency":"eur","capture_method":"automatic",
         "payment_method_types":["card"],"latest_charge":"ch_fixture"}
        """;
    final String chargeJson="""
        {"id":"ch_fixture","object":"charge","livemode":false,"payment_intent":"pi_fixture",
         "amount":2000,"amount_captured":2000,"currency":"eur","paid":true,"captured":true,
         "status":"succeeded","payment_method_details":{"type":"card"}}
        """;
    PaymentIntent intent(String json) { return ApiResource.GSON.fromJson(json,PaymentIntent.class); }
    Charge charge(String json) { return ApiResource.GSON.fromJson(json,Charge.class); }
    ProviderState map(String pi,String ch) { return StripePaymentSnapshotMapper.payment(account,payment,intent(pi),ch==null?null:charge(ch)); }

    @Test void recoverableCardFailureDoesNotBecomeTerminalPaymentFailure() {
        assertThat(StripePaymentSnapshotMapper.normalizedStatus("requires_payment_method")).isEqualTo(ProviderState.Status.REQUIRES_PAYMENT_METHOD);
        assertThat(StripePaymentSnapshotMapper.normalizedStatus("requires_capture")).isEqualTo(ProviderState.Status.UNKNOWN);
    }
    @ParameterizedTest @CsvSource({"requires_confirmation,REQUIRES_PAYMENT_METHOD","requires_payment_method,REQUIRES_PAYMENT_METHOD","requires_action,REQUIRES_ACTION","processing,PROCESSING","succeeded,SUCCEEDED","canceled,CANCELLED","requires_capture,UNKNOWN","new_status,UNKNOWN"})
    void normalizesOnlySupportedStates(String input,ProviderState.Status expected) {
        assertThat(StripePaymentSnapshotMapper.normalizedStatus(input)).isEqualTo(expected);
    }
    @Test void exactCapturedSuccessHasSafeEvidenceWithoutClientSecret() {
        var state=map(intentJson.replace("\"status\"", "\"client_secret\":\""+secret+"\",\"status\""),chargeJson);
        assertThat(state.status()).isEqualTo(ProviderState.Status.SUCCEEDED);
        assertThat(state.evidence()).isEqualTo(new ProviderEvidence(PaymentProviderType.STRIPE,account.id(),PaymentAccountMode.SANDBOX,"acct_expected",Money.of(2000,"EUR"),"pi_fixture","ch_fixture",null,true));
        assertThat(state.toString()).doesNotContain(secret);
    }
    @ParameterizedTest @CsvSource({"\"amount\":2000,\"amount\":1999","\"amount_received\":2000,\"amount_received\":1999","\"currency\":\"eur\",\"currency\":\"usd\"","\"livemode\":false,\"livemode\":true","pi_fixture,pi_foreign"})
    void rejectsForeignIntentMoneyModeOrReference(String from,String to) {
        assertThatThrownBy(() -> map(intentJson.replace(from,to),chargeJson)).isInstanceOf(PaymentApplicationException.class);
    }
    @ParameterizedTest @CsvSource({"\"amount\":2000,\"amount\":1999","\"amount_captured\":2000,\"amount_captured\":1999","\"currency\":\"eur\",\"currency\":\"usd\"","\"livemode\":false,\"livemode\":true","pi_fixture,pi_foreign","ch_fixture,ch_foreign","\"paid\":true,\"paid\":false","\"captured\":true,\"captured\":false","\"status\":\"succeeded\",\"status\":\"failed\""})
    void rejectsUnprovenCapturedCharge(String from,String to) {
        assertThatThrownBy(() -> map(intentJson,chargeJson.replace(from,to))).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void successWithoutChargeIsNotVerified() {
        assertThatThrownBy(() -> map(intentJson,null)).isInstanceOf(PaymentApplicationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"requires_capture","new_status"})
    void unsupportedStatusRefusesCapability(String state) {
        assertThatThrownBy(() -> map(intentJson.replace("succeeded",state),chargeJson))
            .isInstanceOf(PaymentApplicationException.class).extracting("code").isEqualTo("PAYMENT_CAPABILITY_UNAVAILABLE");
    }
    @Test void newClientInputWithoutChargeRemainsRetryable() {
        var pi=intentJson.replace("succeeded","requires_payment_method").replace("\"latest_charge\":\"ch_fixture\"","\"latest_charge\":null").replace("\"amount_received\":2000","\"amount_received\":0");
        var state=map(pi,null);
        assertThat(state.status()).isEqualTo(ProviderState.Status.REQUIRES_PAYMENT_METHOD);
        assertThat(state.evidence().chargeReference()).isNull();assertThat(state.evidence().failedChargeReference()).isNull();
    }
    @Test void verifiedDeclineCarriesFailedChargeWithoutTerminalFailure() {
        var pi=intentJson.replace("succeeded","requires_payment_method").replace("\"latest_charge\"","\"last_payment_error\":{\"charge\":\"ch_fixture\"},\"latest_charge\"");
        var ch=chargeJson.replace("true","false").replace("\"amount_captured\":2000","\"amount_captured\":0").replace("succeeded","failed");
        var state=map(pi,ch);
        assertThat(state.status()).isEqualTo(ProviderState.Status.REQUIRES_PAYMENT_METHOD);
        assertThat(state.evidence().failedChargeReference()).isEqualTo("ch_fixture");
        assertThat(state.evidence().captured()).isFalse();
    }
    @Test void foreignFailedChargeCannotBecomeRetryEvidence() {
        var pi=intentJson.replace("succeeded","requires_payment_method").replace("\"latest_charge\"","\"last_payment_error\":{\"charge\":\"ch_fixture\"},\"latest_charge\"");
        var ch=chargeJson.replace("true","false").replace("succeeded","failed").replace("pi_fixture","pi_foreign");
        assertThatThrownBy(() -> map(pi,ch)).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void latestVerifiedFailedChargeIsWatermarkEvenWithoutErrorChargeField() {
        var pi=intentJson.replace("succeeded","requires_payment_method").replace("\"amount_received\":2000","\"amount_received\":0");
        var ch=chargeJson.replace("true","false").replace("\"amount_captured\":2000","\"amount_captured\":0").replace("succeeded","failed");
        assertThat(map(pi,ch).evidence().failedChargeReference()).isEqualTo("ch_fixture");
    }
    @ParameterizedTest @CsvSource({"\"payment_method_types\":[\"card\"],\"payment_method_types\":[\"sepa_debit\"]","\"capture_method\":\"automatic\",\"capture_method\":\"manual\""})
    void unsupportedProviderCapabilityCannotClaimSuccess(String from,String to) {
        assertThatThrownBy(() -> map(intentJson.replace(from,to),chargeJson)).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("PAYMENT_CAPABILITY_UNAVAILABLE");
    }
}
