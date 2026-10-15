package dev.genericpayments.platform.acceptance;
import dev.genericpayments.platform.support.CustomerMailpitIntegrationTest;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
@CucumberContextConfiguration
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("local")
@TestPropertySource(properties = {"payment.admin.bootstrap.username=admin", "payment.admin.bootstrap.password=local-password", "payment.telemetry.site-keys[0]=acceptance", "payment.telemetry.site-keys[1]=customer-one", "payment.telemetry.site-keys[2]=customer-two", "payment.telemetry.site-keys[3]=subscription-acceptance"})
@org.springframework.context.annotation.Import(StripeAcceptanceConfiguration.class)
public class CucumberSpringConfiguration extends CustomerMailpitIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource
    static void stripe(org.springframework.test.context.DynamicPropertyRegistry registry) {
        StripeAcceptanceConfiguration.properties(registry);
    }
}
