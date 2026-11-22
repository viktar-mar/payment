package dev.genericpayments.platform.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OwnedOptionalTrialSchemaTest {
    @Test void optionalTrialSqlIsAvailableOnlyAtTheExplicitTestLocation() {
        var resources = getClass().getClassLoader();
        assertThat(resources.getResource("db/owned-optional-trial/V1__owned_optional_trial_create.sql")).isNotNull();
        assertThat(resources.getResource("db/migration/V43__stripe_billing_owned_optional_trial_create.sql")).isNull();
    }
}
