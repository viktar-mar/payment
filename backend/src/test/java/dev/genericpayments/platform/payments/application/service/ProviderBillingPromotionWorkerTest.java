package dev.genericpayments.platform.payments.application.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderBillingPromotionWorkerTest {
    final ProviderInboxPort inbox=mock(ProviderInboxPort.class);
    final ProviderInboxResolutionPort resolution=mock(ProviderInboxResolutionPort.class);
    final PaymentTransitionService transitions=mock(PaymentTransitionService.class);
    final Stage2TelemetryPort telemetry=mock(Stage2TelemetryPort.class);
    final ProviderInboxPort.Claim claim=new ProviderInboxPort.Claim(UUID.randomUUID(),"owned");
    final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    ProviderReferenceReceipt receipt(boolean refund){return new ProviderReferenceReceipt(PaymentProviderType.STRIPE,new PaymentAccountId(UUID.randomUUID()),"evt_owned",refund?"re_owned":"pi_owned",refund?ProviderReferenceReceipt.ObjectType.REFUND:ProviderReferenceReceipt.ObjectType.PAYMENT,refund?"refund.created":"payment_intent.succeeded","2026-09-30.endive",now,now);}
    void run(ProviderReferenceReceipt receipt){when(inbox.claimReference(any())).thenReturn(Optional.of(claim));when(inbox.reference(claim)).thenReturn(Optional.of(receipt));new ProviderReferenceInboxWorker(inbox,resolution,transitions,telemetry,Clock.fixed(now,ZoneOffset.UTC)).runOnce();}
    @Test void committedPromotionEndsPassBeforeResolutionAndFinancialWork(){var receipt=receipt(false);when(inbox.promoteBillingPaymentReference(claim,receipt)).thenReturn(true);run(receipt);verify(inbox).promoteBillingPaymentReference(claim,receipt);verifyNoInteractions(resolution,telemetry);verify(transitions).requireNoAmbientTransaction();verifyNoMoreInteractions(transitions);verify(inbox,never()).completeReference(any());verify(inbox,never()).failReference(any(),any());}
    @Test void unresolvedPromotionRetainsOriginalResolverAndRetry(){var receipt=receipt(false);when(resolution.resolve(receipt)).thenThrow(new IllegalStateException("UNRESOLVED_OBJECT"));run(receipt);var order=inOrder(inbox,resolution);order.verify(inbox).promoteBillingPaymentReference(claim,receipt);order.verify(resolution).resolve(receipt);verify(inbox).failReference(claim,"UNRESOLVED_OBJECT");}
    @Test void refundsNeverAttemptBillingPromotion(){var receipt=receipt(true);when(resolution.resolve(receipt)).thenThrow(new IllegalStateException("UNRESOLVED_OBJECT"));run(receipt);verify(inbox,never()).promoteBillingPaymentReference(any(),any());verify(resolution).resolve(receipt);verify(inbox).failReference(claim,"UNRESOLVED_OBJECT");}
    @Test void lostPromotionClaimCannotContinueToProvider(){var receipt=receipt(false);when(inbox.promoteBillingPaymentReference(claim,receipt)).thenThrow(new IllegalStateException("CLAIM_LOST"));run(receipt);verifyNoInteractions(resolution);verify(inbox).failReference(claim,"PROCESSING_FAILED");}
}
