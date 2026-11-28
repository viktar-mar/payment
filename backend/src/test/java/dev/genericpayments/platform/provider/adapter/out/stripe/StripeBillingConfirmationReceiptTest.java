package dev.genericpayments.platform.provider.adapter.out.stripe;
import static org.assertj.core.api.Assertions.*;
import com.stripe.exception.CardException;
import com.stripe.param.PaymentIntentConfirmParams;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingConfirmationAdapter;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.util.*;
import org.junit.jupiter.api.Test;
class StripeBillingConfirmationReceiptTest {
 static final String ERROR="""
 {"error":{"type":"card_error","code":"card_declined","decline_code":"generic_decline","charge":"ch_original","message":"private canary","payment_intent":{"id":"pi_original","object":"payment_intent","status":"requires_payment_method","latest_charge":"ch_original","last_payment_error":{"type":"card_error","code":"card_declined","decline_code":"generic_decline","charge":"ch_original"}}}}
 """;
 CardException decline(String body,Map<String,String> headers)throws Exception {
  try(var http=new StripeHttpFixture()){
   http.enqueue("POST","/v1/payment_intents/pi_original/confirm",402,body,headers,0);
   var client=StripeClientFactoryTest.fixtureFactory(http.baseUri()).create(StripeClientFactoryTest.credentials("A"));
   var error=catchThrowable(()->client.v1().paymentIntents().confirm("pi_original",PaymentIntentConfirmParams.builder().setConfirmationToken("ctoken_original").setUseStripeSdk(true).build()));
   assertThat(http.calls()).hasSize(1);assertThat(http.calls().getFirst().form()).containsEntry("use_stripe_sdk","true");
   assertThat(error).isInstanceOf(CardException.class);return (CardException)error;
  }
 }
 @Test void exactSynchronousSdkReceiptCanProveOriginalDispatch()throws Exception{
  var proof=StripeBillingConfirmationAdapter.receipt(decline(ERROR,Map.of("Request-Id","req_original")),"pi_original");
  assertThat(proof).isNotNull();assertThat(proof.requestId()).isEqualTo("req_original");assertThat(proof.charge()).isEqualTo("ch_original");assertThat(proof.declineCode()).isEqualTo("generic_decline");assertThat(proof.toString()).doesNotContain("private canary");
 }
 @Test void missingRequestAndForeignIntentCannotProveDispatch()throws Exception{
  assertThat(StripeBillingConfirmationAdapter.receipt(decline(ERROR,Map.of()),"pi_original")).isNull();
  assertThat(StripeBillingConfirmationAdapter.receipt(decline(ERROR,Map.of("Request-Id","req_original")),"pi_other")).isNull();
 }
 @Test void contradictoryChargeAndUnsupportedErrorRemainClosed()throws Exception{
  assertThat(StripeBillingConfirmationAdapter.receipt(decline(ERROR.replace("\"latest_charge\":\"ch_original\"","\"latest_charge\":\"ch_other\""),Map.of("Request-Id","req_original")),"pi_original")).isNull();
  assertThat(StripeBillingConfirmationAdapter.receipt(decline(ERROR.replace("generic_decline","authentication_required"),Map.of("Request-Id","req_original")),"pi_original")).isNull();
 }
}
