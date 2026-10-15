package dev.genericpayments.platform.payments.adapter.in.configuration;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class AdminPaymentConfiguration {
    @Bean CheckoutReturnUrlAdminService checkoutReturnUrlAdminService(CheckoutReturnUrlAdminPort policies,LoadSitePort sites,dev.genericpayments.platform.admin.application.port.out.AuditLogPort audit,Clock clock){return new CheckoutReturnUrlAdminService(policies,sites,audit,clock);}
    @Bean AdminPaymentQueryService adminPaymentQueryService(AdminPaymentReadPort reads){return new AdminPaymentQueryService(reads);}
    @Bean SimulateFakeEventService simulateFakeEventService(AdminPaymentReadPort reads,PaymentTransitionPort tx,LoadSitePort sites,LoadPaymentAccountPort accounts,LoadRefundPort refunds,FakeEventControlPort control,IdempotencyService idem,AdminPaymentAuditPort audit,Stage2TelemetryPort telemetry,Clock clock){return new SimulateFakeEventService(reads,tx,sites,accounts,refunds,control,idem,audit,telemetry,clock);}
    @Bean AdminReconciliationService adminReconciliationService(AdminPaymentReadPort reads,PaymentTransitionPort tx,IdempotencyService idem,AdminPaymentAuditPort audit,ReconcilePaymentService reconcile,LoadSitePort sites,LoadPaymentAccountPort accounts,PaymentProviderRegistry providers,Stage2TelemetryPort telemetry){return new AdminReconciliationService(reads,tx,idem,audit,reconcile,sites,accounts,providers,telemetry);}
}
