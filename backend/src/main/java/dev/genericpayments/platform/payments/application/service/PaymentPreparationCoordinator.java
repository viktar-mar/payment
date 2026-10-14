package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
public final class PaymentPreparationCoordinator {
 private final PaymentCreationTransaction phases;private final PaymentProviderRegistry registry;private final Stage2TelemetryPort telemetry;private final LongSupplier ticks;
 public PaymentPreparationCoordinator(PaymentCreationTransaction phases,PaymentProviderRegistry registry,Stage2TelemetryPort telemetry,LongSupplier ticks){this.phases=phases;this.registry=registry;this.telemetry=telemetry;this.ticks=ticks;}
 public Payment prepare(PreparePaymentCommand c){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Provider coordinator requires no ambient transaction");
  var provider=registry.select(c.account());provider.capabilities(c.account()).require(c.payment().taxMode());
  var context=new Stage2TelemetryPort.Context(c.siteKey(),Stage2TelemetryPort.Provider.valueOf(c.account().provider().name()));
  telemetry.requireContext(context);var created=phases.createLocal(c);
  if(created.created())telemetry.paymentCreated(context);
  long start=ticks.getAsLong();ProviderResult result;
  try{result=provider.preparePayment(new ProviderPaymentRequest(c.account(),created.payment(),ProviderOperationKeys.prepare(c.payment().id())));}
  catch(RuntimeException failure){result=new ProviderResult.Unknown(null);}
  Duration duration=Duration.ofNanos(Math.max(0,ticks.getAsLong()-start));
  var change=phases.recordPreparedOrUnknown(c,result);
  telemetry.providerCall(context,Stage2TelemetryPort.ProviderOperation.PREPARE,Stage2TelemetryPort.ProviderResult.valueOf(result.kind().name()),duration);
  if(change.changed())telemetry.paymentTransition(context,Stage2TelemetryPort.PaymentState.valueOf(change.previous().name()),Stage2TelemetryPort.PaymentState.valueOf(change.payment().status().name()));
  return change.payment();
 }
}
