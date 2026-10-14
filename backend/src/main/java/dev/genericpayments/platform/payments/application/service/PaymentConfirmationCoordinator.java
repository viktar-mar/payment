package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
public final class PaymentConfirmationCoordinator {
 private final PaymentAttemptTransaction phases;private final PaymentProviderRegistry registry;private final Stage2TelemetryPort telemetry;private final LongSupplier ticks;
 public PaymentConfirmationCoordinator(PaymentAttemptTransaction phases,PaymentProviderRegistry registry,Stage2TelemetryPort telemetry,LongSupplier ticks){this.phases=phases;this.registry=registry;this.telemetry=telemetry;this.ticks=ticks;}
 public Payment confirm(ConfirmPaymentCommand c){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Provider coordinator requires no ambient transaction");
  var provider=registry.select(c.account());provider.capabilities(c.account()).require(c.method());
  var context=new Stage2TelemetryPort.Context(c.siteKey(),Stage2TelemetryPort.Provider.valueOf(c.account().provider().name()));
  telemetry.requireContext(context);var started=phases.startAttempt(c);
  long start=ticks.getAsLong();ProviderResult result;
  try{result=provider.confirmPayment(new ProviderConfirmationRequest(c.account(),started.payment(),started.attempt().value(),c.method(),ProviderOperationKeys.confirm(c.attemptId())));}
  catch(RuntimeException failure){result=new ProviderResult.Unknown(null);}
  Duration duration=Duration.ofNanos(Math.max(0,ticks.getAsLong()-start));
  var change=phases.recordProviderAcknowledgementOrUnknown(c,result);
  telemetry.providerCall(context,Stage2TelemetryPort.ProviderOperation.CONFIRM,Stage2TelemetryPort.ProviderResult.valueOf(result.kind().name()),duration);
  if(change.changed())telemetry.paymentTransition(context,Stage2TelemetryPort.PaymentState.valueOf(change.previous().name()),Stage2TelemetryPort.PaymentState.valueOf(change.payment().status().name()));
  return change.payment();
 }
}
