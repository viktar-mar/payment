package dev.genericpayments.platform.provider.adapter.out.stripe.billing;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
class StripeBillingGrantTokensTest {
 static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
 @Test void deterministicAcrossNodesButBoundToVersionAndDescriptor(){
  var a=new StripeBillingGrantTokens(true,"v1",Map.of("v1",KEY));var b=new StripeBillingGrantTokens(true,"v1",Map.of("v1",KEY));
  assertThat(a.enabled()).isTrue();String token=a.token("v1","owner:one;expires:fixed");
  assertThat(token).matches("bga1_[A-Za-z0-9_-]{43}").isEqualTo(b.token("v1","owner:one;expires:fixed"));
  assertThat(a.token("v1","owner:two;expires:fixed")).isNotEqualTo(token);
  assertThat(StripeBillingGrantTokens.matches(token,StripeBillingGrantTokens.hash(token))).isTrue();
  assertThat(StripeBillingGrantTokens.matches(token+"x",StripeBillingGrantTokens.hash(token))).isFalse();
  assertThat(a.toString()).doesNotContain(KEY,token);
 }
 @Test void retainsOldVersionAcrossRotationAndRefusesRemovedKey(){
  String next=Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes());
  var old=new StripeBillingGrantTokens(true,"v1",Map.of("v1",KEY));var rotated=new StripeBillingGrantTokens(true,"v2",Map.of("v1",KEY,"v2",next));
  assertThatCode(()->assertThat(rotated.token("v1","binding")).isEqualTo(old.token("v1","binding"))).doesNotThrowAnyException();
  assertThatThrownBy(()->new StripeBillingGrantTokens(true,"v2",Map.of("v2",next)).token("v1","binding")).isInstanceOf(RuntimeException.class);
 }
 @ParameterizedTest @ValueSource(strings={"","not-base64-secret-canary","YQ=="}) void enabledInvalidKeysRefuseWithoutEcho(String value){assertThatThrownBy(()->new StripeBillingGrantTokens(true,"v1",Map.of("v1",value))).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Stripe Billing action configuration");}
 @Test void disabledIgnoresKeysAndNeverIssues(){var disabled=new StripeBillingGrantTokens(false,null,null);assertThat(disabled.enabled()).isFalse();assertThatThrownBy(()->disabled.token("v1","x")).isInstanceOf(RuntimeException.class);}
 @Test void explicitBoundedEnvironmentFormatAndDisabledStripeRemainSafe(){
  var env=new MockEnvironment().withProperty("PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED","true").withProperty("PAYMENT_STRIPE_BILLING_ACTIONS_ACTIVE_KEY_VERSION","v1").withProperty("PAYMENT_STRIPE_BILLING_ACTIONS_KEY_VERSIONS","v1").withProperty("PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1",KEY);
  assertThat(StripeBillingGrantTokens.from(env,false).enabled()).isFalse();assertThat(StripeBillingGrantTokens.from(env,true).enabled()).isTrue();
  env.setProperty("PAYMENT_STRIPE_BILLING_ACTIONS_KEY_VERSIONS","v1,v1");assertThatThrownBy(()->StripeBillingGrantTokens.from(env,true)).isInstanceOf(IllegalArgumentException.class);
 }
}
