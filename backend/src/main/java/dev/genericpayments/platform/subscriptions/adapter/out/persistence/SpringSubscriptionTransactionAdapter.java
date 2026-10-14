package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Component
public final class SpringSubscriptionTransactionAdapter implements SubscriptionTransactionPort {
    private final TransactionTemplate required,newTransaction;
    public SpringSubscriptionTransactionAdapter(PlatformTransactionManager manager){required=new TransactionTemplate(manager);newTransaction=new TransactionTemplate(manager);newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    public <T>T required(Supplier<T> work){Objects.requireNonNull(work);return required.execute(status->work.get());}
    public <T>T requiresNew(Supplier<T> work){Objects.requireNonNull(work);return newTransaction.execute(status->work.get());}
    public boolean active(){return TransactionSynchronizationManager.isActualTransactionActive();}
}
