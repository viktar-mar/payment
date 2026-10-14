package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateSubscriptionServiceTest {
    @Test void legacyScopeSetDoesNotGrantSubscriptionManagement() {
        var legacy=Set.of(CustomerSessionScope.CUSTOMER_READ,CustomerSessionScope.CHECKOUT_CREATE,CustomerSessionScope.IDENTITY_LINK);
        assertThatCode(()->CustomerSessionScope.valueOf("SUBSCRIPTION_MANAGE")).doesNotThrowAnyException();
        assertThat(legacy).doesNotContain(CustomerSessionScope.valueOf("SUBSCRIPTION_MANAGE"));
        assertThat(PartnerScope.parse("subscriptions:read").name()).isEqualTo("SUBSCRIPTIONS_READ");
    }
    @Test void transportFailureCanRemainUnknownWithoutAResourceReference() {
        assertThatCode(()->new Reply(Outcome.UNKNOWN,null,null)).doesNotThrowAnyException();
        assertThat(new Reply(Outcome.UNKNOWN,null,null).reference()).isNull();
        assertThat(new Reply(Outcome.DECLINED,null,null).reference()).isNull();
        assertThatThrownBy(()->new Reply(Outcome.ACCEPTED,null,null)).isInstanceOf(RuntimeException.class);
    }
}
