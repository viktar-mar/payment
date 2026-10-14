package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface CustomerPaymentHistoryCursorPort {
    String encode(Position position);
    Position decode(String cursor);

    record Position(SiteId siteId,UUID customerId,String queryShape,int pageSize,Instant createdAt,PaymentId paymentId) {
        public Position {
            Objects.requireNonNull(siteId,"site id"); Objects.requireNonNull(customerId,"customer id");
            Objects.requireNonNull(queryShape,"query shape"); Objects.requireNonNull(createdAt,"created at");
            Objects.requireNonNull(paymentId,"payment id");
            if(queryShape.isBlank()||queryShape.length()>128||pageSize<1||pageSize>100
                    ||createdAt.getNano()%1_000!=0) throw new IllegalArgumentException("Invalid Customer history cursor position");
        }
    }
}
