package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationAttempt;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SubscriptionConfirmationAttemptTest {
 final Instant now=Instant.parse("2026-10-29T00:00:00Z");
 SubscriptionConfirmationAttempt ready(){return SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),UUID.randomUUID(),null,"v1",now,now.plusSeconds(600));}
 @Test void consumingCapabilitySealsItEvenWhenNoReceiptArrives(){
  var claimed=ready().claim("a".repeat(64),"b".repeat(64),"c".repeat(64));
  assertThat(claimed.state().name()).isEqualTo("DISPATCHING");
  assertThatThrownBy(()->claimed.claim("a".repeat(64),"b".repeat(64),"c".repeat(64))).isInstanceOf(RuntimeException.class);
  assertThat(claimed.retryAllowed(now)).isFalse();assertThat(claimed.retryAllowed(now.plusSeconds(601))).isFalse();
 }
 @Test void onlyPersistedOriginalReceiptCanCreateOneSuccessor(){
  var claimed=ready().claim("a".repeat(64),"b".repeat(64),"c".repeat(64));
  var declined=claimed.finish(SubscriptionConfirmationAttempt.State.DECLINED,new SubscriptionConfirmationAttempt.Receipt("req_original","ch_original","generic_decline"));
  assertThat(declined.retryAllowed(now)).isTrue();var id=UUID.randomUUID();var retired=declined.successor(id,"d".repeat(64));
  assertThat(retired.successor()).isEqualTo(id);assertThat(retired.retryAllowed(now)).isFalse();
  assertThatThrownBy(()->retired.successor(UUID.randomUUID(),"e".repeat(64))).isInstanceOf(RuntimeException.class);
 }
 @Test void unsupportedOutcomesAndMissingReceiptCannotGrantRetry(){
  for(var state:List.of(SubscriptionConfirmationAttempt.State.UNKNOWN,SubscriptionConfirmationAttempt.State.OBSERVE,SubscriptionConfirmationAttempt.State.REQUIRES_ACTION))assertThat(ready().claim("a".repeat(64),"b".repeat(64),"c".repeat(64)).finish(state,null).retryAllowed(now)).isFalse();
  assertThatThrownBy(()->ready().claim("a".repeat(64),"b".repeat(64),"c".repeat(64)).finish(SubscriptionConfirmationAttempt.State.DECLINED,null)).isInstanceOf(RuntimeException.class);
 }
 @Test void receiptCannotBeRewrittenAndExpiryCannotBeExtended(){
  var claimed=ready().claim("a".repeat(64),"b".repeat(64),"c".repeat(64));var declined=claimed.finish(SubscriptionConfirmationAttempt.State.DECLINED,new SubscriptionConfirmationAttempt.Receipt("req_original","ch_original","generic_decline"));
  assertThatThrownBy(()->declined.finish(SubscriptionConfirmationAttempt.State.UNKNOWN,null)).isInstanceOf(RuntimeException.class);
  assertThat(declined.retryAllowed(now.plusSeconds(600))).isFalse();assertThat(declined.expiresAt()).isEqualTo(now.plusSeconds(600));
 }
}
