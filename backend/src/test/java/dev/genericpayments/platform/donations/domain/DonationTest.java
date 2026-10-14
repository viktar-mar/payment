package dev.genericpayments.platform.donations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DonationTest {
    private static final DonationId ID = new DonationId(UUID.fromString("30000000-0000-0000-0000-000000000001"));
    private static final SiteId SITE = new SiteId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final DonationCampaignId CAMPAIGN = new DonationCampaignId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final String CIPHERTEXT = "opaque-ciphertext-reference";

    @Test
    void encryptedMessageCreatesPresentStateAndRedactionDropsReferenceImmutably() {
        Donation initial = Donation.create(ID, SITE, CAMPAIGN, CIPHERTEXT, NOW);
        Donation redacted = initial.redactMessage(NOW.plusSeconds(1));
        assertThat(initial.messageState()).isEqualTo(DonorMessageState.PRESENT);
        assertThat(initial.encryptedMessageRef()).contains(CIPHERTEXT);
        assertThat(initial.updatedAt()).isEqualTo(NOW);
        assertThat(redacted.messageState()).isEqualTo(DonorMessageState.REDACTED);
        assertThat(redacted.encryptedMessageRef()).isEmpty();
        assertThat(redacted.id()).isEqualTo(ID);
        assertThat(redacted.siteId()).isEqualTo(SITE);
        assertThat(redacted.campaignId()).isEqualTo(CAMPAIGN);
        assertThat(redacted.createdAt()).isEqualTo(NOW);
        assertThat(redacted.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(redacted.redactMessage(NOW.plusSeconds(2))).isSameAs(redacted);
    }

    @Test
    void absentMessageStaysAbsentOnRedaction() {
        Donation donation = Donation.create(ID, SITE, CAMPAIGN, null, NOW);
        assertThat(donation.messageState()).isEqualTo(DonorMessageState.ABSENT);
        assertThat(donation.encryptedMessageRef()).isEmpty();
        assertThat(donation.redactMessage(NOW.plusSeconds(1))).isSameAs(donation);
    }

    @ParameterizedTest
    @CsvSource({"PRESENT,false", "ABSENT,true", "REDACTED,true"})
    void reconstitutionRejectsReferenceStateMismatch(DonorMessageState state, boolean hasReference) {
        assertThatThrownBy(() -> Donation.reconstitute(ID, SITE, CAMPAIGN, hasReference ? CIPHERTEXT : null,
            state, NOW, NOW)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void reconstitutionRestoresAllValidStates() {
        for (DonorMessageState state : DonorMessageState.values()) {
            Donation donation = Donation.reconstitute(ID, SITE, CAMPAIGN,
                state == DonorMessageState.PRESENT ? CIPHERTEXT : null, state, NOW, NOW.plusSeconds(1));
            assertThat(donation.messageState()).isEqualTo(state);
            assertThat(donation.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        }
    }

    @Test
    void rejectsInvalidIdentifiersReferencesAndTimes() {
        assertThatThrownBy(() -> new DonationId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.create(null, SITE, CAMPAIGN, null, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.create(ID, null, CAMPAIGN, null, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.create(ID, SITE, null, null, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.create(ID, SITE, CAMPAIGN, " ", NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.create(ID, SITE, CAMPAIGN, null, null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.reconstitute(ID, SITE, CAMPAIGN, null, null, NOW, NOW))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Donation.reconstitute(ID, SITE, CAMPAIGN, null, DonorMessageState.ABSENT, NOW, NOW.minusSeconds(1)))
            .isInstanceOf(DomainValidationException.class);
        Donation present = Donation.create(ID, SITE, CAMPAIGN, CIPHERTEXT, NOW);
        assertThatThrownBy(() -> present.redactMessage(NOW.minusSeconds(1))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> present.redactMessage(null)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nfcNormalizationPrecedesTheCodePointLimit() {
        DonorMessage message = DonorMessage.of("e\u0301".repeat(500));
        assertThat(message.value()).isEqualTo("é".repeat(500));
        assertThat(DonorMessage.of("Cafe\u0301").value()).isEqualTo("Café");
    }

    @Test
    void codePointLimitCountsSupplementaryCharactersOnce() {
        assertThat(DonorMessage.of("😀".repeat(500)).value()).isEqualTo("😀".repeat(500));
        assertThatThrownBy(() -> DonorMessage.of("😀".repeat(501))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> DonorMessage.of("a".repeat(501))).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"a\u0000b", "a\nb", "a\rb", "a\tb", "a\u007fb", "a\u0085b", "a\u200bb", "a\u202eb", "a\u2066b", "a\ufeffb", "\ud800", "\udc00", "\ud800x", "x\udc00"})
    void rejectsControlsFormatCharactersAndUnpairedSurrogates(String raw) {
        assertThatThrownBy(() -> DonorMessage.of(raw)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void markupLookingTextRemainsLiteralPlainTextWithoutInterpretation() {
        String literal = "<b>Thanks & welcome</b><script>alert('x')</script> &#x41;";
        assertThat(DonorMessage.of(literal).value()).isEqualTo(literal);
        assertThatCode(() -> DonorMessage.of("")).doesNotThrowAnyException();
        assertThat(DonorMessage.of("  Thank you  ").value()).isEqualTo("  Thank you  ");
    }

    @Test
    void diagnosticsNeverExposeMessageOrEncryptedReferenceAndValidationDoesNotEchoText() {
        String raw = "private donor message fixture";
        assertThat(DonorMessage.of(raw).toString()).contains("REDACTED").doesNotContain(raw);
        assertThat(Donation.create(ID, SITE, CAMPAIGN, CIPHERTEXT, NOW).toString()).doesNotContain(CIPHERTEXT);
        assertThatThrownBy(() -> DonorMessage.of(raw + "\n")).hasMessageNotContaining(raw);
    }
}
