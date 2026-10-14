package dev.genericpayments.platform.customers.adapter.in.configuration;

import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.application.service.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class CustomerManagementConfiguration {
    @Bean AnonymizeCustomerUseCase customerAnonymizationService(CustomerStore customers,CustomerIdentityStore identities,LockSitePort sites,
            CustomerIdempotencyStore idempotency,DigestPort digests,CustomerAccessRevocationPort access,CustomerDataRedactionPort redaction,
            CustomerAnonymizationAuditPort audits,CustomerTransactionPort transactions,Clock clock,CustomerTelemetryPort telemetry,CustomerBillingAnonymizationGuardPort billingGuard) {
        return new CustomerAnonymizationService(customers,identities,sites,idempotency,digests,access,redaction,audits,transactions,clock,billingGuard,telemetry);
    }
    @Bean CustomerQueryService customerQueryService(CustomerStore customers,CustomerIdentityStore identities,CustomerTransactionPort transactions) {
        return new CustomerQueryService(customers,identities,transactions);
    }
}
