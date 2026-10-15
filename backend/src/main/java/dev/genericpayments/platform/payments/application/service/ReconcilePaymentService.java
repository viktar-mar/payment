package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.in.ReconcilePaymentUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
public final class ReconcilePaymentService implements ReconcilePaymentUseCase {
    private final PaymentTransitionService transitions;private final PaymentProviderRegistry providers;private final Stage2TelemetryPort telemetry;private final Clock clock;private final Duration lease;
    private LoadRefundPort refunds;private RefundTransitionService refundTransitions;private RefundCoordinator refundCoordinator;
    public void refunds(LoadRefundPort refunds,RefundTransitionService transitions,RefundCoordinator coordinator){this.refunds=refunds;this.refundTransitions=transitions;this.refundCoordinator=coordinator;}
    public ReconcilePaymentService(PaymentTransitionService transitions,PaymentProviderRegistry providers,Stage2TelemetryPort telemetry,Clock clock,Duration lease){if(lease==null||lease.compareTo(Duration.ofSeconds(1))<0||lease.compareTo(Duration.ofMinutes(10))>0)throw SafeFactJson.invalid();this.transitions=transitions;this.providers=providers;this.telemetry=telemetry;this.clock=clock;this.lease=lease;}
    @Override public Payment reconcile(SiteId site,PaymentId id,String requestId) {
        transitions.requireNoAmbientTransaction();
        var acquired=transitions.acquire(site,id,requestId,lease);var provider=providers.select(acquired.account());long start=System.nanoTime();ProviderState state;
        try { state=provider.retrievePayment(ProviderPaymentLookup.forPayment(acquired.account(),id,acquired.payment().providerPaymentReference())); }
        catch(RuntimeException failure){telemetry.providerCall(acquired.context(),Stage2TelemetryPort.ProviderOperation.RETRIEVE_PAYMENT,Stage2TelemetryPort.ProviderResult.TEMPORARILY_UNAVAILABLE,Duration.ofNanos(Math.max(0,System.nanoTime()-start)));throw failure;}
        if(state==null||!acquired.payment().providerPaymentReference().equals(state.reference()))throw PartnerPaymentRules.state();
        var outcome=switch(state.status()){case SUCCEEDED -> Stage2TelemetryPort.ProviderResult.SUCCEEDED;case FAILED -> Stage2TelemetryPort.ProviderResult.FAILED;case CANCELLED -> Stage2TelemetryPort.ProviderResult.CANCELLED;case REQUIRES_ACTION -> Stage2TelemetryPort.ProviderResult.REQUIRES_ACTION;case PROCESSING,PENDING -> Stage2TelemetryPort.ProviderResult.PROCESSING;case UNKNOWN -> Stage2TelemetryPort.ProviderResult.UNKNOWN;default -> Stage2TelemetryPort.ProviderResult.ACCEPTED;};
        telemetry.providerCall(acquired.context(),Stage2TelemetryPort.ProviderOperation.RETRIEVE_PAYMENT,outcome,Duration.ofNanos(Math.max(0,System.nanoTime()-start)));
        if(acquired.account().provider()!=dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE&&refunds!=null)for(var stored:refunds.findByPayment(site,id)){
            var r=stored.value();if(RefundTransitionService.terminal(r.status()))continue;
            if(r.providerRefundReference()==null)r=refundCoordinator.resume(acquired.payment(),r,acquired.account(),acquired.context(),requestId);
            if(r.providerRefundReference()==null||RefundTransitionService.terminal(r.status()))continue;
            long refundStart=System.nanoTime();ProviderState truth;
            try{truth=provider.retrieveRefund(ProviderRefundLookup.forRefund(acquired.account(),id,r.id(),r.providerRefundReference()));}
            catch(RuntimeException failure){telemetry.providerCall(acquired.context(),Stage2TelemetryPort.ProviderOperation.RETRIEVE_REFUND,Stage2TelemetryPort.ProviderResult.TEMPORARILY_UNAVAILABLE,Duration.ofNanos(Math.max(0,System.nanoTime()-refundStart)));throw failure;}
            var refundOutcome=switch(truth.status()){case SUCCEEDED -> Stage2TelemetryPort.ProviderResult.SUCCEEDED;case FAILED -> Stage2TelemetryPort.ProviderResult.FAILED;case CANCELLED -> Stage2TelemetryPort.ProviderResult.CANCELLED;case PROCESSING,PENDING -> Stage2TelemetryPort.ProviderResult.PROCESSING;case UNKNOWN -> Stage2TelemetryPort.ProviderResult.UNKNOWN;default -> Stage2TelemetryPort.ProviderResult.ACCEPTED;};
            telemetry.providerCall(acquired.context(),Stage2TelemetryPort.ProviderOperation.RETRIEVE_REFUND,refundOutcome,Duration.ofNanos(Math.max(0,System.nanoTime()-refundStart)));
            var result=refundTransitions.reconcile(acquired,r.id(),truth,requestId);refundTransitions.emit(result);
        }
        var result=transitions.reconcile(acquired,state,requestId);transitions.emit(result);return result.payment();
    }
    /** Internal Task11 entry: one already-bound Stripe Refund, four logical reads, one 120s lease. */
    public Refund reconcileRefund(SiteId site,PaymentId paymentId,RefundId refundId,String requestId) {
        transitions.requireNoAmbientTransaction();
        var acquired=transitions.acquire(site,paymentId,requestId,lease);
        if(acquired.account().provider()!=dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE)throw PartnerPaymentRules.state();
        var refund=refunds.findById(site,refundId).orElseThrow(PartnerPaymentRules::notFound).value();
        if(!refund.paymentId().equals(paymentId)||refund.providerRefundReference()==null)throw PartnerPaymentRules.state();
        long start=System.nanoTime();ProviderState state;
        try{state=providers.select(acquired.account()).retrieveRefund(ProviderRefundLookup.forRefund(acquired.account(),paymentId,refundId,refund.providerRefundReference()));}
        catch(RuntimeException failure){telemetry.providerCall(acquired.context(),Stage2TelemetryPort.ProviderOperation.RETRIEVE_REFUND,Stage2TelemetryPort.ProviderResult.TEMPORARILY_UNAVAILABLE,Duration.ofNanos(Math.max(0,System.nanoTime()-start)));throw failure;}
        var outcome=switch(state.status()){case SUCCEEDED->Stage2TelemetryPort.ProviderResult.SUCCEEDED;case FAILED->Stage2TelemetryPort.ProviderResult.FAILED;case CANCELLED->Stage2TelemetryPort.ProviderResult.CANCELLED;case PROCESSING,PENDING->Stage2TelemetryPort.ProviderResult.PROCESSING;default->Stage2TelemetryPort.ProviderResult.UNKNOWN;};
        telemetry.providerCall(acquired.context(),Stage2TelemetryPort.ProviderOperation.RETRIEVE_REFUND,outcome,Duration.ofNanos(Math.max(0,System.nanoTime()-start)));
        var result=refundTransitions.reconcile(acquired,refundId,state,requestId);refundTransitions.emit(result);return result.refund();
    }
}
