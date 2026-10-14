package dev.genericpayments.platform.customers.adapter.in.configuration;

import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.application.service.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class CustomerIdentityLinkConfiguration {
    @Bean LinkCustomerIdentityUseCase customerIdentityLinkService(LockSitePort sites,CustomerStore customers,IdentityLinkProofStore proofs,
            KeyedDigestPort digests,CustomerIdentityStore identities,CustomerIdentityLinkAuditPort audits,Clock clock,
            CustomerTransactionPort transactions,CustomerTelemetryPort telemetry) {
        return new CustomerIdentityLinkService(sites,customers,proofs,digests,identities,audits,clock,transactions,telemetry);
    }

    @Bean LinkPartnerCustomerIdentityUseCase partnerCustomerIdentityLinkService(LockSitePort sites,CustomerStore customers,
            IdentityProviderConfigurationStore providers,CustomerIdempotencyStore idempotency,KeyedDigestPort keyedDigests,
            DigestPort digests,CustomerIdentityStore identities,CustomerIdentityLinkAuditPort audits,Clock clock,
            CustomerTransactionPort transactions) {
        return new PartnerCustomerIdentityLinkService(sites,customers,providers,idempotency,keyedDigests,digests,identities,audits,clock,transactions);
    }
}
