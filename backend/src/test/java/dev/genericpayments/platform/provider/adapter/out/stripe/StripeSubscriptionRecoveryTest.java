package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoicePayParams;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.RecoveryDisposition;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind;
import java.util.Map;
import java.util.*;
import java.time.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionRecoveryProofStore;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import org.junit.jupiter.api.Test;

class StripeSubscriptionRecoveryTest {
    private static final String PAY = "/v1/invoices/in_owned/pay";

    @Test
    void publicRecoveryReservesOnlyLocalOwnerThenRequiresTheExactAssociationProof() throws Exception {
        try(var c=new RecoveryCase()){
            var service=c.publicService();c.openReadGraph();c.actionGraph();
            var result=publicRecover(service,c);
            assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
            assertThat(c.receipts.get(result.operationId()).billingRecord()).isEqualTo(c.bill.id());
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();
            assertThat(c.fixture.local.debt().graceDeadline()).isEqualTo(c.grace);
        }
    }

    @Test
    void publicReservationDoesNotTurnBlockedNativeStateIntoFinancialPermission() throws Exception {
        try(var c=new RecoveryCase()){
            var service=c.publicService();c.openReadGraph();when(c.proofs.available(any(),any(),anyString(),any())).thenReturn(false);
            var result=publicRecover(service,c);
            assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
            assertThat(c.receipts).isEmpty();assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();
        }
    }

    @Test
    void explicitSameKeyPublicRecoveryReachesOnlyItsOriginalUnknownPay() throws Exception {
        try(var c=new RecoveryCase()){
            var service=c.publicService();c.openReadGraph();c.noAttemptGraph();c.fixture.graph.http.disconnectNext("POST",PAY);
            assertThat(publicRecover(service,c).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);var original=c.payStep();
            doAnswer(i->new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore.Claim(c.fixture.operation,false)).when(c.fixture.operations).claim(any());
            c.openReadGraph();c.noAttemptGraph();c.fixture.graph.http.respond("POST",PAY,c.fixture.graph.invoice.toString());c.afterPayActionGraph();
            assertThat(publicRecover(service,c).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
            assertThat(c.payStep().id()).isEqualTo(original.id());assertThat(c.payStep().operationKey()).isEqualTo(original.operationKey());
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isEqualTo(2);
        }
    }
    static dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult publicRecover(dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryService service,RecoveryCase c){
        var result=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult[1];
        assertThatCode(()->result[0]=service.recover(c.publicCommand())).doesNotThrowAnyException();return result[0];
    }

    @Test
    void exactPaidRecoveryReadObservesTheBillWithoutPayGrantOrReceiptWrites() throws Exception {
        try(var c=new RecoveryCase()){
            c.cashGraph();
            var result=c.fixture.adapter.retrieve(new Lookup(c.fixture.account,c.fixture.local.id(),"sub_owned",c.fixture.operation.providerOperationKey()));
            assertThat(result.operationObservation()).isNotNull();assertThat(result.operationObservation().state()).isEqualTo(OperationState.APPLIED);
            assertThat(result.invoices().getFirst().cashObjects().getFirst().amountMinor()).isEqualTo(c.bill.amountDueMinor());
            assertThat(c.receipts).isEmpty();assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();
            verify(c.proofs,never()).associate(any(),any(),any(),any(),anyString(),anyString());verify(c.proofs,never()).recordPay(any(),any(),any(),any(),any(),anyString(),any(),any());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void associatedRecoveryActionHasDistinctSchemaAndOriginalConsentStep(boolean laterCurrentPeriod) throws Exception {
        try(var c=new RecoveryCase()){
            if(laterCurrentPeriod){var s=c.fixture.local;var period=new Subscription.Period(s.currentPeriod().end(),s.currentPeriod().end().plusSeconds(2678400));c.fixture.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),period,s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());c.fixture.graph.item().addProperty("current_period_start",period.start().getEpochSecond());c.fixture.graph.item().addProperty("current_period_end",period.end().getEpochSecond());}
            c.actionGraph();assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.ACCEPTED);
            var ownership=(dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership)org.springframework.test.util.ReflectionTestUtils.getField(c.fixture.adapter,"ownership");
            var proof=ownership.recovery(c.command(),c.proofs,null);c.afterPayActionGraph();
            var actions=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingSnapshotMapper.PaymentAction[1];
            assertThatCode(()->actions[0]=dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingSnapshotMapper.retrievePaymentAction(c.fixture.graph.http.client(),proof,Instant.now())).doesNotThrowAnyException();var action=actions[0];
            assertThat(action.binding().schema()).isEqualTo("recovery-v1");assertThat(action.binding().stepKind()).isEqualTo("SUBSCRIPTION_CREATE");assertThat(action.binding().stepId()).isEqualTo(c.origin.step());
            assertThat(action.binding().operationKey()).isEqualTo(c.fixture.operation.providerOperationKey());assertThat(action.binding().invoiceReference()).isEqualTo(c.bill.providerInvoiceReference());assertThat(action.binding().intentReference()).isEqualTo("pi_owned");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void consumedRecoveryOwnerReachesPublicFalsePermissionReplayAtEitherReadBoundary(boolean finalPhase) throws Exception {
        try(var c=new RecoveryCase()){
            c.actionGraph();assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.ACCEPTED);
            var actual=c.actions();c.actionGraph();var first=actual.nextAction(c.fixture.lookup());assertThat(first).isNotNull();
            var service=c.confirmations();c.bindGrant();var original=c.grant;
            if(finalPhase){c.consumeInFinalActionPhase=true;c.actionTransactions=0;c.actionGraph();}else c.consumeAfterStaleReplayCheck=true;
            var result=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult[1];
            assertThatCode(()->result[0]=service.confirm(c.confirmCommand(first.secret()))).doesNotThrowAnyException();
            assertThat(result[0].browserConfirmAllowed()).isFalse();assertThat(c.grant.id()).isEqualTo(original.id());assertThat(c.grant.tokenDigest()).isEqualTo(original.tokenDigest());assertThat(c.grant.consumedAt()).isNotNull();
            verify(c.actionStore,never()).claimBilling(any(),any(),any(),any(),any(),anyString(),any(),anyString(),anyString(),anyString(),anyString(),any());
            int reads=c.fixture.graph.http.calls().size();assertThatCode(()->assertThat(actual.nextAction(c.fixture.lookup())).isNull()).doesNotThrowAnyException();assertThat(c.fixture.graph.http.calls()).hasSize(reads);
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();
        }
    }

    @Test
    void actualRecoveryActionStillRefusesAnIncompatibleAvailableOwner() throws Exception {
        try(var c=new RecoveryCase()){
            c.actionGraph();assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.ACCEPTED);
            var actual=c.actions();c.actionGraph();actual.nextAction(c.fixture.lookup());var original=c.grant;
            when(c.proofs.available(any(),any(),anyString(),any())).thenReturn(false);c.actionGraph();
            assertThatThrownBy(()->actual.nextAction(c.fixture.lookup())).isInstanceOf(PaymentApplicationException.class);assertThat(c.grant).isEqualTo(original);
        }
    }

    @Test
    void publicRecoveryRetainsAcceptedImmediateProrationHistoryWhenAssociatingTheLaterDebt() throws Exception {
        try(var c=new RecoveryCase()){
            c.retainAcceptedProration();var service=c.publicService();c.openReadGraph();c.actionGraph();
            assertThat(publicRecover(service,c).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
            assertThat(c.receipts.get(c.fixture.operation.id()).billingRecord()).isEqualTo(c.bill.id());assertThat(c.receipts.get(c.fixture.operation.id()).payStep()).isNull();
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();assertThat(c.fixture.local.debt().graceDeadline()).isEqualTo(c.grace);
            verify(c.planHistory,atLeast(2)).prorations(c.fixture.account.id(),c.fixture.local.id());
        }
    }

    @Test
    void ownedUnfinishedFirstActionAssociatesWithoutPayOrAnotherIntent() throws Exception {
        try(var c=new RecoveryCase()){
            c.actionGraph();
            assertThatCode(()->assertThat(c.fixture.adapter.recover(c.command()).outcome()).as("native paths %s",c.fixture.graph.http.calls().stream().map(call->call.method()+" "+call.path()).toList()).isEqualTo(Outcome.ACCEPTED)).doesNotThrowAnyException();
            var proof=c.receipts.get(c.fixture.operation.id());
            assertThat(proof.billingRecord()).isEqualTo(c.bill.id());
            assertThat(proof.allocation()).isEqualTo("inpay_owned");assertThat(proof.intent()).isEqualTo("pi_owned");
            assertThat(proof.payStep()).isNull();
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();
            assertThat(c.fixture.steps.rows.values()).noneSatisfy(step->assertThat(step.kind()).isEqualTo(ProviderOperation.Kind.INVOICE_PAY));
        }
    }

    @Test
    void explicitPayRequiresPositiveNoAttemptProofAndUsesOneOriginalStep() throws Exception {
        try(var c=new RecoveryCase()){
            c.noAttemptGraph();
            c.fixture.graph.http.respond("POST",PAY,c.fixture.graph.invoice.toString());
            c.afterPayActionGraph();
            assertThat(c.fixture.adapter.recover(c.command()).outcome()).as("native paths %s; steps %s",c.fixture.graph.http.calls().stream().map(call->call.method()+" "+call.path()).toList(),c.fixture.steps.rows.values().stream().map(s->s.kind()+":"+s.status()).toList()).isEqualTo(Outcome.ACCEPTED);
            var step=c.payStep();
            assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(step.resourceId()).isEqualTo(c.fixture.operation.id());assertThat(step.providerReference()).isEqualTo("in_owned");
            assertThat(c.receipts.get(c.fixture.operation.id()).payStep()).isEqualTo(step.id());
            var pay=c.fixture.graph.http.calls().stream().filter(call->call.method().equals("POST")&&call.path().equals(PAY)).toList();
            assertThat(pay).singleElement().satisfies(call->{assertThat(call.header("Idempotency-Key")).isEqualTo(c.fixture.operation.providerOperationKey()+":INVOICE_PAY");assertThat(call.form()).containsExactlyEntriesOf(Map.of("payment_method","pm_owned"));});
            assertThat(c.fixture.local.debt().graceDeadline()).isEqualTo(c.grace);
        }
    }

    @Test
    void absentAllocationWithoutExplicitNoAutomaticWorkProofCannotPay() throws Exception {
        try(var c=new RecoveryCase()){
            c.fixture.graph.invoice.remove("attempted");c.noAttemptGraph();
            assertThatCode(()->assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN)).doesNotThrowAnyException();
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();assertThat(c.receipts).isEmpty();
        }
    }

    @Test
    void consumedOrIncompatibleLocalOwnerCannotBeAdoptedFromRetryableNativeStatus() throws Exception {
        try(var c=new RecoveryCase()){
            when(c.proofs.available(any(),any(),anyString(),any())).thenReturn(false);
            assertThatCode(()->assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN)).doesNotThrowAnyException();
            assertThat(c.receipts).isEmpty();assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"attempted","count","automatic","scheduled","missingNext","amount","period","processing"})
    void incompleteOrIncompatibleNativeEvidenceNeverCreatesFinancialPermission(String mutation) throws Exception {
        try(var c=new RecoveryCase()){
            switch(mutation){
                case "attempted"->c.fixture.graph.invoice.addProperty("attempted",true);
                case "count"->c.fixture.graph.invoice.addProperty("attempt_count",1);
                case "automatic"->c.fixture.graph.invoice.addProperty("auto_advance",true);
                case "scheduled"->c.fixture.graph.invoice.addProperty("next_payment_attempt",Instant.now().plusSeconds(60).getEpochSecond());
                case "missingNext"->c.fixture.graph.invoice.remove("next_payment_attempt");
                case "amount"->c.fixture.graph.invoice.addProperty("amount_due",999);
                case "period"->c.fixture.graph.line.getAsJsonObject("period").addProperty("end",StripeBillingSnapshotMapperTest.END.plusSeconds(1).getEpochSecond());
                case "processing"->c.fixture.graph.intent.addProperty("status","processing");
            }
            if(mutation.equals("processing"))c.actionGraph();else c.noAttemptGraph();
            assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN);
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();assertThat(c.receipts).isEmpty();
        }
    }

    @Test
    void originalAcknowledgementWithoutTheCapturedConsentFingerprintIsNotRecoveryAuthority() throws Exception {
        try(var c=new RecoveryCase()){
            var root=c.fixture.steps.findById(c.origin.step()).orElseThrow();
            c.fixture.steps.rows.put(root.id(),new ProviderOperation(root.id(),root.accountId(),root.externalAccountId(),root.credentialsRef(),root.kind(),root.resourceId(),root.operationKey(),"b".repeat(64),root.status(),root.providerReference(),root.firstDispatchedAt(),root.retryBefore(),root.leaseOwner(),root.leaseExpiresAt(),root.version()));
            assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN);
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isZero();assertThat(c.receipts).isEmpty();
        }
    }

    @Test
    void lostPayResponseReplaysOnlyOriginalInvoiceStepAndKeyWithinItsWindow() throws Exception {
        try(var c=new RecoveryCase()){
            c.noAttemptGraph();c.fixture.graph.http.disconnectNext("POST",PAY);
            assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN);var unknown=c.payStep();
            assertThat(unknown.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(c.receipts).isEmpty();
            c.noAttemptGraph();c.fixture.graph.http.respond("POST",PAY,c.fixture.graph.invoice.toString());c.afterPayActionGraph();
            assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(c.payStep().id()).isEqualTo(unknown.id());assertThat(c.payStep().firstDispatchedAt()).isEqualTo(unknown.firstDispatchedAt());assertThat(c.payStep().retryBefore()).isEqualTo(unknown.retryBefore());
            assertThat(c.fixture.graph.http.calls().stream().filter(call->call.method().equals("POST")&&call.path().equals(PAY)).toList()).hasSize(2).allSatisfy(call->assertThat(call.header("Idempotency-Key")).isEqualTo(unknown.operationKey()));
        }
    }

    @Test
    void unknownPayCannotBeEndedByLaterActionStatusOrReopenedAfterTheOriginalWindow() throws Exception {
        try(var c=new RecoveryCase()){
            c.noAttemptGraph();c.fixture.graph.http.disconnectNext("POST",PAY);assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN);
            c.actionGraph();assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(c.receipts).isEmpty();
            var step=c.payStep();var first=Instant.now().minus(Duration.ofHours(24));c.fixture.steps.replace(step,step.status(),null,first,first.plus(Duration.ofHours(23)),null,null);
            c.noAttemptGraph();assertThat(c.fixture.adapter.recover(c.command()).outcome()).isEqualTo(Outcome.UNKNOWN);
            assertThat(c.fixture.graph.http.requestCount("POST",PAY)).isEqualTo(1);assertThat(c.receipts).isEmpty();
        }
    }

    static final class RecoveryCase implements AutoCloseable {
        final StripeSubscriptionCreateTest fixture=new StripeSubscriptionCreateTest();
        final SubscriptionRecoveryProofStore proofs=mock(SubscriptionRecoveryProofStore.class);
        final Map<UUID,SubscriptionRecoveryProofStore.Receipt> receipts=new HashMap<>();
        final SubscriptionRecoveryProofStore.Origin origin;
        BillingRecord bill;
        Instant grace=StripeBillingSnapshotMapperTest.START.plusSeconds(259200);
        dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore planHistory;
        com.google.gson.JsonObject retainedInvoice,retainedLines,retainedAllocation,retainedIntent,retainedCharge;
        dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext publicContext;
        dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant grant;
        final dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore actionStore=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.class);
        dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext confirmationContext;
        boolean durableConfirmation,consumeAfterStaleReplayCheck,consumeInFinalActionPhase;int actionTransactions;
        RecoveryCase() throws Exception {
            fixture.setup();fixture.create();
            var source=fixture.operation;var sourceStep=fixture.steps.only();
            origin=new SubscriptionRecoveryProofStore.Origin(source.id(),sourceStep.id());
            var s=fixture.graph.local;var id=UUID.randomUUID();
            bill=new BillingRecord(id,s.siteId(),s.id(),s.paymentAccountId(),"in_owned",BillingRecord.Kind.RENEWAL,s.billingPrice(),null,s.currentPeriod(),1000,"EUR",BillingRecord.Settlement.OPEN,0,StripeBillingSnapshotMapperTest.START,StripeBillingSnapshotMapperTest.START);
            fixture.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),Subscription.Status.PAST_DUE,s.providerReference(),s.currentPeriod(),StripeBillingSnapshotMapperTest.START,null,null,null,new Subscription.Debt(id,s.currentPeriod(),grace),null,s.revision(),s.createdAt(),s.updatedAt(),null);
            var op=UUID.randomUUID();var now=Instant.now();
            fixture.operation=new SubscriptionOperation(op,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,"customer:owned","recover-key","v1.recover."+id+"."+"a".repeat(64),"subscription:"+fixture.account.id().value()+":"+op+":RECOVER",s.revision(),1,s.providerReference(),null,now,now,1);
            var original=new SubscriptionOperation(source.id(),source.siteId(),source.customerId(),source.subscriptionId(),source.kind(),SubscriptionOperation.Status.SUCCEEDED,source.authorityScope(),source.keyDigest(),source.fingerprint(),source.providerOperationKey(),source.expectedRevision(),source.policyVersion(),"sub_owned",null,source.createdAt(),source.updatedAt(),source.version());
            when(fixture.operations.find(eq(s.siteId()),eq(s.id()),any())).thenAnswer(i->fixture.operation.id().equals(i.getArgument(2))?Optional.of(fixture.operation):original.id().equals(i.getArgument(2))?Optional.of(original):Optional.empty());
            when(fixture.operations.unresolved(any(),any())).thenAnswer(i->List.of(fixture.operation));
            when(proofs.origin(fixture.account.id(),s.id())).thenReturn(Optional.of(origin));
            when(proofs.bill(fixture.account.id(),s.id(),id)).thenReturn(Optional.of(bill));
            when(proofs.receipt(any(),any())).thenAnswer(i->Optional.ofNullable(receipts.get(i.getArgument(1))));
            when(proofs.available(any(),any(),anyString(),any())).thenReturn(true);
            when(proofs.associate(any(),any(),any(),any(),anyString(),anyString())).thenAnswer(i->{var saved=receipt(null,i.getArgument(4),i.getArgument(5));receipts.put(fixture.operation.id(),saved);return saved;});
            when(proofs.recordPay(any(),any(),any(),any(),any(),anyString(),any(),any())).thenAnswer(i->{ProviderOperation step=i.getArgument(4);fixture.steps.acknowledge(step.id(),i.getArgument(5),step.version(),"in_owned");var saved=receipt(step.id(),i.getArgument(6),i.getArgument(7));receipts.put(fixture.operation.id(),saved);return saved;});
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"recoveryProofs",proofs);
            var g=fixture.graph;g.subscription.addProperty("status","past_due");g.subscription.addProperty("default_payment_method","pm_owned");
            g.invoice.addProperty("status","open");g.invoice.addProperty("amount_paid",0);g.invoice.addProperty("amount_remaining",1000);
            g.invoice.addProperty("billing_reason","subscription_cycle");
            g.invoice.addProperty("attempted",false);g.invoice.addProperty("attempt_count",0);g.invoice.addProperty("auto_advance",false);g.invoice.add("next_payment_attempt",com.google.gson.JsonNull.INSTANCE);
            g.allocation.addProperty("status","open");g.allocation.addProperty("is_default",true);g.allocation.addProperty("amount_paid",0);
            g.intent.addProperty("status","requires_payment_method");g.intent.addProperty("amount_received",0);g.intent.remove("latest_charge");g.intent.addProperty("client_secret","pi_owned_secret_fixture");
        }
        SubscriptionRecoveryProofStore.Receipt receipt(UUID pay,String allocation,String intent){return new SubscriptionRecoveryProofStore.Receipt(fixture.operation.id(),fixture.local.id(),fixture.account.id(),bill.id(),"sub_owned","in_owned",origin.operation(),origin.step(),pay,allocation,intent);}
        Command command(){return new Command(fixture.account,fixture.operation,fixture.local.billingPrice(),null,"sub_owned","in_owned",null,false,false);}
        dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingActionAdapter actions(){
            var ownership=(dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership)org.springframework.test.util.ReflectionTestUtils.getField(fixture.adapter,"ownership");org.springframework.test.util.ReflectionTestUtils.setField(ownership,"recoveryProofs",proofs);
            when(proofs.available(any(),any(),anyString(),any())).thenAnswer(i->grant==null||grant.consumedAt()==null);
            when(actionStore.current(any(),any())).thenAnswer(i->Optional.ofNullable(grant));when(actionStore.billing(anyString())).thenAnswer(i->Optional.ofNullable(grant).filter(g->g.tokenDigest().equals(i.getArgument(0))));
            doAnswer(i->{grant=i.getArgument(0);return null;}).when(actionStore).reserveBilling(any());when(fixture.operations.lock(any())).thenAnswer(i->fixture.operation);
            var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
            var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return fixture.graph.http.client();}};
            var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> action){return action.get();}public <T>T requiresNew(java.util.function.Supplier<T> action){if(++actionTransactions==2&&consumeInFinalActionPhase){consumeInFinalActionPhase=false;consumeGrant();}return action.get();}public boolean active(){return false;}};
            var actual=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingActionAdapter(ownership,new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))),credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),actionStore,fixture.operations,tx,Clock.systemUTC());
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"billingActions",(java.util.function.Supplier<dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingActionAdapter>)()->actual);return actual;
        }
        dev.genericpayments.platform.subscriptions.application.service.SubscriptionActionService confirmations(){
            var s=fixture.local;var locks=mock(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort.class);var policies=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort.class);var authority=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort.class);
            when(locks.findByIdForUpdate(s.paymentAccountId())).thenReturn(Optional.of(fixture.account));when(policies.load(s.siteId())).thenReturn(new SubscriptionPolicy(s.siteId(),1,true,3,false,false,false,Set.of()));when(fixture.subscriptions.find(s.siteId(),s.id())).thenAnswer(i->Optional.of(fixture.local));when(fixture.subscriptions.lock(s.siteId(),s.id())).thenAnswer(i->fixture.local);when(fixture.operations.save(any(),anyLong())).thenAnswer(i->{fixture.operation=i.getArgument(0);return fixture.operation;});
            var session=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(new dev.genericpayments.platform.customers.domain.CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),"https://shop.test",Set.of(dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE),Instant.now().plusSeconds(600));confirmationContext=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(session),s.siteId(),s.customerId(),session.origin(),"same-confirm-key","same-confirm-request");
            when(actionStore.replay(any(),anyString(),anyString(),anyString())).thenAnswer(i->{if(consumeAfterStaleReplayCheck&&!durableConfirmation){consumeAfterStaleReplayCheck=false;consumeGrant();return false;}return durableConfirmation;});
            var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> action){return action.get();}public <T>T requiresNew(java.util.function.Supplier<T> action){return action.get();}public boolean active(){return false;}};
            var phases=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction(tx,fixture.operations,fixture.subscriptions,authority,policies,locks,actionStore,mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPortalStore.class),mock(dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore.class),Clock.systemUTC());
            return new dev.genericpayments.platform.subscriptions.application.service.SubscriptionActionService(phases,a->fixture.adapter);
        }
        dev.genericpayments.platform.subscriptions.application.port.in.ConfirmSubscriptionActionUseCase.Command confirmCommand(String token){return new dev.genericpayments.platform.subscriptions.application.port.in.ConfirmSubscriptionActionUseCase.Command(confirmationContext,fixture.local.id(),fixture.operation.id(),token);}
        void bindGrant(){var g=grant;var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)confirmationContext.actor()).session();grant=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(g.id(),g.site(),g.customer(),g.account(),g.subscription(),g.operation(),g.keyVersion(),g.binding(),g.tokenDigest(),g.issuedAt(),g.expiresAt(),g.consumedAt(),g.supersededAt(),session.sessionId().value(),session.origin(),g.expiresAt());}
        void consumeGrant(){var g=grant;grant=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(g.id(),g.site(),g.customer(),g.account(),g.subscription(),g.operation(),g.keyVersion(),g.binding(),g.tokenDigest(),g.issuedAt(),g.expiresAt(),Instant.now(),g.supersededAt(),g.sessionId(),g.origin(),g.clientExpiresAt());durableConfirmation=true;var o=fixture.operation;fixture.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.PROCESSING,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),Instant.now(),o.version()+1);}
        dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryService publicService(){
            var s=fixture.local;var locks=mock(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort.class);
            var sites=mock(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class);
            var bills=mock(dev.genericpayments.platform.subscriptions.application.port.out.BillingRecordStore.class);
            var policies=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort.class);
            var authority=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort.class);
            var registry=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry.class);
            var site=fixture.sites.findById(s.siteId());var customer=fixture.customerStore.findById(s.siteId(),s.customerId());
            when(locks.findByIdForUpdate(s.paymentAccountId())).thenReturn(Optional.of(fixture.account));when(sites.findByIdForUpdate(s.siteId())).thenReturn(site);
            when(fixture.customerStore.findByIdForUpdate(s.siteId(),s.customerId())).thenReturn(customer);
            when(fixture.subscriptions.find(s.siteId(),s.id())).thenAnswer(i->Optional.of(fixture.local));when(fixture.subscriptions.lock(s.siteId(),s.id())).thenAnswer(i->fixture.local);
            when(bills.lock(s.siteId(),bill.id())).thenReturn(bill);when(policies.load(s.siteId())).thenReturn(new SubscriptionPolicy(s.siteId(),1,true,3,false,false,false,Set.of()));
            when(registry.forAccount(fixture.account)).thenReturn(fixture.adapter);
            when(fixture.operations.claim(any())).thenAnswer(i->{fixture.operation=i.getArgument(0);return new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore.Claim(fixture.operation,true);});
            when(fixture.operations.lock(any())).thenAnswer(i->fixture.operation);
            when(fixture.operations.save(any(),anyLong())).thenAnswer(i->{fixture.operation=i.getArgument(0);return fixture.operation;});
            var session=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(new dev.genericpayments.platform.customers.domain.CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),"https://shop.test",Set.of(),Instant.now().plusSeconds(600));
            publicContext=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(session),s.siteId(),s.customerId(),session.origin(),"recover-public","recover-public-request");
            var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> action){return action.get();}public <T>T requiresNew(java.util.function.Supplier<T> action){return action.get();}public boolean active(){return false;}};
            var transaction=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryTransaction(tx,fixture.operations,fixture.subscriptions,bills,authority,policies,locks,sites,fixture.customerStore,registry,Clock.systemUTC());
            return new dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryService(transaction,registry);
        }
        dev.genericpayments.platform.subscriptions.application.port.in.RecoverSubscriptionUseCase.Command publicCommand(){return new dev.genericpayments.platform.subscriptions.application.port.in.RecoverSubscriptionUseCase.Command(publicContext,fixture.local.id(),bill.id());}
        void openReadGraph(){if(retainedInvoice!=null){retainedGraph(false);return;}head();fixture.graph.payments();method();}
        void cashGraph(){var g=fixture.graph;g.invoice.addProperty("status","paid");g.invoice.addProperty("amount_paid",1000);g.invoice.addProperty("amount_remaining",0);g.allocation.addProperty("status","paid");g.allocation.addProperty("amount_paid",1000);g.intent.addProperty("status","succeeded");g.intent.addProperty("amount_received",1000);g.intent.addProperty("latest_charge","ch_owned");fixture.identity();g.graph();method();}
        void head(){fixture.identity();fixture.graph.head();fixture.graph.lines();}
        void actionGraph(){if(retainedInvoice!=null){retainedGraph(true);return;}head();fixture.graph.payments();method();fixture.graph.http.respond("GET","/v1/payment_intents/pi_owned",fixture.graph.intent.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,fixture.graph.allocation));}
        void afterPayActionGraph(){fixture.graph.head();fixture.graph.lines();fixture.graph.payments();method();fixture.graph.http.respond("GET","/v1/payment_intents/pi_owned",fixture.graph.intent.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,fixture.graph.allocation));}
        void noAttemptGraph(){head();fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false));fixture.graph.http.respond("GET","/v1/payment_methods/pm_owned","{\"id\":\"pm_owned\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"brand\":\"visa\"}}");}
        void method(){fixture.graph.http.respond("GET","/v1/payment_methods/pm_owned","{\"id\":\"pm_owned\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"brand\":\"visa\"}}");}
        void retainAcceptedProration(){
            var plan=new StripeSubscriptionPlanChangeTest();plan.borrowFixture(fixture);fixture.graph.subscription.addProperty("status","active");
            var change=plan.changeCommand();plan.identityAndCurrent();plan.updateResult();assertThat(fixture.adapter.change(change).outcome()).isEqualTo(Outcome.ACCEPTED);
            var result=plan.results.get(change.operation().id());var quote=plan.quotes.get(result.quote());planHistory=plan.proofs;
            when(planHistory.prorations(any(),any())).thenReturn(List.of(new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration(quote,result)));
            when(fixture.operations.find(fixture.local.siteId(),fixture.local.id(),change.operation().id())).thenReturn(Optional.of(change.operation()));
            retainedInvoice=com.google.gson.JsonParser.parseString(plan.preview()).getAsJsonObject();retainedLines=retainedInvoice.remove("lines").getAsJsonObject();
            retainedInvoice.addProperty("id","in_change");retainedInvoice.addProperty("billing_reason","subscription_update");retainedInvoice.addProperty("status","paid");retainedInvoice.addProperty("amount_paid",500);retainedInvoice.addProperty("amount_remaining",0);
            for(var line:retainedLines.getAsJsonArray("data")){var l=line.getAsJsonObject();l.addProperty("invoice","in_change");l.addProperty("id",l.getAsJsonObject("pricing").getAsJsonObject("price_details").get("price").getAsString().equals("price_next")?result.debitLine():result.creditLine());}
            var g=fixture.graph;retainedAllocation=g.allocation.deepCopy();retainedAllocation.addProperty("id","inpay_change");retainedAllocation.addProperty("invoice","in_change");retainedAllocation.addProperty("status","paid");retainedAllocation.addProperty("amount_requested",500);retainedAllocation.addProperty("amount_paid",500);retainedAllocation.addProperty("is_default",true);retainedAllocation.getAsJsonObject("payment").addProperty("payment_intent","pi_change");
            retainedIntent=g.intent.deepCopy();retainedIntent.addProperty("id","pi_change");retainedIntent.addProperty("status","succeeded");retainedIntent.addProperty("amount",500);retainedIntent.addProperty("amount_received",500);retainedIntent.addProperty("latest_charge","ch_change");retainedIntent.remove("client_secret");
            retainedCharge=g.charge.deepCopy();retainedCharge.addProperty("id","ch_change");retainedCharge.addProperty("payment_intent","pi_change");retainedCharge.addProperty("amount",500);retainedCharge.addProperty("amount_captured",500);
            var old=fixture.local;var period=new Subscription.Period(old.currentPeriod().end(),old.currentPeriod().end().plusSeconds(2678400));grace=period.start().plusSeconds(259200);
            bill=new BillingRecord(bill.id(),old.siteId(),old.id(),old.paymentAccountId(),"in_owned",BillingRecord.Kind.RENEWAL,plan.target,null,period,2000,"EUR",BillingRecord.Settlement.OPEN,0,period.start(),period.start());
            fixture.local=new Subscription(old.id(),old.siteId(),old.customerId(),old.productId(),old.paymentAccountId(),plan.target,plan.target,Subscription.Status.PAST_DUE,old.providerReference(),period,old.paidThrough(),null,null,null,new Subscription.Debt(bill.id(),period,grace),null,old.revision()+1,old.createdAt(),period.start(),null);
            when(proofs.bill(fixture.account.id(),fixture.local.id(),bill.id())).thenReturn(Optional.of(bill));
            g.subscription.addProperty("status","past_due");g.item().addProperty("current_period_start",period.start().getEpochSecond());g.item().addProperty("current_period_end",period.end().getEpochSecond());var price=g.item().getAsJsonObject("price");price.addProperty("id","price_next");price.addProperty("unit_amount",2000);price.getAsJsonObject("metadata").addProperty("local_price_id",plan.target.priceId().value().toString());
            for(String field:List.of("amount_due","amount_remaining","total","subtotal"))g.invoice.addProperty(field,2000);g.line.addProperty("amount",2000);g.line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_next");g.line.getAsJsonObject("period").addProperty("start",period.start().getEpochSecond());g.line.getAsJsonObject("period").addProperty("end",period.end().getEpochSecond());g.allocation.addProperty("amount_requested",2000);g.intent.addProperty("amount",2000);
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.systemUTC());org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.systemUTC());
        }
        void retainedGraph(boolean action){
            fixture.identity();var g=fixture.graph;g.base();g.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,retainedInvoice,g.invoice));g.http.respond("GET","/v1/invoices/in_change/lines",retainedLines.toString());g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,retainedAllocation));g.http.respond("GET","/v1/payment_intents/pi_change",retainedIntent.toString());g.http.respond("GET","/v1/charges/ch_change",retainedCharge.toString());g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,retainedAllocation));g.lines();g.payments();method();
            if(action){g.http.respond("GET","/v1/payment_intents/pi_owned",g.intent.toString());g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,g.allocation));}
        }
        ProviderOperation payStep(){return fixture.steps.rows.values().stream().filter(s->s.kind()==ProviderOperation.Kind.INVOICE_PAY).findFirst().orElseThrow();}
        public void close(){fixture.close();}
    }

    @Test
    void explicitPayWireUsesOriginalInvoiceMethodAndKeyWithoutAnotherCashSource() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("POST", PAY, paidInvoice());
            var invoice = http.client().v1().invoices().pay("in_owned",
                InvoicePayParams.builder().setPaymentMethod("pm_owned").build(),
                RequestOptions.builder().setIdempotencyKey("original-recover-pay-wire").build());

            assertThat(invoice.getId()).isEqualTo("in_owned");
            assertThat(invoice.getCustomer()).isEqualTo("cus_owned");
            assertThat(http.calls()).singleElement().satisfies(call -> {
                assertThat(call.method()).isEqualTo("POST");
                assertThat(call.path()).isEqualTo(PAY);
                assertThat(call.form()).containsExactlyEntriesOf(Map.of("payment_method", "pm_owned"));
                assertThat(call.header("Idempotency-Key")).isEqualTo("original-recover-pay-wire");
                assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
                assertThat(call.header("Stripe-Account")).isNull();
            });
        }
    }

    @Test
    void disconnectedPayHasNoSdkRetryAndExplicitWireReplayRetainsOriginalBodyAndKey() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            var params = InvoicePayParams.builder().setPaymentMethod("pm_owned").build();
            var options = RequestOptions.builder().setIdempotencyKey("original-unknown-pay-wire").build();
            http.disconnectNext("POST", PAY);
            assertThatThrownBy(() -> http.client().v1().invoices().pay("in_owned", params, options))
                .isInstanceOf(ApiConnectionException.class);
            assertThat(http.requestCount("POST", PAY)).isEqualTo(1);

            // Not platform retry admission: lease/23h/resource/UNKNOWN permission tests are deferred.
            http.respond("POST", PAY, paidInvoice());
            http.client().v1().invoices().pay("in_owned", params, options);
            assertThat(http.requestCount("POST", PAY)).isEqualTo(2);
            assertThat(http.calls().get(1).form()).isEqualTo(http.calls().getFirst().form());
            assertThat(http.calls()).allSatisfy(call ->
                assertThat(call.header("Idempotency-Key")).isEqualTo("original-unknown-pay-wire"));
        }
    }

    @Test
    void alreadyPaidObservationKeepsExactInvoiceAndCashIdentityWithoutPayOrConfirmation() throws Exception {
        var fixture = new StripeSubscriptionCreateTest();
        try {
            fixture.setup();
            fixture.local = fixture.graph.local;
            fixture.identity();
            fixture.graph.graph();
            var snapshot = fixture.adapter.retrieve(new Lookup(fixture.account, fixture.local.id(), "sub_owned", null));

            assertThat(snapshot.invoices()).singleElement().satisfies(invoice -> {
                assertThat(invoice.reference()).isEqualTo("in_owned");
                assertThat(invoice.settlement()).isEqualTo(SettlementKind.CASH);
                assertThat(invoice.cashObjects()).singleElement().satisfies(cash -> {
                    assertThat(cash.reference()).isEqualTo("pi_owned");
                    assertThat(cash.amountMinor()).isEqualTo(1000);
                });
            });
            assertThat(snapshot.recoveryDisposition("in_owned")).isEqualTo(RecoveryDisposition.BLOCKED);
            assertThat(snapshot.operationObservation()).isNull();
            assertOnlyReads(fixture);
        } finally {
            fixture.close();
        }
    }

    @Test
    void openAllocationWithoutFreshAttemptProofCannotAuthorizeRetry() throws Exception {
        var fixture = new StripeSubscriptionCreateTest();
        try {
            fixture.setup();
            fixture.local = fixture.graph.local;
            var graph = fixture.graph;
            graph.subscription.addProperty("status", "past_due");
            graph.invoice.addProperty("status", "open");
            graph.invoice.addProperty("amount_paid", 0);
            graph.invoice.addProperty("amount_remaining", 1000);
            graph.allocation.addProperty("status", "open");
            graph.allocation.addProperty("is_default", true);
            graph.allocation.addProperty("amount_paid", 0);
            fixture.identity();
            graph.head();
            graph.lines();
            graph.payments();

            var snapshot = fixture.adapter.retrieve(new Lookup(fixture.account, fixture.local.id(), "sub_owned", null));
            assertThat(snapshot.invoices()).singleElement().satisfies(invoice -> {
                assertThat(invoice.reference()).isEqualTo("in_owned");
                assertThat(invoice.cashObjects()).isEmpty();
            });
            assertThat(snapshot.recoveryDisposition("in_owned")).isEqualTo(RecoveryDisposition.BLOCKED);
            assertOnlyReads(fixture);
        } finally {
            fixture.close();
        }
    }

    @Test
    void foreignInvoiceCannotBecomeRecoveryAuthorityAndCausesNoFinancialWrite() throws Exception {
        var fixture = new StripeSubscriptionCreateTest();
        try {
            fixture.setup();
            fixture.local = fixture.graph.local;
            fixture.graph.invoice.addProperty("customer", "cus_foreign");
            fixture.identity();
            fixture.graph.graph();
            assertThatThrownBy(() -> fixture.adapter.retrieve(
                new Lookup(fixture.account, fixture.local.id(), "sub_owned", null)))
                .isInstanceOf(PaymentApplicationException.class);
            assertOnlyReads(fixture);
        } finally {
            fixture.close();
        }
    }

    @Test
    void safeUnconsumedOriginalActionRemainsSameActionWithoutInvoicePay() throws Exception {
        var fixture = new StripeBillingActionAdapterTest();
        try {
            fixture.setup();
            var start = fixture.fixture.graph.http.calls().size();
            fixture.graph();
            var first = fixture.adapter.nextAction(fixture.fixture.lookup());
            assertThat(first).isNotNull();
            var grant = fixture.row;
            fixture.graph();
            var same = fixture.adapter.nextAction(fixture.fixture.lookup());

            assertThat(same).isEqualTo(first);
            assertThat(fixture.row).isEqualTo(grant);
            assertThat(grant.consumedAt()).isNull();
            assertThat(grant.binding().invoiceReference()).isEqualTo("in_owned");
            assertThat(grant.binding().intentReference()).isEqualTo("pi_owned");
            assertThat(fixture.fixture.graph.http.calls().subList(start, fixture.fixture.graph.http.calls().size()))
                .allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
            assertThat(fixture.fixture.graph.http.requestCount("POST", PAY)).isZero();
            // Original CREATE action precondition only: this does not claim a new RECOVER owner/grant.
        } finally {
            fixture.close();
        }
    }

    private static void assertOnlyReads(StripeSubscriptionCreateTest fixture) {
        assertThat(fixture.graph.http.calls()).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
        assertThat(fixture.graph.http.requestCount("POST", PAY)).isZero();
        assertThat(fixture.graph.http.requestCount("POST", "/v1/payment_intents/pi_owned/confirm")).isZero();
        assertThat(fixture.steps.rows).isEmpty();
    }

    private static String paidInvoice() {
        return "{\"id\":\"in_owned\",\"object\":\"invoice\",\"livemode\":false,"
            + "\"customer\":\"cus_owned\",\"status\":\"paid\",\"currency\":\"eur\","
            + "\"amount_due\":1000,\"amount_paid\":1000,\"amount_remaining\":0}";
    }
}
