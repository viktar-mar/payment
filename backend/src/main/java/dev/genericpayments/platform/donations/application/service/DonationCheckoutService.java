package dev.genericpayments.platform.donations.application.service;

import dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand;
import dev.genericpayments.platform.donations.application.port.in.CreateDonationCheckoutUseCase;
import dev.genericpayments.platform.donations.application.port.out.DonationPaymentCreationPort;
import dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort;
import dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort.CheckoutOutcome;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Duration;
import dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService;
import org.springframework.stereotype.Service;

@Service
public class DonationCheckoutService implements CreateDonationCheckoutUseCase {
    private final DonationCheckoutTransaction transaction;
    private final DonationTelemetryPort telemetry;
    public DonationCheckoutService(DonationCheckoutTransaction transaction,DonationTelemetryPort telemetry){this.transaction=transaction;this.telemetry=telemetry;}
    @Override public DonationPaymentCreationPort.Checkout create(CreateDonationCheckoutCommand command) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Checkout coordinator requires no ambient transaction");
        long start=System.nanoTime();CheckoutOutcome outcome=CheckoutOutcome.FAILED;
        try {
            var result=transaction.create(command);
            // Let common receipt shredding commit before translating an expired replay to its HTTP error.
            if(result.expiredPaymentId()!=null) throw CheckoutAuthorizationService.expired(result.expiredPaymentId());
            outcome=result.replayed()?CheckoutOutcome.REPLAYED:CheckoutOutcome.CREATED;
            return result.checkout();
        } catch(PaymentApplicationException failure){
            outcome=switch(failure.code()){
                case "AMOUNT_NOT_ALLOWED"->CheckoutOutcome.REJECTED_AMOUNT;
                case "ANONYMOUS_DONATION_NOT_ALLOWED"->CheckoutOutcome.REJECTED_ANONYMOUS;
                case "AUTHENTICATION_FAILED"->CheckoutOutcome.REJECTED_SESSION;
                case "IDEMPOTENCY_CONFLICT","OPERATION_IN_PROGRESS"->CheckoutOutcome.CONFLICT;
                default->failure.status()<500?CheckoutOutcome.REJECTED:CheckoutOutcome.FAILED;
            };throw failure;
        } catch(DomainValidationException failure){outcome=CheckoutOutcome.REJECTED;throw failure;}
        finally {telemetry.checkout(outcome,Duration.ofNanos(System.nanoTime()-start));}
    }
}
