package dev.genericpayments.platform.paymentaccounts.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.in.CreatePaymentAccountCommand;
import dev.genericpayments.platform.paymentaccounts.application.port.in.CreatePaymentAccountUseCase;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.SavePaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

public final class CreatePaymentAccountService implements CreatePaymentAccountUseCase {
    private final LoadPaymentAccountPort loadPaymentAccounts;
    private final SavePaymentAccountPort savePaymentAccounts;

    public CreatePaymentAccountService(LoadPaymentAccountPort loadPaymentAccounts,
                                       SavePaymentAccountPort savePaymentAccounts) {
        this.loadPaymentAccounts = Objects.requireNonNull(loadPaymentAccounts);
        this.savePaymentAccounts = Objects.requireNonNull(savePaymentAccounts);
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
        return savePaymentAccounts.save(account);
    }
}
