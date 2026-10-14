package dev.genericpayments.platform.payments.adapter.in.configuration;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
class PaymentExecutionConfiguration {
 @Bean PaymentPhaseSupport paymentPhaseSupport(AppendPaymentEventPort append,LoadPaymentEventPort events,DigestPort digest,LoadSitePort sites,LoadPaymentAccountPort accounts,Clock clock){return new PaymentPhaseSupport(append,events,digest,sites,accounts,clock);}
 @Bean PaymentCreationTransaction paymentCreationTransaction(LoadPaymentPort load,SavePaymentPort save,PaymentPhaseSupport support){return new PaymentCreationTransaction(load,save,support);}
 @Bean PaymentAttemptTransaction paymentAttemptTransaction(LoadPaymentPort load,SavePaymentPort save,LoadPaymentAttemptPort attempts,SavePaymentAttemptPort saveAttempts,PaymentPhaseSupport support){return new PaymentAttemptTransaction(load,save,attempts,saveAttempts,support);}
 @Bean PaymentPreparationCoordinator paymentPreparationCoordinator(PaymentCreationTransaction phases,PaymentProviderRegistry registry,Stage2TelemetryPort telemetry){return new PaymentPreparationCoordinator(phases,registry,telemetry,System::nanoTime);}
 @Bean PaymentConfirmationCoordinator paymentConfirmationCoordinator(PaymentAttemptTransaction phases,PaymentProviderRegistry registry,Stage2TelemetryPort telemetry){return new PaymentConfirmationCoordinator(phases,registry,telemetry,System::nanoTime);}
}
