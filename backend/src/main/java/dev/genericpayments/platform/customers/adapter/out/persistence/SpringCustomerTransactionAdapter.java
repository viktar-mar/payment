package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.port.out.CustomerTransactionPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class SpringCustomerTransactionAdapter implements CustomerTransactionPort {
    private final TransactionTemplate transactions;

    public SpringCustomerTransactionAdapter(PlatformTransactionManager transactionManager) {
        this.transactions=new TransactionTemplate(transactionManager);
    }

    @Override public <T> T required(Supplier<T> work) {
        Objects.requireNonNull(work,"transaction work");
        return transactions.execute(status -> work.get());
    }
}
