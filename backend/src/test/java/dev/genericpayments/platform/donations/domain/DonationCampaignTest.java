package dev.genericpayments.platform.donations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DonationCampaignTest {
    private static final DonationCampaignId ID = new DonationCampaignId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final SiteId SITE = new SiteId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    @Test
    void draftNormalizesKeyAndCurrencyAndDefensivelyPreservesSuggestedOrder() {
        List<Long> suggestions = new ArrayList<>(List.of(1000L, 500L));
        DonationCampaign campaign = campaign("eur", suggestions, true);
        suggestions.clear();
        assertThat(campaign.campaignKey()).isEqualTo(new DonationCampaignKey("support-us"));
        assertThat(campaign.currency()).isEqualTo("EUR");
        assertThat(campaign.suggestedAmounts()).containsExactly(1000L, 500L);
        assertThatThrownBy(() -> campaign.suggestedAmounts().add(700L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign.activatedOnce()).isFalse();
        assertThat(campaign.version()).isZero();
        assertThat(campaign.createdAt()).isEqualTo(NOW);
        assertThat(campaign.updatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "-abc", "has space", "bad_key", "abc/def", "abc\n"})
    void rejectsUnsafeKeys(String key) {
        assertThatThrownBy(() -> new DonationCampaignKey(key)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void keyUsesSiteKeyLengthBoundaries() {
        assertThat(new DonationCampaignKey("ABC").value()).isEqualTo("abc");
        assertThatCode(() -> new DonationCampaignKey("a".repeat(63))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new DonationCampaignKey("a".repeat(64))).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ZZZ", "EU", "EURO", " EUR", "€"})
    void rejectsNonIsoCurrency(String currency) {
        assertThatThrownBy(() -> campaign(currency, List.of(500L), true)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,2000", "-1,2000", "100,0", "100,-1", "2000,100"})
    void rejectsInvalidAmountBounds(long min, long max) {
        assertThatThrownBy(() -> DonationCampaign.draft(ID, SITE, new DonationCampaignKey("support-us"),
            "Support", "Purpose", "EUR", List.of(), min, max, true, true, NOW))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsDuplicateOutOfRangeNullAndUnusableSuggestedOnlyAmounts() {
        for (List<Long> amounts : List.of(List.of(500L, 500L), List.of(99L), List.of(2001L), Arrays.asList((Long) null))) {
            assertThatThrownBy(() -> campaign("EUR", amounts, true)).isInstanceOf(DomainValidationException.class);
        }
        assertThatThrownBy(() -> campaign("EUR", null, true)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> campaign("EUR", List.of(), false)).isInstanceOf(DomainValidationException.class);
        assertThatCode(() -> campaign("EUR", List.of(), true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {100, 750, 2000})
    void customAmountsAcceptInclusiveBounds(long amount) {
        assertThatCode(() -> campaign("EUR", List.of(500L), true).validateAmount(amount)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 99, 2001, Long.MAX_VALUE})
    void rejectsAmountsOutsideCampaignRange(long amount) {
        assertThatThrownBy(() -> campaign("EUR", List.of(500L), true).validateAmount(amount))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void suggestedOnlyAcceptsOnlyConfiguredSuggestions() {
        DonationCampaign campaign = campaign("EUR", List.of(500L, 1000L), false);
        assertThatCode(() -> campaign.validateAmount(500)).doesNotThrowAnyException();
        assertThatCode(() -> campaign.validateAmount(1000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> campaign.validateAmount(750)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void checkoutRequiresActiveCampaignAndAllowedIdentityContext() {
        DonationCampaign draft = campaign("EUR", List.of(500L), true);
        assertThatThrownBy(draft::requireCheckoutAllowed).isInstanceOf(DomainValidationException.class);
        DonationCampaign active = draft.activate(NOW.plusSeconds(1));
        assertThatCode(active::requireCheckoutAllowed).doesNotThrowAnyException();
        assertThatThrownBy(() -> active.pause(NOW.plusSeconds(2)).requireCheckoutAllowed()).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> active.archive(NOW.plusSeconds(2)).requireCheckoutAllowed()).isInstanceOf(DomainValidationException.class);
        assertThatCode(() -> active.validateAnonymousAllowed(false)).doesNotThrowAnyException();
        DonationCampaign identifiedOnly = active.updateConfiguration("Support", "Purpose", "EUR", List.of(500L),
            100, 2000, true, false, NOW.plusSeconds(2));
        assertThatThrownBy(() -> identifiedOnly.validateAnonymousAllowed(true)).isInstanceOf(DomainValidationException.class);
        assertThatCode(() -> identifiedOnly.validateAnonymousAllowed(false)).doesNotThrowAnyException();
    }

    @Test
    void lifecyclePreservesOriginalAndResumesPausedCampaign() {
        DonationCampaign draft = campaign("EUR", List.of(500L), true);
        DonationCampaign active = draft.activate(NOW.plusSeconds(1));
        DonationCampaign paused = active.pause(NOW.plusSeconds(2));
        DonationCampaign resumed = paused.activate(NOW.plusSeconds(3));
        assertThat(draft.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(active.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(paused.status()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(resumed.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(resumed.activatedOnce()).isTrue();
        assertThat(resumed.version()).isEqualTo(3);
        assertThat(resumed.id()).isEqualTo(ID);
        assertThat(resumed.siteId()).isEqualTo(SITE);
        assertThat(resumed.createdAt()).isEqualTo(NOW);
        assertThat(resumed.updatedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThatThrownBy(() -> draft.pause(NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> active.activate(NOW.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> paused.pause(NOW.plusSeconds(3))).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "ACTIVE", "PAUSED"})
    void archiveIsAllowedFromEveryNonArchivedStateAndIsTerminal(CampaignStatus state) {
        DonationCampaign original = restored(state, state != CampaignStatus.DRAFT, 7, NOW);
        DonationCampaign archived = original.archive(NOW.plusSeconds(1));
        assertThat(archived.status()).isEqualTo(CampaignStatus.ARCHIVED);
        assertThat(archived.version()).isEqualTo(8);
        assertThat(archived.activatedOnce()).isEqualTo(state != CampaignStatus.DRAFT);
        assertThatThrownBy(() -> archived.activate(NOW.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> archived.pause(NOW.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> archived.archive(NOW.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> archived.updateConfiguration("Changed", "Purpose", "EUR", List.of(500L),
            100, 2000, true, true, NOW.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void draftCanChangeCurrencyButFirstActivationLocksItThroughPauseAndReconstitution() {
        DonationCampaign draft = campaign("EUR", List.of(500L), true);
        DonationCampaign changed = draft.updateConfiguration("New name", "New purpose", "usd", List.of(800L, 400L),
            200, 3000, false, false, NOW.plusSeconds(1));
        assertThat(changed.currency()).isEqualTo("USD");
        assertThat(changed.displayName()).isEqualTo("New name");
        assertThat(changed.purposeDescription()).isEqualTo("New purpose");
        assertThat(changed.suggestedAmounts()).containsExactly(800L, 400L);
        assertThat(changed.minimumAmount()).isEqualTo(200);
        assertThat(changed.maximumAmount()).isEqualTo(3000);
        assertThat(changed.customAmountAllowed()).isFalse();
        assertThat(changed.anonymousAllowed()).isFalse();
        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.activatedOnce()).isFalse();
        assertThat(draft.currency()).isEqualTo("EUR");
        for (DonationCampaign locked : List.of(changed.activate(NOW.plusSeconds(2)),
                changed.activate(NOW.plusSeconds(2)).pause(NOW.plusSeconds(3)),
                restored(CampaignStatus.PAUSED, true, 12, NOW))) {
            String replacement = locked.currency().equals("EUR") ? "USD" : "EUR";
            assertThatThrownBy(() -> locked.updateConfiguration("Support", "Purpose", replacement, List.of(500L),
                100, 2000, true, true, NOW.plusSeconds(4))).isInstanceOf(DomainValidationException.class);
            assertThat(locked.updateConfiguration("Support", "Purpose", locked.currency().toLowerCase(), List.of(500L),
                100, 2000, true, true, NOW.plusSeconds(4)).activatedOnce()).isTrue();
        }
    }

    @Test
    void capturedSnapshotRemainsIndependentOfLaterConfigurationAndLifecycle() {
        DonationCampaign active = campaign("EUR", List.of(500L), true).activate(NOW.plusSeconds(1));
        DonationCampaignSnapshot snapshot = active.snapshot();
        DonationCampaign changed = active.updateConfiguration("New", null, "EUR", List.of(1000L),
            100, 2000, false, false, NOW.plusSeconds(2)).pause(NOW.plusSeconds(3));
        assertThat(snapshot.campaignId()).isEqualTo(ID);
        assertThat(snapshot.campaignKey()).isEqualTo(new DonationCampaignKey("support-us"));
        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.currency()).isEqualTo("EUR");
        assertThat(changed.snapshot().version()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidReconstitutedLifecycleVersionAndTimes() {
        assertThatThrownBy(() -> restored(CampaignStatus.DRAFT, true, 0, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> restored(CampaignStatus.ACTIVE, false, 0, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> restored(CampaignStatus.PAUSED, false, 0, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> restored(CampaignStatus.DRAFT, false, -1, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> restored(CampaignStatus.DRAFT, false, 0, NOW.minusSeconds(1))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> restored(CampaignStatus.ACTIVE, true, Long.MAX_VALUE, NOW).pause(NOW))
            .isInstanceOf(DomainValidationException.class);
        DonationCampaign active = campaign("EUR", List.of(500L), true).activate(NOW.plusSeconds(1));
        assertThatThrownBy(() -> active.pause(NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> active.archive(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> active.updateConfiguration("Support", "Purpose", "EUR", List.of(500L),
            100, 2000, true, true, NOW)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingIdentityDisplayNameAndSnapshotFields() {
        assertThatThrownBy(() -> new DonationCampaignId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> DonationCampaign.draft(null, SITE, new DonationCampaignKey("support-us"), "Support", null,
            "EUR", List.of(), 100, 2000, true, true, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> DonationCampaign.draft(ID, null, new DonationCampaignKey("support-us"), "Support", null,
            "EUR", List.of(), 100, 2000, true, true, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> DonationCampaign.draft(ID, SITE, null, "Support", null,
            "EUR", List.of(), 100, 2000, true, true, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> DonationCampaign.draft(ID, SITE, new DonationCampaignKey("support-us"), " ", null,
            "EUR", List.of(), 100, 2000, true, true, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> DonationCampaign.draft(ID, SITE, new DonationCampaignKey("support-us"), "Support", null,
            "EUR", List.of(), 100, 2000, true, true, null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DonationCampaignSnapshot(null, new DonationCampaignKey("support-us"), 0, "EUR"))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DonationCampaignSnapshot(ID, null, 0, "EUR")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DonationCampaignSnapshot(ID, new DonationCampaignKey("support-us"), -1, "EUR"))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DonationCampaignSnapshot(ID, new DonationCampaignKey("support-us"), 0, "ZZZ"))
            .isInstanceOf(DomainValidationException.class);
    }

    private DonationCampaign campaign(String currency, List<Long> amounts, boolean customAllowed) {
        return DonationCampaign.draft(ID, SITE, new DonationCampaignKey("Support-Us"), "Support", "Purpose",
            currency, amounts, 100, 2000, customAllowed, true, NOW);
    }

    private DonationCampaign restored(CampaignStatus status, boolean activatedOnce, long version, Instant updatedAt) {
        return DonationCampaign.reconstitute(ID, SITE, new DonationCampaignKey("support-us"), "Support", "Purpose",
            "EUR", List.of(500L), 100, 2000, true, true, status, activatedOnce, version, NOW, updatedAt);
    }
}
