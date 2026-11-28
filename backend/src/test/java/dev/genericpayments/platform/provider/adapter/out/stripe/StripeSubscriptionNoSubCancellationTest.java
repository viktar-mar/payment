package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase.Mode;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/** Closed mock/Supplier/owned loopback fixture; SQL atomicity and queue order remain hosted tests. */
class StripeSubscriptionNoSubCancellationTest {
    @Test void originalOwnerMustCloseBeforeTheUniqueUnresolvedCancelClaim() throws Exception {
        try (var c = new StripeSubscriptionCancellationTest.AbandonCase()) {
            var store = enforceUniqueOwner(c);
            var result = c.transaction.abandon(c.context,c.subscription.id(),Mode.PERIOD_END,c.candidate());
            assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            assertThat(c.operations.get(c.original.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
            assertThat(c.operations.get(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            assertThat(c.eligibility.reservedByOperation()).isNull();
            assertThat(c.events).hasSize(1);
            verify(store,times(1)).claim(any());
            assertThat(c.base.fixture.graph.http.calls()).isEmpty();
        }
    }

    @Test void exactReplayAfterTrialReleaseRevalidatesAuthorityWithoutClaimOrClosureAgain() throws Exception {
        try (var c = new StripeSubscriptionCancellationTest.AbandonCase()) {
            var store = enforceUniqueOwner(c);
            var candidate = c.candidate();
            var first = c.transaction.abandon(c.context,c.subscription.id(),Mode.PERIOD_END,candidate);
            var original = c.operations.get(c.original.id());
            var closed = c.subscription;
            var eligibility = c.eligibility;
            assertThat(c.transaction.abandon(c.context,c.subscription.id(),Mode.PERIOD_END,candidate)).isEqualTo(first);
            assertThat(c.operations).hasSize(2);
            assertThat(c.operations.get(c.original.id())).isEqualTo(original);
            assertThat(c.subscription).isEqualTo(closed);
            assertThat(c.eligibility).isEqualTo(eligibility);
            assertThat(c.events).hasSize(1);
            verify(store,times(1)).claim(any());
            verify((SubscriptionAuthorityPort)ReflectionTestUtils.getField(c.transaction,"authority"),times(2)).revalidate(c.context,true);
        }
    }

    @Test void sameKeyWithChangedModeCannotReuseTheDurableAbandonment() throws Exception {
        try (var c = new StripeSubscriptionCancellationTest.AbandonCase()) {
            var store = enforceUniqueOwner(c);
            var candidate = c.candidate();
            c.transaction.abandon(c.context,c.subscription.id(),Mode.PERIOD_END,candidate);
            var closed = c.subscription;
            var original = c.operations.get(c.original.id());
            assertThatThrownBy(() -> c.transaction.abandon(c.context,c.subscription.id(),Mode.IMMEDIATE,candidate))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e -> assertThat(e.code()).isEqualTo("SUBSCRIPTION_STATE_CONFLICT"));
            assertThat(c.subscription).isEqualTo(closed);
            assertThat(c.operations.get(c.original.id())).isEqualTo(original);
            assertThat(c.events).hasSize(1);
            verify(store,times(1)).claim(any());
        }
    }

    @Test void unknownNativeCreateRefusesBeforeOriginalClosureOrCancelClaim() throws Exception {
        try (var c = new StripeSubscriptionCancellationTest.AbandonCase()) {
            var store = enforceUniqueOwner(c);
            var steps = c.base.fixture.steps;
            var reserved = steps.reserve(c.base.fixture.account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,c.original.id(),c.original.providerOperationKey(),"original-body");
            var live = steps.claim(reserved.id(),"original-owner",c.base.now,Duration.ofSeconds(60)).orElseThrow();
            var unknown = steps.unknown(live.id(),"original-owner",live.version());
            assertThatThrownBy(() -> c.transaction.abandon(c.context,c.subscription.id(),Mode.PERIOD_END,c.candidate()))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e -> assertThat(e.code()).isEqualTo("SUBSCRIPTION_STATE_CONFLICT"));
            assertThat(c.operations).containsOnlyKeys(c.original.id());
            assertThat(c.operations.get(c.original.id())).isEqualTo(c.original);
            assertThat(c.eligibility.reservedByOperation()).isEqualTo(c.original.id());
            assertThat(steps.findById(unknown.id())).contains(unknown);
            assertThat(c.events).isEmpty();
            verify(store,never()).claim(any());
        }
    }

    private SubscriptionOperationStore enforceUniqueOwner(StripeSubscriptionCancellationTest.AbandonCase c) {
        var store = (SubscriptionOperationStore)ReflectionTestUtils.getField(c.transaction,"operations");
        when(store.findByRequest(any(),anyString(),any(),anyString())).thenAnswer(i -> c.operations.values().stream()
            .filter(o -> o.siteId().equals(i.getArgument(0)) && o.authorityScope().equals(i.getArgument(1))
                && o.kind()==i.getArgument(2) && o.keyDigest().equals(i.getArgument(3))).findFirst());
        doAnswer(i -> {
            SubscriptionOperation proposed = i.getArgument(0);
            Optional<SubscriptionOperation> existing = c.operations.values().stream().filter(o -> o.siteId().equals(proposed.siteId())
                && o.authorityScope().equals(proposed.authorityScope()) && o.kind()==proposed.kind() && o.keyDigest().equals(proposed.keyDigest())).findFirst();
            if(existing.isPresent()) return new SubscriptionOperationStore.Claim(existing.orElseThrow(),false);
            if(c.operations.values().stream().anyMatch(o -> proposed.subscriptionId().equals(o.subscriptionId())
                && List.of(SubscriptionOperation.Status.RESERVED,SubscriptionOperation.Status.PROCESSING,SubscriptionOperation.Status.REQUIRES_ACTION,SubscriptionOperation.Status.UNKNOWN).contains(o.status())
                && o.kind()!=SubscriptionOperation.Kind.PORTAL && o.kind()!=SubscriptionOperation.Kind.RECONCILE))
                throw new DataIntegrityViolationException("uq_subscription_unresolved_operation");
            c.operations.put(proposed.id(),proposed);
            return new SubscriptionOperationStore.Claim(proposed,true);
        }).when(store).claim(any());
        return store;
    }
}
