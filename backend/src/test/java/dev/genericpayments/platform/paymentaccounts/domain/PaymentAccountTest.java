package dev.genericpayments.platform.paymentaccounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAccountTest {
    @Test
    void createsAnActiveAccountWithNormalizedKey() {
        PaymentAccount account = PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),
            "Demo-Account", PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX,
            "fake_123", "env:FAKE_API_KEY");

        assertThat(account.key()).isEqualTo("demo-account");
        assertThat(account.status()).isEqualTo(PaymentAccountStatus.ACTIVE);
        assertThat(account.provider()).isEqualTo(PaymentProviderType.FAKE);
    }

    @Test
    void acceptsBothSupportedProviderTypes() {
        PaymentAccount fake = account(PaymentProviderType.FAKE, "env:FAKE_KEY");
        PaymentAccount stripe = account(PaymentProviderType.STRIPE, "env:STRIPE_KEY");

        assertThat(fake.provider()).isEqualTo(PaymentProviderType.FAKE);
        assertThat(stripe.provider()).isEqualTo(PaymentProviderType.STRIPE);
    }

    @Test
    void rejectsBlankCredentialsReference() {
        assertThatThrownBy(() -> account(PaymentProviderType.FAKE, "  "))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("credentials ref");
    }

    private PaymentAccount account(PaymentProviderType provider, String credentialsRef) {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), "demo-account", provider,
            PaymentAccountMode.SANDBOX, "fake_123", credentialsRef);
    }
}
