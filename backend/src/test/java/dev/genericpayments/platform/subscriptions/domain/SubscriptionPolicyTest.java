package dev.genericpayments.platform.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionPolicyTest {
    @Test
    void defaultsDoNotEnableSubscriptionsOrPortal() {
        var policy = SubscriptionPolicy.defaults(site());

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.graceDays()).isEqualTo(3);
        assertThat(policy.portalEnabled()).isFalse();
        assertThat(policy.allowImmediateCancellation()).isFalse();
        assertThat(policy.version()).isZero();
        assertThat(policy.portalReturnUrls()).isEmpty();
    }

    @Test
    void negativeGraceIsRejected() {
        assertThatThrownBy(() -> new SubscriptionPolicy(site(), 1, true, -1, false, false, false, Set.of()))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void negativeVersionIsRejected() {
        assertThatThrownBy(() -> new SubscriptionPolicy(site(), -1, true, 3, false, false, false, Set.of()))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void returnUrlAllowlistIsAnImmutableSnapshot() {
        var configured = new HashSet<>(Set.of("https://shop.example/billing"));
        var policy = new SubscriptionPolicy(site(), 1, true, 0, false, true, false, configured);
        configured.clear();

        assertThat(policy.portalReturnUrls()).containsExactly("https://shop.example/billing");
        assertThatThrownBy(() -> policy.portalReturnUrls().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void transitionCannotTargetItsOwnPrice() {
        var price = new PriceId(UUID.randomUUID());

        assertThatThrownBy(() -> new PlanTransition(site(), new ProductId(UUID.randomUUID()), price, price,
            PlanTransition.Direction.UPGRADE, true, 1)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void transitionRejectsNegativePolicyVersion() {
        assertThatThrownBy(() -> new PlanTransition(site(), new ProductId(UUID.randomUUID()),
            new PriceId(UUID.randomUUID()), new PriceId(UUID.randomUUID()), PlanTransition.Direction.LATERAL,
            false, -1)).isInstanceOf(DomainValidationException.class);
    }

    private static SiteId site() {
        return new SiteId(UUID.randomUUID());
    }
}
