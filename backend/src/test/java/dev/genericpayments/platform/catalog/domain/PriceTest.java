package dev.genericpayments.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriceTest {
    @Test
    void subscriptionRequiresIntervalAndNonNegativeTrial() {
        Money money = Money.of(1999, "EUR");
        Price price = Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-monthly"), 1,
            PriceType.SUBSCRIPTION, money, BillingInterval.MONTH, 14,
            TaxMode.INCLUSIVE, "txcd_10000000");

        assertThat(price.status()).isEqualTo(PriceStatus.DRAFT);
        assertThat(price.money().minorUnits()).isEqualTo(1999);
    }

    @Test
    void subscriptionPriceRejectsNegativeTrialDays() {
        assertThatThrownBy(() -> Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-monthly"), 1, PriceType.SUBSCRIPTION,
            Money.of(1999, "EUR"), BillingInterval.MONTH, -1, TaxMode.NONE, null))
            .isInstanceOf(DomainValidationException.class)
            .hasMessage("trial days must not be negative");
    }

    @Test
    void publishedPriceCannotBeChanged() {
        Price published = validDraft().publish(Instant.parse("2026-09-12T12:00:00Z"));

        assertThatThrownBy(() -> published.changeAmount(Money.of(2999, "EUR")))
            .isInstanceOf(DomainValidationException.class)
            .hasMessage("published price is immutable");
    }

    @Test
    void oneTimePriceCannotHaveBillingInterval() {
        assertThatThrownBy(() -> Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-once"), 1, PriceType.ONE_TIME,
            Money.of(1999, "EUR"), BillingInterval.MONTH, 0, TaxMode.NONE, null))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void subscriptionPriceRequiresBillingInterval() {
        assertThatThrownBy(() -> Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-monthly"), 1, PriceType.SUBSCRIPTION,
            Money.of(1999, "EUR"), null, 0, TaxMode.NONE, null))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void oneTimePriceCannotHaveTrialDays() {
        assertThatThrownBy(() -> Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-once"), 1, PriceType.ONE_TIME,
            Money.of(1999, "EUR"), null, 1, TaxMode.NONE, null))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void legacyDraftDefaultsToOptionalTrialMethodWithoutInventingTrialDays() {
        Price price = validDraft();

        assertThat(price.trialPaymentMethodPolicy()).isEqualTo(TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
        assertThat(price.trialDays()).isZero();
    }

    @Test
    void requiredTrialMethodSurvivesAmountEditAndPublication() {
        Price price = validDraft().withTrialPaymentMethodPolicy(TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL)
            .changeAmount(Money.of(2499, "EUR")).publish(Instant.parse("2026-10-11T10:00:00Z"));

        assertThat(price.trialPaymentMethodPolicy()).isEqualTo(TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
        assertThat(price.money()).isEqualTo(Money.of(2499, "EUR"));
    }

    @Test
    void publishedTrialPolicyCannotBeChanged() {
        Price price = validDraft().withTrialPaymentMethodPolicy(TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL)
            .publish(Instant.parse("2026-10-11T10:00:00Z"));

        assertThatThrownBy(() -> price.withTrialPaymentMethodPolicy(TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void oneTimeLegacyDefaultDoesNotGrantATrial() {
        Price price = Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-once"), 1, PriceType.ONE_TIME,
            Money.of(1999, "EUR"), null, 0, TaxMode.NONE, null);

        assertThat(price.trialDays()).isZero();
        assertThat(price.billingInterval()).isNull();
        assertThat(price.trialPaymentMethodPolicy()).isEqualTo(TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
    }

    private Price validDraft() {
        return Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
            new ProductId(UUID.randomUUID()), PriceKey.of("pro-monthly"), 1,
            PriceType.SUBSCRIPTION, Money.of(1999, "EUR"), BillingInterval.MONTH, 0,
            TaxMode.NONE, null);
    }
}
