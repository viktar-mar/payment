package dev.genericpayments.platform.paymentaccounts.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentAccountJpaRepository extends JpaRepository<PaymentAccountJpaEntity, UUID> {
    Optional<PaymentAccountJpaEntity> findByKey(String key);
}
