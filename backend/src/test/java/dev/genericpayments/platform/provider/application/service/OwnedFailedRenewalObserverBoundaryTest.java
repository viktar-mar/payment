package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalObserver;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedFailedRenewalObserverBoundaryTest {
    @Test void missingAuthenticatedBindingStopsBeforeProviderRead() {
        var h=new OwnedFailedRenewalServiceTest.Harness();
        var sdk=new StripeBillingInertSdkFixture();
        var observer=new StripeBillingOwnedFailedRenewalObserver(h.service,sdk.credentials,sdk.clients,Clock.fixed(h.utc,ZoneOffset.UTC));
        assertSafe(()->observer.observe(h.admission));
        assertThat(sdk.http.calls).isEmpty();
        assertThat(h.stopped).isTrue();
        assertThat(h.trace.stream().filter("stop"::equals)).hasSize(1);
    }
    @Test void futureClaimStopsOnceBeforeProviderIo() {
        var h=new OwnedFailedRenewalServiceTest.Harness(true);
        var sdk=new StripeBillingInertSdkFixture();
        var observer=new StripeBillingOwnedFailedRenewalObserver(h.service,sdk.credentials,sdk.clients,Clock.fixed(h.utc.minusSeconds(1),ZoneOffset.UTC));
        assertSafe(()->observer.observe(h.admission));
        assertThat(sdk.http.calls).isEmpty();
        assertThat(h.trace.stream().filter("stop"::equals)).hasSize(1);
    }
    @Test void duplicateBeginIsStoppedByServiceOnly() {
        var h=new OwnedFailedRenewalServiceTest.Harness(true);
        h.service.beginFailedRead(h.admission,java.util.UUID.randomUUID());
        var sdk=new StripeBillingInertSdkFixture();
        var observer=new StripeBillingOwnedFailedRenewalObserver(h.service,sdk.credentials,sdk.clients,Clock.fixed(h.utc,ZoneOffset.UTC));
        assertSafe(()->observer.observe(h.admission));
        assertThat(sdk.http.calls).isEmpty();
        assertThat(h.trace.stream().filter("stop"::equals)).hasSize(1);
    }
    private static void assertSafe(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{
            assertThat(e.code()).isEqualTo("FAILED_RENEWAL_UNAVAILABLE");assertThat(e.status()).isEqualTo(409);
            assertThat(e).hasMessage("Failed renewal observation unavailable").hasNoCause();});
    }
}
