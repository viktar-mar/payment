package dev.genericpayments.platform.customers.application.model;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CustomerSessionContextTest {
    @Test
    void operationSpecificScopeCheckAllowsOnlyAnExplicitlyGrantedCapability() {
        var context=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),new SiteId(UUID.randomUUID()),
            new CustomerId(UUID.randomUUID()),"https://shop.example.test",Set.of(CustomerSessionScope.CHECKOUT_CREATE),
            Instant.parse("2026-09-13T10:45:30Z"));

        assertThatCode(() -> context.requireScope(CustomerSessionScope.CHECKOUT_CREATE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> context.requireScope(CustomerSessionScope.CUSTOMER_READ))
            .isInstanceOfSatisfying(PaymentApplicationException.class,error -> {
                assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED");
                assertThat(error.status()).isEqualTo(401);
                assertThat(error).hasMessage("Authentication failed").hasNoCause();
            });
    }
}
