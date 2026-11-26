package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.model.ClockOwner;
import dev.genericpayments.platform.catalog.domain.PriceId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.*;

@Import(OwnedOptionalTrialPublication.Configuration.class)
class OwnedOptionalTrialAdmissionIntegrationTest extends OwnedOptionalTrialAdmissionFixture {
    @Autowired OwnedOptionalTrialAdmission admission;

    @Test void bindsFreshOwnerAndReservesOnlyTheOriginalTrial() {
        var request = admissionRequest();
        long earliest = Instant.now().getEpochSecond();
        queueOpening(() -> {
            assertThatThrownBy(() -> admission.admit(request)).isInstanceOf(IllegalStateException.class);
            assertThat(http.calls).hasSize(1);
        });

        var admitted = admission.admit(request);

        assertThat(admitted.account()).isEqualTo(billing);
        assertThat(admitted.frozen()).isBetween(earliest,Instant.now().getEpochSecond());
        assertThat(admitted.trialEnd()).isEqualTo(admitted.frozen()+604800L);
        assertThat(admitted.scope().run()).isEqualTo(run);
        assertThat(clocks.owner(billing.id(),site,customer)).get().extracting(ClockOwner::intent)
            .isEqualTo(admitted.scope().clockIntent());
        assertThat(jdbc.queryForList("select status from subscription_operations",String.class)).containsExactly("RESERVED");
        assertThat(proofs.trial(billing.id(),admitted.scope().subscription()).orElseThrow().requestedEnd())
            .isEqualTo(admitted.trialEnd());
        assertThat(http.calls.stream().filter(call -> call.method().name().equals("POST"))
            .map(call -> call.url().getPath())).containsExactly("/v1/test_helpers/test_clocks","/v1/customers");
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(http.replies).isEmpty();

        int calls = http.calls.size();
        assertThatThrownBy(() -> admission.admit(request)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_clock_intents")).isOne();
    }

    @Test void priorOwnedClockRemainsInPlaceAndIsNeverResumed() {
        var original = clocks.admitFresh(billing,site,customer,run,StripeBillingTestClockAdapter.API_VERSION,
            FROZEN,Instant.now());
        createReady(original);
        http.calls.clear();
        assertThatThrownBy(() -> admission.admit(admissionRequest())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertThat(clocks.owner(billing.id(),site,customer)).contains(original);
        assertThat(count("subscriptions")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"wrong run","wrong origin","wrong price","no consent"})
    void preflightRefusesUnsuitableInputsBeforeAnyProviderWork(String fault) {
        var requestedPrice = price;
        if (fault.equals("wrong price")) {
            var product = jdbc.queryForObject("select product_id from prices where id=?", UUID.class, price.value());
            requestedPrice = new PriceId(UUID.randomUUID());
            provisionPrice(requestedPrice, product, "other-monthly", 2100, "price_other");
        }
        var request = admissionRequest(fault.equals("wrong run") ? UUID.randomUUID() : run,
            fault.equals("wrong origin") ? "https://other.test" : ORIGIN, !fault.equals("no consent"), requestedPrice);
        assertThatThrownBy(() -> admission.admit(request)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertThat(count("stripe_billing_clock_intents")).isZero();
        assertThat(count("subscription_operations")).isZero();
    }

    @Test void incompleteOptionalTrialMigrationsRefuseBeforeOwningAClock() {
        assertThat(jdbc.update("update owned_optional_trial_schema_history set success=false where version='7'")).isOne();
        try {
            assertThatThrownBy(() -> admission.admit(admissionRequest())).isInstanceOf(IllegalStateException.class);
            assertThat(http.calls).isEmpty();
            assertThat(count("stripe_billing_clock_intents")).isZero();
        } finally {
            jdbc.update("update owned_optional_trial_schema_history set success=true where version='7'");
        }
    }

}
