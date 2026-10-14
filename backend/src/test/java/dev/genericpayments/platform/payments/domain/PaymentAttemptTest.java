package dev.genericpayments.platform.payments.domain;

import static dev.genericpayments.platform.support.PostgresIntegrationTest.INITIAL_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentAttemptTest {
    private static final PaymentAttemptId ID = new PaymentAttemptId(UUID.fromString("00000000-0000-0000-0000-000000000005"));

    @Test
    void startKeepsDeterministicOperationKeyAndPositiveSequence() {
        PaymentAttempt attempt = attempt();
        assertThat(attempt.sequence()).isEqualTo(1);
        assertThat(attempt.providerOperationKey()).isEqualTo("payment:00000000-0000-0000-0000-000000000001:attempt:1");
        assertThat(attempt.status()).isEqualTo(PaymentAttemptStatus.CREATED);
        assertThatThrownBy(() -> PaymentAttempt.start(ID, PaymentTest.PAYMENT_ID, 0, "key", "card", INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> PaymentAttempt.start(ID, PaymentTest.PAYMENT_ID, 1, " ", "card", INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void onlyUnknownBlocksSubsequentAttemptPendingReconciliation(PaymentAttemptStatus status) {
        PaymentAttempt original = attempt();
        PaymentAttempt stored = new PaymentAttempt(ID, PaymentTest.PAYMENT_ID, 1, original.providerOperationKey(),
            null, "card", status, null, INITIAL_TIME, INITIAL_TIME);
        assertThat(stored.blocksSubsequentAttempt()).isEqualTo(status == PaymentAttemptStatus.UNKNOWN);
    }

    @Test
    void unknownCanResolveToProviderConfirmedSuccessKeepingOriginalOperation() {
        PaymentAttempt original = attempt().withProviderIntentReference("fake_pi_1", INITIAL_TIME);
        PaymentAttempt unknown = original.unknown("timeout", INITIAL_TIME.plusSeconds(1));
        PaymentAttempt resolved = unknown.succeeded(INITIAL_TIME.plusSeconds(2));
        assertThat(unknown.blocksSubsequentAttempt()).isTrue();
        assertThat(resolved.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(resolved.blocksSubsequentAttempt()).isFalse();
        assertThat(resolved.providerOperationKey()).isEqualTo("payment:00000000-0000-0000-0000-000000000001:attempt:1");
        assertThat(resolved.providerIntentReference()).isEqualTo("fake_pi_1");
        assertThat(resolved.resultCode()).isNull();
        assertThat(original.status()).isEqualTo(PaymentAttemptStatus.CREATED);
    }

    @Test
    void recoverableFailureTerminatesAttemptWithoutBlockingRetry() {
        PaymentAttempt failed = attempt().processing(INITIAL_TIME).failed("declined", INITIAL_TIME.plusSeconds(1));
        assertThat(failed.status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(failed.resultCode()).isEqualTo("declined");
        assertThat(failed.blocksSubsequentAttempt()).isFalse();
        assertThatThrownBy(() -> failed.processing(INITIAL_TIME.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void processingAttemptCannotRegressAndSuccessIsTerminal() {
        PaymentAttempt processing = attempt().requiresPaymentMethod(INITIAL_TIME).requiresAction(INITIAL_TIME).processing(INITIAL_TIME);
        assertThatThrownBy(() -> processing.requiresAction(INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        PaymentAttempt success = processing.succeeded(INITIAL_TIME);
        assertThatThrownBy(() -> success.unknown("timeout", INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThat(success.succeeded(INITIAL_TIME.plusSeconds(1))).isSameAs(success);
        assertThatThrownBy(() -> attempt().cancelled(INITIAL_TIME).succeeded(INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void attemptRejectsUnsafeResultCodesAndRegressiveTimes() {
        assertThatThrownBy(() -> attempt().failed("raw provider message / secret", INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> attempt().processing(INITIAL_TIME.minusSeconds(1)))
            .isInstanceOf(DomainValidationException.class);
    }

    private PaymentAttempt attempt() {
        return PaymentAttempt.start(ID, PaymentTest.PAYMENT_ID, 1,
            "payment:00000000-0000-0000-0000-000000000001:attempt:1", "card", INITIAL_TIME);
    }
}
