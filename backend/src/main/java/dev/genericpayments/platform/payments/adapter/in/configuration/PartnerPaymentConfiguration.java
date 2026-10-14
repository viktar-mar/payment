package dev.genericpayments.platform.payments.adapter.in.configuration;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
@Configuration(proxyBeanMethods=false)
class PartnerPaymentConfiguration {
    @Bean DynamicPaymentTransaction dynamicPaymentTransaction(IdempotencyService idem,PaymentCreationTransaction creation,SavePaymentPort payments,HandoffSessionPort handoffs,RandomTokenPort tokens,DigestPort digest,Clock clock) {
        return new DynamicPaymentTransaction(idem,creation,payments,handoffs,tokens,digest,clock);
    }
    @Bean HandoffTransaction handoffTransaction(HandoffSessionPort handoffs,CheckoutSessionPort checkouts,LoadPaymentPort payments,LoadSitePort sites,LoadPaymentAccountPort accounts,
        PaymentProviderRegistry providers,IdempotencyService idem,RandomTokenPort tokens,DigestPort digest,Stage2TelemetryPort telemetry,@Qualifier("publicRateLimitPort") RateLimitPort limits,Clock clock) {
        return new HandoffTransaction(handoffs,checkouts,payments,sites,accounts,providers,idem,tokens,digest,telemetry,limits,clock);
    }
    @Bean HandoffService handoffService(HandoffTransaction phases,HandoffSessionPort handoffs,LoadPaymentPort payments,LoadSitePort sites,LoadPaymentAccountPort accounts,
        PaymentProviderRegistry providers,Stage2TelemetryPort telemetry,Clock clock) { return new HandoffService(phases,handoffs,payments,sites,accounts,providers,telemetry,clock); }
    @Bean DynamicPaymentService dynamicPaymentService(DynamicPaymentTransaction phases,HandoffService handoffs,LoadPaymentPort payments,LoadPaymentAccountPort accounts,PaymentProviderRegistry providers,
        PaymentPreparationCoordinator preparation,Stage2TelemetryPort telemetry) { return new DynamicPaymentService(phases,handoffs,payments,accounts,providers,preparation,telemetry); }
}
