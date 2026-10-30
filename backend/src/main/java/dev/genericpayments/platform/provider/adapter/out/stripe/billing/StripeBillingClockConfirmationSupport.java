package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.testhelpers.TestClock;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction.State;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared descriptive graph checks. Persisted authority always comes from guarded phases. */
final class StripeBillingClockConfirmationSupport {
    private StripeBillingClockConfirmationSupport() {}
    static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw StripeBillingOwnership.conflict();}
    static void require(boolean valid){if(!valid)throw StripeBillingOwnership.mismatch();}
    static StripeBillingSnapshotMapper.PaymentAction graph(StripeClient client,State state,OwnedClockSubscriptionReceipt receipt,Instant now)throws StripeException{
        return graph(client,state,receipt,now,null);
    }
    static StripeBillingSnapshotMapper.PaymentAction graph(StripeClient client,State state,OwnedClockSubscriptionReceipt receipt,Instant now,StripeBillingSnapshotMapper.OwnedActionFailure failure)throws StripeException{
        ready(client,receipt.intent().clock());
        var i=receipt.intent();var o=i.clock().owner();
        var customer=new CustomerBinding(i.customerBinding(),o.account(),o.site(),o.customer(),i.customerReference(),i.customerCreationOperation(),0);
        var ownership=new StripeBillingSnapshotMapper.Ownership(state.account(),state.subscription(),customer,
            List.of(new StripeBillingSnapshotMapper.PublishedPrice(i.terms(),i.priceReference(),i.productReference())));
        var graph=StripeBillingSnapshotMapper.retrieveOwnedPaymentAction(client,ownership,new StripeBillingSnapshotMapper.OwnedInitial(receipt),now,failure);
        ready(client,i.clock());return graph;
    }
    static void ready(StripeClient client,ReadyClock expected)throws StripeException{
        TestClock r=client.v1().testHelpers().testClocks().retrieve(expected.reference(),StripeBillingTestClockAdapter.options(null));
        require(r!=null&&expected.reference().equals(r.getId())&&"test_helpers.test_clock".equals(r.getObject())&&Boolean.FALSE.equals(r.getLivemode())&&!Boolean.TRUE.equals(r.getDeleted())
            &&expected.owner().name().equals(r.getName())&&Objects.equals(expected.frozenTime(),r.getFrozenTime())&&Objects.equals(expected.providerCreated(),r.getCreated())
            &&Objects.equals(expected.providerDeletesAfter(),r.getDeletesAfter())&&"ready".equals(r.getStatus())&&(r.getStatusDetails()==null||r.getStatusDetails().getAdvancing()==null));
    }
    static void root(SubscriptionClientGrant root,OwnedClockSubscriptionReceipt receipt,StripeBillingGrantTokens tokens,Instant now){
        var i=receipt.intent();var o=i.clock().owner();var b=root.binding();
        require(tokens.enabled()&&root.site().equals(o.site())&&root.customer().equals(o.customer())&&root.account().equals(o.account())&&root.subscription().equals(i.subscription())&&root.operation().equals(i.createOperation())
            &&"owned-initial-v1".equals(b.schema())&&i.id().equals(b.ownedIntent())&&b.setupReference()==null&&receipt.providerOperation().equals(b.stepId())&&i.operationKey().equals(b.operationKey())
            &&receipt.reference().equals(b.subscriptionReference())&&i.customerReference().equals(b.customerReference())&&b.invoiceReference()!=null&&b.allocationReference()!=null&&b.intentReference()!=null
            &&OwnedInitialConfirmationProof.hash(receipt,b.invoiceReference(),b.allocationReference(),b.intentReference()).equals(b.proof())
            &&root.supersededAt()==null&&root.sessionId()==null&&root.origin()==null&&root.clientExpiresAt()==null&&!root.issuedAt().isBefore(receipt.acknowledgedAt())
            &&root.expiresAt().isAfter(root.issuedAt())&&!root.expiresAt().isAfter(root.issuedAt().plusSeconds(600))&&!now.isBefore(root.issuedAt())&&now.isBefore(root.expiresAt())
            &&StripeBillingGrantTokens.matches(tokens.token(root.keyVersion(),root.descriptor()),root.tokenDigest()));
    }
    static void same(State a,State b){require(a.account().equals(b.account())&&a.subscription().equals(b.subscription())&&a.operation().equals(b.operation())&&a.policy().equals(b.policy()));}
}
