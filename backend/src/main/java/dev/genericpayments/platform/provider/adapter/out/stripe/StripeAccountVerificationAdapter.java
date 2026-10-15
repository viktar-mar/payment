package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.exception.StripeException;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class StripeAccountVerificationAdapter implements PaymentAccountVerificationPort {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;

    public StripeAccountVerificationAdapter(StripeCredentialResolver credentials, StripeClientFactory clients) {
        this.credentials = Objects.requireNonNull(credentials);
        this.clients = Objects.requireNonNull(clients);
    }

    /** Suspend the Admin save/audit transaction for the read-only identity check. */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void verify(PaymentAccount account) {
        Objects.requireNonNull(account);
        if (account.provider() == PaymentProviderType.FAKE) return;
        var bound = credentials.resolve(account);
        try {
            var current = clients.create(bound).v1().accounts().retrieveCurrent();
            if (!account.externalAccountId().equals(current.getId())) {
                throw new DomainValidationException("Stripe Sandbox account identity does not match");
            }
        } catch (StripeException failure) {
            // SDK exceptions can contain raw responses and credentials; do not retain a cause.
            throw new DomainValidationException("Stripe Sandbox account verification failed");
        }
    }
}
