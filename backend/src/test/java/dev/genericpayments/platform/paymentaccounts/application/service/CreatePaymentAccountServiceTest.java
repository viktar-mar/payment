package dev.genericpayments.platform.paymentaccounts.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.paymentaccounts.application.port.in.CreatePaymentAccountCommand;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.SavePaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreatePaymentAccountServiceTest {
    @Test
    void legacyConstructorsCannotCreateStripeAccountsWithoutVerification() {
        InMemoryPaymentAccountPort accounts = new InMemoryPaymentAccountPort();
        var stripe = new CreatePaymentAccountCommand("stripe-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");

        assertThatThrownBy(() -> new CreatePaymentAccountService(accounts).create(stripe))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new CreatePaymentAccountService(accounts, accounts).create(stripe))
            .isInstanceOf(DomainValidationException.class);
        assertThat(accounts.accounts).isEmpty();
    }

    @Test
    void refusesDuplicateNormalizedAccountKey() {
        InMemoryPaymentAccountPort accounts = new InMemoryPaymentAccountPort();
        accounts.save(account("demo-account", "env:EXISTING_KEY"));
        CreatePaymentAccountService service = new CreatePaymentAccountService(accounts);

        assertThatThrownBy(() -> service.create(command("Demo-Account", "env:NEW_KEY")))
            .isInstanceOf(DomainValidationException.class)
            .hasMessage("payment account key already exists: demo-account");
    }

    @Test
    void savesNewAccountAndPreservesCredentialsReference() {
        InMemoryPaymentAccountPort accounts = new InMemoryPaymentAccountPort();
        CreatePaymentAccountService service = new CreatePaymentAccountService(accounts);

        PaymentAccount created = service.create(command("Demo-Account", "vault://payments/demo"));

        assertThat(created.key()).isEqualTo("demo-account");
        assertThat(created.credentialsRef()).isEqualTo("vault://payments/demo");
        assertThat(accounts.findById(created.id())).contains(created);
    }

    private CreatePaymentAccountCommand command(String key, String credentialsRef) {
        return new CreatePaymentAccountCommand(key, PaymentProviderType.FAKE,
            PaymentAccountMode.SANDBOX, "fake_123", credentialsRef);
    }

    private PaymentAccount account(String key, String credentialsRef) {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), key,
            PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX, "fake_123", credentialsRef);
    }

    private static final class InMemoryPaymentAccountPort
        implements LoadPaymentAccountPort, SavePaymentAccountPort {
        private final List<PaymentAccount> accounts = new ArrayList<>();

        @Override public Optional<PaymentAccount> findById(PaymentAccountId id) {
            return accounts.stream().filter(account -> account.id().equals(id)).findFirst();
        }

        @Override public Optional<PaymentAccount> findByKey(String key) {
            return accounts.stream().filter(account -> account.key().equals(key)).findFirst();
        }

        @Override public PaymentAccount save(PaymentAccount account) {
            accounts.add(account);
            return account;
        }
    }
}
