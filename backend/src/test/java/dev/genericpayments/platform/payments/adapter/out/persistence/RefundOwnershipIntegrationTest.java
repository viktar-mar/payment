package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.port.out.LoadRefundOwnershipPort;
import dev.genericpayments.platform.payments.application.port.out.SaveRefundPort;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.catalog.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundOwnershipIntegrationTest extends ProviderTestFixture {
    @Autowired LoadRefundOwnershipPort ownership;
    @Autowired SaveRefundPort refunds;

    @Test void resolvesExactPersistedOwnershipWithoutProjectionState() {
        var p=save.save(payment().succeeded(INITIAL_TIME));var id=new RefundId(UUID.randomUUID());
        refunds.insert(Refund.request(id,p,Money.of(500,"EUR"),"refund_"+id.value(),"CUSTOMER_REQUEST",null,INITIAL_TIME));
        assertThat(ownership.findOwnership(id)).contains(new LoadRefundOwnershipPort.RefundOwnership(site,p.id(),account.id()));
        assertThat(ownership.findOwnership(new RefundId(UUID.randomUUID()))).isEmpty();
        assertThat(count("fake_provider_objects")).isZero();assertThat(count("fake_provider_operations")).isZero();
    }
}
