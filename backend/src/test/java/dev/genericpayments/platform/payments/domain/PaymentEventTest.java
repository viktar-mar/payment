package dev.genericpayments.platform.payments.domain;

import static dev.genericpayments.platform.support.PostgresIntegrationTest.INITIAL_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentEventTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void factCopiesMetadataAndPreservesUtcInstantFromOffsetInput() {
        Map<String, String> metadata = new HashMap<>(Map.of("result_code", "approved"));
        PaymentEvent event = event(1, HASH, metadata);
        metadata.put("result_code", "changed");
        assertThat(event.technicalMetadata()).containsEntry("result_code", "approved");
        assertThatThrownBy(() -> event.technicalMetadata().put("new", "value")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-13T12:15:30+02:00"));
        assertThat(event.money()).isEqualTo(Money.of(2000, "EUR"));
    }

    @Test
    void rejectsNonpositiveSequenceAndNonSha256PayloadHash() {
        assertThatThrownBy(() -> event(0, HASH, Map.of())).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, "x".repeat(64), Map.of())).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, "a".repeat(63), Map.of())).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingEventIdentityAndBlankRequestCorrelation() {
        assertThatThrownBy(() -> new PaymentEvent(null, PaymentTest.PAYMENT_ID, 1, PaymentTest.SITE_ID, PaymentTest.ACCOUNT_ID,
            PaymentEventType.PAYMENT_SUCCEEDED, PaymentEventSource.PROVIDER_EVENT, null, null, null, INITIAL_TIME,
            INITIAL_TIME, "req-1", null, HASH, Map.of())).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new PaymentEvent(ID, PaymentTest.PAYMENT_ID, 1, PaymentTest.SITE_ID, PaymentTest.ACCOUNT_ID,
            PaymentEventType.PAYMENT_CREATED, PaymentEventSource.API, null, null, null, INITIAL_TIME,
            INITIAL_TIME, " ", null, HASH, Map.of())).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paymentId", "siteId", "paymentAccountId", "type", "source", "occurredAt", "receivedAt", "requestId"})
    void rejectsEachMissingRequiredFactFieldIndependently(String missingField) {
        assertThatThrownBy(() -> new PaymentEvent(ID,
            missingField.equals("paymentId") ? null : PaymentTest.PAYMENT_ID, 1,
            missingField.equals("siteId") ? null : PaymentTest.SITE_ID,
            missingField.equals("paymentAccountId") ? null : PaymentTest.ACCOUNT_ID,
            missingField.equals("type") ? null : PaymentEventType.PAYMENT_SUCCEEDED,
            missingField.equals("source") ? null : PaymentEventSource.PROVIDER_EVENT,
            Money.of(2000, "EUR"), "fake_evt_1", "fake_pi_1",
            missingField.equals("occurredAt") ? null : INITIAL_TIME,
            missingField.equals("receivedAt") ? null : INITIAL_TIME.plusSeconds(1),
            missingField.equals("requestId") ? null : "req-1", null, HASH, Map.of()))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void metadataAcceptsLimitsAndRejectsOneCharacterOverKeyOrValueLimit() {
        assertThat(event(1, HASH, Map.of("k".repeat(64), "v".repeat(500))).technicalMetadata()).hasSize(1);
        assertThatThrownBy(() -> event(1, HASH, Map.of("k".repeat(65), "v"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, HASH, Map.of("key", "v".repeat(501)))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void metadataRejectsEntryOverflowAndCountsUtf8Bytes() {
        Map<String, String> twenty = new HashMap<>();
        for (int i = 0; i < 20; i++) twenty.put("k" + i, "v");
        assertThat(event(1, HASH, twenty).technicalMetadata()).hasSize(20);
        twenty.put("overflow", "v");
        assertThatThrownBy(() -> event(1, HASH, twenty)).isInstanceOf(DomainValidationException.class);
        Map<String, String> bytes = Map.of("a", "é".repeat(500), "b", "é".repeat(500),
            "c", "é".repeat(500), "d", "é".repeat(500), "e", "é".repeat(46));
        assertThatThrownBy(() -> event(1, HASH, bytes)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void metadataRejectsSensitiveFieldNamesEmailAndControlCharacters() {
        assertThatThrownBy(() -> event(1, HASH, Map.of("access_token", "secret"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, HASH, Map.of("email", "person@example.org"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, HASH, Map.of("card_number", "4242424242424242"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, HASH, Map.of("note", "person@example.org"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> event(1, HASH, Map.of("note", "line\nnext"))).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4242424242424242", "Bearer token-value", "sk_live_providerSecret", "4111 1111 1111 1111"})
    void metadataRejectsRecognizablePaymentDataAndCredentialsUnderGenericKeys(String value) {
        assertThatThrownBy(() -> event(1, HASH, Map.of("note", value))).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person@example.org", "4242424242424242", "sk_live_abc123"})
    void metadataRejectsRecognizableSensitiveContentInKeys(String key) {
        assertThatThrownBy(() -> event(1, HASH, Map.of(key, "approved"))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void metadataAcceptsOrdinaryTechnicalKeyAfterContentValidation() {
        assertThat(event(1, HASH, Map.of("result_code", "approved")).technicalMetadata())
            .containsEntry("result_code", "approved");
    }

    @Test
    void metadataAcceptsExactly4096Utf8BytesAndRejectsOneByteMore() {
        Map<String, String> atLimit = Map.of("a", "é".repeat(500), "b", "é".repeat(500),
            "c", "é".repeat(500), "d", "é".repeat(500), "e", "v".repeat(91));
        assertThat(event(1, HASH, atLimit).technicalMetadata()).hasSize(5);
        Map<String, String> overLimit = new HashMap<>(atLimit);
        overLimit.put("e", "v".repeat(92));
        assertThatThrownBy(() -> event(1, HASH, overLimit)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void identifiersRejectMissingUuidInsteadOfAdmittingUnpersistableEntities() {
        assertThatThrownBy(() -> new PaymentId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new PaymentAttemptId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new RefundId(null)).isInstanceOf(DomainValidationException.class);
    }

    private PaymentEvent event(long sequence, String hash, Map<String, String> metadata) {
        return new PaymentEvent(ID, PaymentTest.PAYMENT_ID, sequence, PaymentTest.SITE_ID, PaymentTest.ACCOUNT_ID,
            PaymentEventType.PAYMENT_SUCCEEDED, PaymentEventSource.PROVIDER_EVENT, Money.of(2000, "EUR"),
            "fake_evt_1", "fake_pi_1", INITIAL_TIME, INITIAL_TIME.plusSeconds(1), "req-1", null, hash, metadata);
    }
}
