package dev.genericpayments.platform.paymentaccounts.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.in.CreatePaymentAccountCommand;
import dev.genericpayments.platform.paymentaccounts.application.port.in.CreatePaymentAccountUseCase;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.SavePaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

public final class CreatePaymentAccountService implements CreatePaymentAccountUseCase {
    private final LoadPaymentAccountPort loadPaymentAccounts;
    private final SavePaymentAccountPort savePaymentAccounts;
    private final PaymentAccountVerificationPort verification;

    public CreatePaymentAccountService(LoadPaymentAccountPort loadPaymentAccounts,
                                       SavePaymentAccountPort savePaymentAccounts,
                                       PaymentAccountVerificationPort verification) {
        this.loadPaymentAccounts = Objects.requireNonNull(loadPaymentAccounts);
        this.savePaymentAccounts = Objects.requireNonNull(savePaymentAccounts);
        this.verification = Objects.requireNonNull(verification);
    }

    public CreatePaymentAccountService(LoadPaymentAccountPort loadPaymentAccounts,
                                       SavePaymentAccountPort savePaymentAccounts) {
        this(loadPaymentAccounts, savePaymentAccounts, account -> {
            if (account.provider() != PaymentProviderType.FAKE) {
                throw new DomainValidationException("payment account verification is required");
            }
        });
    }

    public <T extends LoadPaymentAccountPort & SavePaymentAccountPort> CreatePaymentAccountService(T accounts) {
        this(accounts, accounts);
    }

    @Override
    public PaymentAccount create(CreatePaymentAccountCommand command) {
        Objects.requireNonNull(command, "command");
        String key = PaymentAccount.normalizeKey(command.key());
        if (loadPaymentAccounts.findByKey(key).isPresent()) {
            throw new DomainValidationException("payment account key already exists: " + key);
        }
        PaymentAccount account = PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), key,
            command.provider(), command.mode(), command.externalAccountId(), command.credentialsRef());
        verification.verify(account);
        return savePaymentAccounts.save(account);
    }
}
