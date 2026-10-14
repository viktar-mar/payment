package dev.genericpayments.platform.paymentaccounts.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.SavePaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PaymentAccountPersistenceAdapter implements LoadPaymentAccountPort, SavePaymentAccountPort,
        dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort,
        dev.genericpayments.platform.paymentaccounts.application.port.out.ListPaymentAccountsPort {
    private final PaymentAccountJpaRepository repository;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entityManager;
    private final dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountBillingGuardPort billingGuard;

    PaymentAccountPersistenceAdapter(PaymentAccountJpaRepository repository, Clock clock, jakarta.persistence.EntityManager entityManager,dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountBillingGuardPort billingGuard) {
        this.repository = repository;
        this.clock = clock;
        this.entityManager = entityManager;
        this.billingGuard = billingGuard;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public PaymentAccount save(PaymentAccount paymentAccount) {
        var current=findByIdForUpdate(paymentAccount.id());
        if(current.isPresent()&&current.get().status()==dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus.ACTIVE
            &&paymentAccount.status()!=dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus.ACTIVE)billingGuard.requireMutable(paymentAccount.id());
        PaymentAccountJpaEntity entity = repository.findById(paymentAccount.id().value())
            .map(existing -> {
                existing.update(paymentAccount);
                return existing;
            })
            .orElseGet(() -> PaymentAccountJpaEntity.from(paymentAccount, clock.instant()));
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<PaymentAccount> findById(PaymentAccountId id) {
        return repository.findById(id.value()).map(PaymentAccountJpaEntity::toDomain);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<PaymentAccount> findByIdForUpdate(PaymentAccountId id) {
        var entity=entityManager.find(PaymentAccountJpaEntity.class,id.value(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(entity!=null)entityManager.refresh(entity,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return Optional.ofNullable(entity).map(PaymentAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<PaymentAccount> findByKey(String key) {
        return repository.findByKey(key).map(PaymentAccountJpaEntity::toDomain);
    }

    @Override
    public java.util.List<PaymentAccount> findAll() {
        return repository.findAll(org.springframework.data.domain.Sort.by("key")).stream()
            .map(PaymentAccountJpaEntity::toDomain).toList();
    }
}
