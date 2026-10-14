package dev.genericpayments.platform.donations.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderRegistry;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DonationPaymentLifecycleIntegrationTest extends DonationFinancialTestFixture {
    @Autowired PaymentPreparationCoordinator preparation;
    @Autowired Stage2TelemetryPort telemetry;
    @Autowired PaymentTransitionService transitions;
    @Autowired ProviderInboxService inbox;
    @Autowired ProviderInboxWorker worker;
    @Autowired RefundCoordinator refunds;
    @Autowired Clock clock;

    @ParameterizedTest @EnumSource(value=PaymentSource.class,names={"CATALOG","DYNAMIC","DONATION"})
    void synchronousProcessingIsNotSuccessAndUnknownLookupInventsNoFact(PaymentSource source) {
        var p=preparation.prepare(prepare(source(source)));
        var provider=new DelegatingProvider(fake) {
            @Override public ProviderResult confirmPayment(ProviderConfirmationRequest request) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new ProviderResult.Accepted(request.payment().providerPaymentReference(),true);
            }
            @Override public ProviderState retrievePayment(ProviderPaymentLookup lookup){return new ProviderState(lookup.reference(),ProviderState.Status.UNKNOWN);}
        };
        var registry=new PaymentProviderRegistry(Map.of(account.provider(),provider));
        var result=new PaymentConfirmationCoordinator(attemptPhase,registry,telemetry,System::nanoTime).confirm(confirm(p));
        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
        int journal=count("payment_events");
        var reconciled=new ReconcilePaymentService(transitions,registry,telemetry,clock,Duration.ofSeconds(30)).reconcile(site,p.id(),"task5-unknown");
        assertThat(reconciled.status()).isEqualTo(PaymentStatus.PROCESSING);assertThat(count("payment_events")).isEqualTo(journal);
    }

    @ParameterizedTest @EnumSource(value=PaymentSource.class,names={"CATALOG","DYNAMIC","DONATION"})
    void sharedPreparationDeclineActionProcessingAndInboxUseOneFinancialJournal(PaymentSource source) {
        var original=source(source);
        var prepared=preparation.prepare(prepare(original));
        assertThat(prepared.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        var results=List.of(new ProviderResult.RecoverableDecline(prepared.providerPaymentReference()),
            new ProviderResult.RequiresAction(prepared.providerPaymentReference(),new ProviderResult.Action(ProviderResult.ActionKind.COMPLETE_VERIFICATION,"safe")),
            new ProviderResult.Accepted(prepared.providerPaymentReference(),true));
        var expected=List.of(PaymentStatus.REQUIRES_PAYMENT_METHOD,PaymentStatus.REQUIRES_ACTION,PaymentStatus.PROCESSING);
        for(int i=0;i<results.size();i++) {
            var result=results.get(i);
            if(i==2) {
                receive(prepared,"processing",PaymentEventType.PAYMENT_PROCESSING);
            } else {
                var provider=new DelegatingProvider(fake) {
                    @Override public ProviderResult confirmPayment(ProviderConfirmationRequest request) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        assertThat(request.payment().money()).isEqualTo(original.money());
                        assertThat(request.payment().paymentAccountId()).isEqualTo(account.id());
                        return result;
                    }
                };
                var coordinator=new PaymentConfirmationCoordinator(attemptPhase,new PaymentProviderRegistry(Map.of(account.provider(),provider)),telemetry,System::nanoTime);
                prepared=coordinator.confirm(confirm(prepared));
            }
            assertThat(load.findById(site,original.id()).orElseThrow().status()).isEqualTo(expected.get(i));
        }
        receive(prepared,"success",PaymentEventType.PAYMENT_SUCCEEDED);
        int journal=count("payment_events");
        receive(prepared,"success",PaymentEventType.PAYMENT_SUCCEEDED);
        receive(prepared,"late-failure",PaymentEventType.PAYMENT_FAILED);
        receive(prepared,"late-processing",PaymentEventType.PAYMENT_PROCESSING);
        var paid=load.findById(site,original.id()).orElseThrow();
        assertThat(paid.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(count("payment_events")).isEqualTo(journal);
        assertThat(paid.source()).isEqualTo(source);
        assertThat(paid.donationId()).isEqualTo(original.donationId());
        assertThat(paid.donationSnapshot()).isEqualTo(original.donationSnapshot());
        assertThat(events.findByPayment(site,paid.id())).extracting(PaymentEvent::type)
            .contains(PaymentEventType.PAYMENT_CREATED,PaymentEventType.PAYMENT_METHOD_REQUIRED,PaymentEventType.CUSTOMER_ACTION_REQUIRED,PaymentEventType.PAYMENT_PROCESSING,PaymentEventType.PAYMENT_SUCCEEDED);
    }
    @ParameterizedTest @EnumSource(value=PaymentSource.class,names={"CATALOG","DYNAMIC","DONATION"})
    void unknownConfirmationReconcilesThenPartialAndFullRefundsStayProviderConfirmed(PaymentSource source) {
        var prepared=preparation.prepare(prepare(source(source)));
        var provider=new DelegatingProvider(fake) {
            @Override public ProviderResult confirmPayment(ProviderConfirmationRequest request){return new ProviderResult.Unknown(request.payment().providerPaymentReference());}
            @Override public ProviderState retrievePayment(ProviderPaymentLookup lookup){
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new ProviderState(lookup.reference(),ProviderState.Status.SUCCEEDED);
            }
        };
        new PaymentConfirmationCoordinator(attemptPhase,new PaymentProviderRegistry(Map.of(account.provider(),provider)),telemetry,System::nanoTime).confirm(confirm(prepared));
        assertThat(attempts.findByPayment(site,prepared.id()).getLast().value().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        var reconcile=new ReconcilePaymentService(transitions,new PaymentProviderRegistry(Map.of(account.provider(),provider)),telemetry,clock,Duration.ofSeconds(30));
        var paid=reconcile.reconcile(site,prepared.id(),"task5-reconcile");
        assertThat(paid.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        int journal=count("payment_events");reconcile.reconcile(site,paid.id(),"task5-repeat");assertThat(count("payment_events")).isEqualTo(journal);
        long total=paid.money().minorUnits();
        for(long amount:new long[]{700,total-700}) {
            var refund=refunds.create(new CreateRefundCommand(site,paid.id(),UUID.randomUUID(),amount,"EUR","REQUESTED_BY_CUSTOMER",null,UUID.randomUUID().toString(),"task5-refund"));
            assertThat(load.findById(site,paid.id()).orElseThrow().refundedAmountMinor()).isEqualTo(amount==700?0:700);
            var fact=new ProviderEventEnvelope(account.provider(),account.id(),"refund-"+amount,refund.providerRefundReference(),PaymentEventType.REFUND_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of());
            inbox.receive(fact);worker.runOnce();inbox.receive(fact);worker.runOnce();
            assertThat(load.findById(site,paid.id()).orElseThrow().status()).isEqualTo(amount==700?PaymentStatus.PARTIALLY_REFUNDED:PaymentStatus.REFUNDED);
        }
        var refunded=load.findById(site,paid.id()).orElseThrow();
        assertThat(refunded.refundedAmountMinor()).isEqualTo(total);
        assertThat(refunded.donationSnapshot()).isEqualTo(paid.donationSnapshot());
    }
    @ParameterizedTest @EnumSource(value=PaymentSource.class,names={"CATALOG","DYNAMIC","DONATION"})
    void providerFailureWinsOverLateSuccessForEverySource(PaymentSource source) {
        var p=preparation.prepare(prepare(source(source)));receive(p,"failure",PaymentEventType.PAYMENT_FAILED);
        int journal=count("payment_events");receive(p,"late-success",PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(count("payment_events")).isEqualTo(journal);
    }
    private void receive(Payment p,String id,PaymentEventType type) {
        inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),id,p.providerPaymentReference(),type,INITIAL_TIME,INITIAL_TIME,Map.of()));worker.runOnce();
    }
}
