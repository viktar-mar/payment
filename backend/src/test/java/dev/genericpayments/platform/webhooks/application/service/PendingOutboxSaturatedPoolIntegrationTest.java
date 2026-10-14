package dev.genericpayments.platform.webhooks.application.service;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"spring.datasource.hikari.maximum-pool-size=10","spring.datasource.hikari.minimum-idle=10",
    "spring.datasource.hikari.connection-timeout=2000","payment.callbacks.delay-ms=60000"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PendingOutboxSaturatedPoolIntegrationTest extends PendingOutboxPoolPressureTestSupport {
    @Override int poolSize(){return 10;}
}
