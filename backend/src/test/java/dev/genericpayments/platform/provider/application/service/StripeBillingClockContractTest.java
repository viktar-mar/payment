package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Standalone pure class: no superclass, extension, static initialization, Spring or external resources. */
class StripeBillingClockContractTest {
    @Test void ledgerHasDedicatedOriginalClockCreationKind() {
        assertThat(java.util.Arrays.stream(ProviderOperation.Kind.values()).map(Enum::name))
            .contains("TEST_CLOCK_CREATE");
    }
}
