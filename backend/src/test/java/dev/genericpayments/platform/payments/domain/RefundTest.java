package dev.genericpayments.platform.payments.domain;

import static dev.genericpayments.platform.support.PostgresIntegrationTest.INITIAL_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RefundTest {
    private static final RefundId ID = new RefundId(UUID.fromString("00000000-0000-0000-0000-000000000006"));

    @Test
    void requestReservesPositiveMatchingCurrencyWithoutChangingPayment() {
        Payment payment = PaymentTest.succeededPayment();
        Refund refund = request(payment, Money.of(500, "eur"));
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.paymentId()).isEqualTo(PaymentTest.PAYMENT_ID);
        assertThat(refund.money()).isEqualTo(Money.of(500, "EUR"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.refundedAmountMinor()).isZero();
    }

    @Test
    void requestRejectsUnpaidMismatchedCurrencyAndExcessRemainingAmount() {
        assertThatThrownBy(() -> request(PaymentTest.catalogPayment(), Money.of(500, "EUR"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> request(PaymentTest.succeededPayment(), Money.of(500, "USD"))).isInstanceOf(DomainValidationException.class);
        Payment partial = PaymentTest.succeededPayment().refundSucceeded(1500, INITIAL_TIME);
        assertThat(request(partial, Money.of(500, "EUR")).status()).isEqualTo(RefundStatus.PENDING);
        assertThatThrownBy(() -> request(partial, Money.of(501, "EUR"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> request(PaymentTest.succeededPayment().refundSucceeded(2000, INITIAL_TIME), Money.of(1, "EUR")))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void unknownRefundCanResolveWithoutChangingIdempotentOperation() {
        Refund original = request(PaymentTest.succeededPayment(), Money.of(500, "EUR"));
        Refund processing = original.withProviderRefundReference("fake_re_1", INITIAL_TIME).processing(INITIAL_TIME);
        Refund unknown = processing.unknown(INITIAL_TIME.plusSeconds(1));
        Refund success = unknown.succeeded(INITIAL_TIME.plusSeconds(2));
        assertThat(original.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(unknown.status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(success.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(success.providerOperationKey()).isEqualTo("refund:00000000-0000-0000-0000-000000000006");
        assertThat(success.providerRefundReference()).isEqualTo("fake_re_1");
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void terminalRefundCannotRegress(RefundStatus status) {
        Refund original = request(PaymentTest.succeededPayment(), Money.of(500, "EUR"));
        Refund terminal = switch (status) {
            case SUCCEEDED -> original.succeeded(INITIAL_TIME);
            case FAILED -> original.failed(INITIAL_TIME);
            case CANCELLED -> original.cancelled(INITIAL_TIME);
            default -> throw new AssertionError(status);
        };
        assertThatThrownBy(() -> terminal.processing(INITIAL_TIME.plusSeconds(1))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void refundRejectsMissingOperationKeyUnsafeReasonAndRegressiveTime() {
        assertThatThrownBy(() -> Refund.request(ID, PaymentTest.succeededPayment(), Money.of(500, "EUR"), " ",
            "requested_by_customer", null, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Refund.request(ID, PaymentTest.succeededPayment(), Money.of(500, "EUR"), "key",
            "reason with raw sensitive text", null, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> request(PaymentTest.succeededPayment(), Money.of(500, "EUR")).processing(INITIAL_TIME.minusSeconds(1)))
            .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person@example.org", "4242424242424242", "Bearer token-value"})
    void refundRejectsSensitiveDataInTechnicalNote(String note) {
        assertThatThrownBy(() -> Refund.request(ID, PaymentTest.succeededPayment(), Money.of(500, "EUR"), "key",
            "requested_by_customer", note, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
    }

    private Refund request(Payment payment, Money amount) {
        return Refund.request(ID, payment, amount, "refund:00000000-0000-0000-0000-000000000006",
            "requested_by_customer", "operator approved", INITIAL_TIME);
    }
}
