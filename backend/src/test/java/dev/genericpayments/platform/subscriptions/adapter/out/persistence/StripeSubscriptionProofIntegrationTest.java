package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.customers.domain.CustomerStatus;
import dev.genericpayments.platform.payments.application.port.out.SavePaymentPort;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Hosted PostgreSQL only. Uses the existing application/Flyway/container fixture; no provider HTTP. */
class StripeSubscriptionProofIntegrationTest extends SubscriptionFactFixture {
    @Autowired SubscriptionBillingProofStore proofs;
    @Autowired ProviderOperationStore steps;
    @Autowired SavePaymentPort payments;
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired dev.genericpayments.platform.subscriptions.application.service.SubscriptionResumeReceiptTransaction resumeReceipts;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore actions;
    @Autowired dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort bindings;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObservationPort observations;
    Subscription original;
    SubscriptionOperation createOperation;
    static final long START = INITIAL_TIME.getEpochSecond();
    static final long END = START + Duration.ofDays(3).toSeconds();
    static final String TRIAL_COLUMNS = "operation_id,site_id,customer_id,subscription_id,payment_account_id,requested_end,step_id,subscription_reference,item_reference,invoice_reference,trial_start";
    static final String SETUP_COLUMNS = "operation_id,site_id,customer_id,subscription_id,payment_account_id,operation_kind,step_id,setup_reference,method_reference";
    static final String RESUME_COLUMNS = "operation_id,site_id,customer_id,subscription_id,payment_account_id,step_id,subscription_reference,invoice_reference,period_start,period_end";

    @BeforeEach void stripeProofFixture() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_proofs',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        account = accounts.findById(account.id()).orElseThrow();
        var admitted = newOriginal();
        original = admitted.subscription();
        createOperation = admitted.operation();
    }

    @Test void concurrentTrialRequestsKeepOnePersistedEndAcrossUnknownAndRestart() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return proofs.reserveTrial(original, createOperation, END); });
            var second = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return proofs.reserveTrial(original, createOperation, END + 86400); });
            var winner = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(winner.requestedEnd()).isIn(END, END + 86400);
            commands.recordReply(createOperation.id(), new Reply(Outcome.UNKNOWN, null, null));
            assertThat(proofs.reserveTrial(original, op(createOperation.id()), END + 172800)).isEqualTo(winner);
            assertThat(new SubscriptionBillingProofPersistenceAdapter(jdbc).trial(account.id(), original.id())).contains(winner);
        }
        var eligibility = trials.find(site, customer.id(), original.productId()).orElseThrow();
        assertThat(eligibility.reservedByOperation()).isEqualTo(createOperation.id());
        assertThat(eligibility.consumedAt()).isNull();
        assertThat(sub(original.id()).entitledPrice()).isNull();
        assertThat(sub(original.id()).paidThrough()).isNull();
        assertThat(count("stripe_subscription_trial_proofs")).isEqualTo(1);
        assertThat(count("payments")).isZero();
    }

    @Test void trialReservationRequiresTheSavedTrialDecisionAndExactEligibilityOwner() {
        price = price(0);
        var paid = commands.reserveCreate(command("paid-create"));
        evidenceConflict(() -> proofs.reserveTrial(paid.subscription(), paid.operation(), END));
        var altered = new SubscriptionOperation(createOperation.id(), site, customer.id(), original.id(), createOperation.kind(), createOperation.status(),
            createOperation.authorityScope(), createOperation.keyDigest(), "v1.trial." + "0".repeat(64), createOperation.providerOperationKey(),
            createOperation.expectedRevision(), createOperation.policyVersion(), null, null, createOperation.createdAt(), createOperation.updatedAt(), createOperation.version());
        evidenceConflict(() -> proofs.reserveTrial(original, altered, END));
        trials.releaseConfirmedFailure(createOperation.id());
        evidenceConflict(() -> proofs.reserveTrial(original, createOperation, END));
        assertThat(count("stripe_subscription_trial_proofs")).isZero();
    }

    @Test void trialResultRequiresTheExactAcknowledgedOriginalStepAndBindsOnlyOnce() {
        var pending = proofs.reserveTrial(original, createOperation, END);
        var reserved = reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE);
        sqlState("23503", () -> proofs.bindTrial(pending, reserved.id(), "sub_trial", "si_trial", "in_trial", START));
        var acknowledged = acknowledge(reserved, "sub_trial");
        sqlState("23503", () -> proofs.bindTrial(pending, acknowledged.id(), "sub_foreign", "si_trial", "in_trial", START));
        var staleExpected = new SubscriptionBillingProofStore.Trial(pending.operation(), pending.subscription(), pending.account(), END + 1, null, null, null, null, null);
        evidenceConflict(() -> proofs.bindTrial(staleExpected, acknowledged.id(), "sub_trial", "si_trial", "in_trial", START));
        assertThat(proofs.trial(account.id(), original.id())).contains(pending);
        var verified = proofs.bindTrial(pending, acknowledged.id(), "sub_trial", "si_trial", "in_trial", START);
        assertThat(verified.verified()).isTrue();
        assertThat(verified.period()).isEqualTo(new Subscription.Period(Instant.ofEpochSecond(START), Instant.ofEpochSecond(END)));
        assertThat(proofs.bindTrial(pending, acknowledged.id(), "sub_trial", "si_trial", "in_trial", START)).isEqualTo(verified);
        evidenceConflict(() -> proofs.bindTrial(pending, acknowledged.id(), "sub_trial", "si_other", "in_other", START + 1));
        sqlState("23514", () -> jdbc.update("update stripe_subscription_trial_proofs set requested_end=requested_end+1 where operation_id=?", createOperation.id()));
        sqlState("23514", () -> jdbc.update("update stripe_subscription_trial_proofs set step_id=null,subscription_reference=null,item_reference=null,invoice_reference=null,trial_start=null where operation_id=?", createOperation.id()));
        assertThat(proofs.trial(account.id(), original.id())).contains(verified);
    }

    @Test void trialSqlRejectsPartialResultGroupsInvalidSecondsAndMalformedNativeReferences() {
        proofs.reserveTrial(original, createOperation, END);
        var step = acknowledge(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_trial");
        for (String assignment : List.of("step_id='" + step.id() + "'", "subscription_reference='sub_trial'", "item_reference='si_trial'", "invoice_reference='in_trial'", "trial_start=" + START)) {
            sqlState("23514", () -> jdbc.update("update stripe_subscription_trial_proofs set " + assignment + " where operation_id=?", createOperation.id()));
        }
        sqlState("23514", () -> proofs.bindTrial(proofs.trial(account.id(), original.id()).orElseThrow(), step.id(), "sub_trial", "si_trial", "in_trial", END));
        sqlState("23514", () -> proofs.bindTrial(proofs.trial(account.id(), original.id()).orElseThrow(), step.id(), "sub_trial", "si_bad_id", "in_trial", START));
        assertThat(proofs.trial(account.id(), original.id()).orElseThrow().verified()).isFalse();
    }

    @Test void trialInsertChecksCompositeOwnersOriginalKindStepResourceAndInvoiceUniqueness() {
        var step = acknowledge(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_trial");
        var other = newOriginal();
        var otherStep = acknowledge(reserve(other.operation(), ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_other");
        Object[] valid = trialRow(original, createOperation, step, "si_trial", "in_shared");
        rejectOwnerSubstitutions("stripe_subscription_trial_proofs", TRIAL_COLUMNS, valid, other.subscription());
        var wrongStep = valid.clone(); wrongStep[6] = otherStep.id(); wrongStep[7] = otherStep.providerReference();
        sqlState("23503", () -> insert("stripe_subscription_trial_proofs", TRIAL_COLUMNS, wrongStep));
        var badEnd = valid.clone(); badEnd[5] = 0L;
        sqlState("23514", () -> insert("stripe_subscription_trial_proofs", TRIAL_COLUMNS, badEnd));
        var badStart = valid.clone(); badStart[10] = 0L;
        sqlState("23514", () -> insert("stripe_subscription_trial_proofs", TRIAL_COLUMNS, badStart));
        finish(createOperation);
        var setup = operation(SubscriptionOperation.Kind.SETUP);
        var wrongKind = trialRow(original, setup, step, "si_wrong", "in_wrong");
        sqlState("23503", () -> insert("stripe_subscription_trial_proofs", TRIAL_COLUMNS, wrongKind));
        insert("stripe_subscription_trial_proofs", TRIAL_COLUMNS, valid);
        sqlState("23505", () -> insert("stripe_subscription_trial_proofs", TRIAL_COLUMNS, trialRow(other.subscription(), other.operation(), otherStep, "si_other", "in_shared")));
    }

    @Test void setupRequiresOriginalAcknowledgedSetiAndRejectsForeignOwnersKindsAndReferences() {
        var reserved = reserve(createOperation, ProviderOperation.Kind.SETUP_INTENT_CREATE);
        sqlState("23503", () -> proofs.recordSetup(original, createOperation, reserved.id(), "seti_owned"));
        var acknowledged = acknowledge(reserved, "seti_owned");
        sqlState("23503", () -> proofs.recordSetup(original, createOperation, acknowledged.id(), "seti_other"));
        var other = newOriginal();
        var otherStep = acknowledge(reserve(other.operation(), ProviderOperation.Kind.SETUP_INTENT_CREATE), "seti_other");
        var valid = setupRow(original, createOperation, acknowledged);
        rejectOwnerSubstitutions("stripe_subscription_setup_proofs", SETUP_COLUMNS, valid, other.subscription());
        var wrongResource = valid.clone(); wrongResource[6] = otherStep.id(); wrongResource[7] = otherStep.providerReference();
        sqlState("23503", () -> insert("stripe_subscription_setup_proofs", SETUP_COLUMNS, wrongResource));
        var wrongKind = valid.clone(); wrongKind[5] = "SETUP";
        sqlState("23503", () -> insert("stripe_subscription_setup_proofs", SETUP_COLUMNS, wrongKind));
        var wrongStepKind = valid.clone(); wrongStepKind[6] = acknowledge(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_owned").id();
        sqlState("23503", () -> insert("stripe_subscription_setup_proofs", SETUP_COLUMNS, wrongStepKind));
        var saved = proofs.recordSetup(original, createOperation, acknowledged.id(), "seti_owned");
        assertThat(proofs.recordSetup(original, createOperation, acknowledged.id(), "seti_owned")).isEqualTo(saved);
        assertThat(proofs.setupReference(account.id(), "seti_owned")).contains(saved);
    }

    @Test void concurrentMethodBindingHasOneImmutableWinnerAndSupportsExactReplay() throws Exception {
        var step = acknowledge(reserve(createOperation, ProviderOperation.Kind.SETUP_INTENT_CREATE), "seti_owned");
        var pending = proofs.recordSetup(original, createOperation, step.id(), "seti_owned");
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> bindAfterGate(gate, pending, "pm_first"));
            var second = pool.submit(() -> bindAfterGate(gate, pending, "pm_second"));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        var winner = proofs.setup(account.id(), createOperation.id()).orElseThrow();
        assertThat(winner.method()).isIn("pm_first", "pm_second");
        assertThat(proofs.bindMethod(pending, winner.method())).isEqualTo(winner);
        assertThat(proofs.method(account.id(), original.id(), winner.method())).contains(winner);
        assertThat(proofs.method(account.id(), UUID.randomUUID(), winner.method())).isEmpty();
        sqlState("23514", () -> jdbc.update("update stripe_subscription_setup_proofs set method_reference=null where operation_id=?", createOperation.id()));
        sqlState("23514", () -> jdbc.update("update stripe_subscription_setup_proofs set setup_reference='seti_replaced' where operation_id=?", createOperation.id()));
    }

    @Test void twoOwnedSetupMethodUpdatesAndResumeCanAcknowledgeTheSameRealSubscription() {
        existingSubscription();
        var first = methodUpdate("One");
        var second = methodUpdate("Two");
        var resume = operation(SubscriptionOperation.Kind.RESUME);
        var resumed = acknowledge(reserve(resume, ProviderOperation.Kind.SUBSCRIPTION_UPDATE), "sub_owned");
        var result = proofs.recordResume(sub(original.id()), resume, resumed.id(), "in_resume", resumePeriod());
        assertThat(List.of(first, second, resumed)).extracting(ProviderOperation::providerReference).containsOnly("sub_owned");
        assertThat(List.of(first, second, resumed)).extracting(ProviderOperation::resourceId).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='SUBSCRIPTION_UPDATE' and provider_reference='sub_owned'", Integer.class)).isEqualTo(3);
        assertThat(proofs.recordResume(sub(original.id()), resume, resumed.id(), "in_resume", resumePeriod())).isEqualTo(result);
        assertThat(result.operation()).isEqualTo(resume.id());
        assertThat(sub(original.id()).paidThrough()).isNull();
        assertThat(count("payments")).isZero();
    }

    @Test void resumeSqlChecksExactOriginalKindOwnerStepReferenceAndPositiveGroupedPeriod() {
        existingSubscription();
        var resume = operation(SubscriptionOperation.Kind.RESUME);
        var reserved = reserve(resume, ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var pendingRow = resumeRow(resume, reserved, "in_resume");
        sqlState("23503", () -> insert("stripe_subscription_resume_proofs", RESUME_COLUMNS, pendingRow));
        var acknowledged = acknowledge(reserved, "sub_owned");
        var valid = resumeRow(resume, acknowledged, "in_resume");
        var other = newOriginal();
        rejectOwnerSubstitutions("stripe_subscription_resume_proofs", RESUME_COLUMNS, valid, other.subscription());
        var wrongReference = valid.clone(); wrongReference[6] = "sub_other";
        sqlState("23503", () -> insert("stripe_subscription_resume_proofs", RESUME_COLUMNS, wrongReference));
        var wrongKind = valid.clone(); wrongKind[0] = createOperation.id();
        sqlState("23503", () -> insert("stripe_subscription_resume_proofs", RESUME_COLUMNS, wrongKind));
        for (Object invalidStart : Arrays.asList(null, 0L, END + 86400)) {
            var bad = valid.clone(); bad[8] = invalidStart;
            sqlState(invalidStart == null ? "23502" : "23514", () -> insert("stripe_subscription_resume_proofs", RESUME_COLUMNS, bad));
        }
        var saved = proofs.recordResume(sub(original.id()), resume, acknowledged.id(), "in_resume", resumePeriod());
        evidenceConflict(() -> proofs.recordResume(sub(original.id()), resume, acknowledged.id(), "in_other", resumePeriod()));
        sqlState("23514", () -> jdbc.update("update stripe_subscription_resume_proofs set period_end=period_end+1 where operation_id=?", resume.id()));
        sqlState("23514", () -> jdbc.update("update stripe_subscription_resume_proofs set invoice_reference='in_other' where operation_id=?", resume.id()));
        assertThat(proofs.resume(account.id(), resume.id())).contains(saved);
        finish(resume);
        var next = operation(SubscriptionOperation.Kind.RESUME);
        var nextStep = acknowledge(reserve(next, ProviderOperation.Kind.SUBSCRIPTION_UPDATE), "sub_owned");
        sqlState("23505", () -> insert("stripe_subscription_resume_proofs", RESUME_COLUMNS, resumeRow(next, nextStep, "in_resume")));
    }

    @Test void failedResumeResultProofRollsBackItsAcknowledgementAndRetriesAtomically() {
        existingSubscription();var resume=operation(SubscriptionOperation.Kind.RESUME);var reserved=reserve(resume,ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var claimed=steps.claim(reserved.id(),"resume-owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();var s=sub(original.id());
        jdbc.execute("create function task7_reject_resume_proof() returns trigger language plpgsql as $$ begin raise exception 'injected resume result failure' using errcode='23514'; end; $$");
        jdbc.execute("create trigger task7_reject_resume_proof before insert on stripe_subscription_resume_proofs for each row execute function task7_reject_resume_proof()");
        try {
            sqlState("23514",()->resumeReceipts.record(s,resume,claimed,"resume-owner","in_atomicResume",resumePeriod()));
            assertThat(steps.findById(claimed.id())).contains(claimed);assertThat(proofs.resume(account.id(),resume.id())).isEmpty();
        } finally {
            jdbc.execute("drop trigger task7_reject_resume_proof on stripe_subscription_resume_proofs");jdbc.execute("drop function task7_reject_resume_proof()");
        }
        var acknowledged=resumeReceipts.record(s,resume,claimed,"resume-owner","in_atomicResume",resumePeriod());
        assertThat(acknowledged.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(proofs.resume(account.id(),resume.id())).hasValueSatisfying(p->{assertThat(p.step()).isEqualTo(acknowledged.id());assertThat(p.invoice()).isEqualTo("in_atomicResume");});
        assertThat(sub(original.id()).paidThrough()).isNull();assertThat(count("payments")).isZero();
    }

    @Test void resumeReceiptWaitsForStepBeforeAccountWhileAnOrdinaryAckHoldsTheStep() throws Exception {
        existingSubscription();var resume=operation(SubscriptionOperation.Kind.RESUME);var reserved=reserve(resume,ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var claimed=steps.claim(reserved.id(),"resume-owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();var s=sub(original.id());
        try(var pool=Executors.newFixedThreadPool(2);var holder=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            holder.setAutoCommit(false);int holderPid;
            try(var query=holder.createStatement();var row=query.executeQuery("select pg_backend_pid()")){row.next();holderPid=row.getInt(1);}
            try(var lock=holder.prepareStatement("select id from payment_accounts where id=? for update")){lock.setObject(1,account.id().value());try(var row=lock.executeQuery()){assertThat(row.next()).isTrue();}}
            var ordinary=pool.submit(()->steps.acknowledge(claimed.id(),"resume-owner",claimed.version(),"sub_owned"));
            int ordinaryPid=awaitBlockedQuery(holderPid,"payment_accounts");
            var receipt=pool.submit(()->{
                try{resumeReceipts.record(s,resume,claimed,"resume-owner","in_contendedResume",resumePeriod());return "unexpected receipt";}
                catch(PaymentApplicationException expected){return expected.code();}
            });
            try {
                // The exact receipt waits on the ordinary ACK's Step, rather than taking Account first.
                awaitBlockedQuery(ordinaryPid,"provider_operations");
            } finally {holder.rollback();}
            assertThat(ordinary.get(10,TimeUnit.SECONDS).status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(receipt.get(10,TimeUnit.SECONDS)).isEqualTo("PROVIDER_OPERATION_CONFLICT");
            assertThat(proofs.resume(account.id(),resume.id())).isEmpty();assertThat(sub(original.id()).paidThrough()).isNull();
        }
    }

    private int awaitBlockedQuery(int blocker,String table) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        do {
            var waiting=jdbc.queryForList("select pid from pg_stat_activity where ?=any(pg_blocking_pids(pid)) and query like ?",Integer.class,blocker,"%"+table+"%");
            if(!waiting.isEmpty())return waiting.getFirst();
            Thread.sleep(10);
        }while(System.nanoTime()<until);
        throw new AssertionError("Expected concurrent database row-lock wait on "+table);
    }

    @Test void nonUpdateCreateSetupPaymentAndRefundReferencesRemainUnique() {
        var other = newOriginal();
        assertReferenceCollision(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), reserve(other.operation(), ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_unique");
        assertReferenceCollision(reserve(createOperation, ProviderOperation.Kind.SETUP_INTENT_CREATE), reserve(other.operation(), ProviderOperation.Kind.SETUP_INTENT_CREATE), "seti_unique");
        var one = payment(); var two = payment();
        assertReferenceCollision(steps.reserve(account, ProviderOperation.Kind.PAYMENT_PREPARE, one.id().value(), "payment-one", "payment-facts-one"),
            steps.reserve(account, ProviderOperation.Kind.PAYMENT_PREPARE, two.id().value(), "payment-two", "payment-facts-two"), "pi_unique");
        UUID refundOne = refund(one), refundTwo = refund(two);
        assertReferenceCollision(steps.reserve(account, ProviderOperation.Kind.REFUND_CREATE, refundOne, "refund-one", "refund-facts-one"),
            steps.reserve(account, ProviderOperation.Kind.REFUND_CREATE, refundTwo, "refund-two", "refund-facts-two"), "re_unique");
    }

    @Test void preSubscriptionSetupGrantPersistsOnlyItsExactSetiStepAndStrictPurposeGroup(){
        bindCustomer();var seti=acknowledge(reserve(createOperation,ProviderOperation.Kind.SETUP_INTENT_CREATE),"seti_grant");
        var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(seti.id(),createOperation.providerOperationKey(),"a".repeat(64),null,"cus_grant",null,null,null,"setup-v1","seti_grant");
        var grant=grant(createOperation,binding);tx.required(()->{actions.reserveBilling(grant);return null;});
        var stored=tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow());assertThat(stored.binding()).isEqualTo(binding);assertThat(stored.binding().flow()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession.Flow.SETUP);
        var row=jdbc.queryForMap("select * from subscription_action_capabilities where id=?",grant.id());
        for(String missing:List.of("billing_schema","billing_step_kind","billing_step_reference","billing_setup_reference","consent_purpose")){
            var bad=grantCopy(row);bad.put(missing,null);sqlState("23514",()->insertRow("subscription_action_capabilities",bad));
        }
        for(var change:List.of(Map.of("billing_setup_reference","sub_owned"),Map.of("billing_step_reference","seti_foreign"),Map.of("billing_intent_reference","pi_unowned"),Map.of("consent_purpose","RESUME"))){
            var bad=new LinkedHashMap<String,Object>(row);bad.putAll(change);bad.put("id",UUID.randomUUID());bad.put("token_digest",UUID.randomUUID().toString().replace("-","")+"0".repeat(32));bad.put("superseded_at",row.get("expires_at"));
            sqlState("23514",()->insertRow("subscription_action_capabilities",bad));
        }
        var other=newOriginal();var foreign=acknowledge(reserve(other.operation(),ProviderOperation.Kind.SETUP_INTENT_CREATE),"seti_foreign");
        var bad=new LinkedHashMap<String,Object>(row);bad.put("id",UUID.randomUUID());bad.put("token_digest","e".repeat(64));bad.put("billing_step_id",foreign.id());bad.put("superseded_at",row.get("expires_at"));
        sqlState("23503",()->insertRow("subscription_action_capabilities",bad));
        assertThat(sub(original.id()).providerReference()).isNull();assertThat(sub(original.id()).paidThrough()).isNull();
    }

    @Test void explicitSetupGrantRequiresItsRealSubscriptionAndNeverAcceptsTheCreateNullGroup(){
        bindCustomer();existingSubscription();var setup=operation(SubscriptionOperation.Kind.SETUP);var seti=acknowledge(reserve(setup,ProviderOperation.Kind.SETUP_INTENT_CREATE),"seti_explicitGrant");
        var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(seti.id(),setup.providerOperationKey(),"a".repeat(64),"sub_owned","cus_grant",null,null,null,"setup-v1","seti_explicitGrant");
        var grant=grant(setup,binding);tx.required(()->{actions.reserveBilling(grant);return null;});
        var stored=tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow());assertThat(stored.binding()).isEqualTo(binding);
        var row=jdbc.queryForMap("select * from subscription_action_capabilities where id=?",grant.id());
        var missing=grantCopy(row);missing.put("billing_subscription_reference",null);sqlState("23514",()->insertRow("subscription_action_capabilities",missing));
        for(var cross:List.of(Map.of("billing_schema","payment-v1"),Map.of("billing_step_kind","SUBSCRIPTION_UPDATE"),Map.of("consent_purpose","CREATE"))){var bad=grantCopy(row);bad.putAll(cross);sqlState("23514",()->insertRow("subscription_action_capabilities",bad));}
        assertThat(sub(original.id()).providerReference()).isEqualTo("sub_owned");assertThat(sub(original.id()).paidThrough()).isNull();
    }

    @Test void resumePaymentGrantUsesExactUpdateReceiptAndRetainsConsumedDescriptor(){
        bindCustomer();existingSubscription();var resume=operation(SubscriptionOperation.Kind.RESUME);
        var step=acknowledge(reserve(resume,ProviderOperation.Kind.SUBSCRIPTION_UPDATE),"sub_owned");
        var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(step.id(),resume.providerOperationKey(),"a".repeat(64),"sub_owned","cus_grant","in_resumeGrant","inpay_resumeGrant","pi_resumeGrant","resume-v1",null);
        var grant=grant(resume,binding);tx.required(()->{actions.reserveBilling(grant);return null;});
        assertThat(tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow()).binding()).isEqualTo(binding);
        jdbc.update("update subscription_action_capabilities set consumed_at=issued_at where id=?",grant.id());
        assertThat(tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow()).descriptor()).isEqualTo(grant.descriptor());
        sqlState("23514",()->jdbc.update("update subscription_action_capabilities set billing_setup_reference='seti_other' where id=?",grant.id()));
        sqlState("23514",()->jdbc.update("update subscription_action_capabilities set consumed_at=null where id=?",grant.id()));
        assertThat(sub(original.id()).paidThrough()).isNull();
    }

    private void bindCustomer(){var p=steps.reserve(account,ProviderOperation.Kind.CUSTOMER_CREATE,customer.id().value(),"customer-grant-proof","customer-grant-fingerprint");var step=acknowledge(p,"cus_grant");bindings.bindCustomer(account.id(),site,customer.id(),"cus_grant",step.id());}
    private dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant grant(SubscriptionOperation o,dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding b){var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);return new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(UUID.randomUUID(),site,customer.id(),account.id(),original.id(),o.id(),"v1",b,"d".repeat(64),now,now.plusSeconds(600),null,null,null,null,null);}
    private void insertRow(String table,Map<String,Object> row){insert(table,String.join(",",row.keySet()),row.values().toArray());}
    private Map<String,Object> grantCopy(Map<String,Object> row){var copy=new LinkedHashMap<String,Object>(row);copy.put("id",UUID.randomUUID());copy.put("token_digest",UUID.randomUUID().toString().replace("-","")+"0".repeat(32));copy.put("superseded_at",row.get("expires_at"));return copy;}

    @Test void recoveredSetupAndUpdateReceiptsRevalidateTheOriginalPinnedAccount() {
        existingSubscription();
        for (var kind : List.of(ProviderOperation.Kind.SETUP_INTENT_CREATE, ProviderOperation.Kind.SUBSCRIPTION_UPDATE)) {
            var setup = kind == ProviderOperation.Kind.SUBSCRIPTION_UPDATE ? setupOperationWithMethod("RecoveryAccount") : operation(SubscriptionOperation.Kind.SETUP);
            var unknown = unknown(reserve(setup, kind));
            String reference = kind == ProviderOperation.Kind.SETUP_INTENT_CREATE ? "seti_recovered" : "sub_owned";
            jdbc.update("update payment_accounts set account_status='DISABLED' where id=?", account.id().value());
            try {
                providerConflict(() -> steps.bindRecovered(unknown, reference, unknown.fingerprint()));
                assertThat(steps.findById(unknown.id())).contains(unknown);
            } finally {
                jdbc.update("update payment_accounts set account_status='ACTIVE' where id=?", account.id().value());
            }
            var acknowledged = steps.bindRecovered(unknown, reference, unknown.fingerprint());
            assertThat(acknowledged.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(acknowledged.resourceId()).isEqualTo(setup.id());
            assertThat(acknowledged.operationKey()).isEqualTo(unknown.operationKey());
            assertThat(acknowledged.providerReference()).isEqualTo(reference);
            finish(setup);
        }
    }

    @Test void recoveredSetupReceiptRequiresNativeSetiReferenceAndExactOriginalSnapshot() {
        existingSubscription();
        var setup = operation(SubscriptionOperation.Kind.SETUP);
        var reserved = reserve(setup, ProviderOperation.Kind.SETUP_INTENT_CREATE);
        var claimed = steps.claim(reserved.id(), "recovery-owner", Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        providerConflict(() -> steps.bindRecovered(claimed, "seti_recovered", claimed.fingerprint()));
        var unknown = steps.unknown(claimed.id(), "recovery-owner", claimed.version());
        providerConflict(() -> steps.bindRecovered(claimed, "seti_recovered", claimed.fingerprint()));
        providerConflict(() -> steps.bindRecovered(unknown, "seti_recovered", "different-original-fingerprint"));
        providerConflict(() -> steps.bindRecovered(unknown, "seti_bad_id", unknown.fingerprint()));
        providerConflict(() -> steps.bindRecovered(unknown, "sub_owned", unknown.fingerprint()));
        assertThat(steps.findById(unknown.id())).contains(unknown);
        assertThat(steps.bindRecovered(unknown, "seti_recovered", unknown.fingerprint()).providerReference()).isEqualTo("seti_recovered");
    }

    @Test void recoveredUpdateReceiptCannotBindAnotherRealOwnedSubscription() {
        existingSubscription();
        var other = newOriginal();
        acknowledge(reserve(other.operation(), ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_foreign");
        commands.recordReply(other.operation().id(), new Reply(Outcome.ACCEPTED, "sub_foreign", null));
        var setup = setupOperationWithMethod("RecoveryReference");
        var unknown = unknown(reserve(setup, ProviderOperation.Kind.SUBSCRIPTION_UPDATE));
        providerConflict(() -> steps.bindRecovered(unknown, "sub_foreign", unknown.fingerprint()));
        providerConflict(() -> steps.bindRecovered(unknown, "sub_bad_id", unknown.fingerprint()));
        providerConflict(() -> steps.bindRecovered(unknown, "seti_foreign", unknown.fingerprint()));
        assertThat(steps.findById(unknown.id())).contains(unknown);
        var acknowledged = steps.bindRecovered(unknown, "sub_owned", unknown.fingerprint());
        assertThat(acknowledged.providerReference()).isEqualTo("sub_owned");
        assertThat(acknowledged.resourceId()).isEqualTo(setup.id());
        assertThat(acknowledged.firstDispatchedAt()).isEqualTo(unknown.firstDispatchedAt());
        assertThat(acknowledged.retryBefore()).isEqualTo(unknown.retryBefore());
    }

    @Test void failedGroupedProofCommitRetainsAckAndEligibilityForAnExactRetry() {
        var pending = proofs.reserveTrial(original, createOperation, END);
        var acknowledged = acknowledge(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_owned");
        jdbc.execute("create function task7_reject_trial_proof() returns trigger language plpgsql as $$ begin if new.trial_start is not null then raise exception 'injected proof failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task7_reject_trial_proof before update on stripe_subscription_trial_proofs for each row execute function task7_reject_trial_proof()");
        try {
            sqlState("23514", () -> proofs.bindTrial(pending, acknowledged.id(), "sub_owned", "si_owned", "in_owned", START));
            assertThat(proofs.trial(account.id(), original.id())).contains(pending);
            assertThat(steps.findById(acknowledged.id())).contains(acknowledged);
            assertThat(trials.find(site, customer.id(), original.productId()).orElseThrow().reservedByOperation()).isEqualTo(createOperation.id());
        } finally {
            jdbc.execute("drop trigger task7_reject_trial_proof on stripe_subscription_trial_proofs");
            jdbc.execute("drop function task7_reject_trial_proof()");
        }
        assertThat(proofs.bindTrial(pending, acknowledged.id(), "sub_owned", "si_owned", "in_owned", START).verified()).isTrue();
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test void originalSetupDispatchAuthorityIsRecheckedAgainstTheCurrentPolicy(){
        existingSubscription();var setup=operation(SubscriptionOperation.Kind.SETUP);var s=sub(original.id());
        assertThatCode(()->proofs.authorizeSetup(s,setup)).doesNotThrowAnyException();
        var policy=policies.load(site);policies.save(new dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy(site,policy.version(),false,policy.graceDays(),false,false,false,Set.of()),policy.version());
        evidenceConflict(()->proofs.authorizeSetup(s,setup));assertThat(count("stripe_subscription_setup_proofs")).isZero();
    }

    @Test void trialProofEligibilityBillProjectionAndJournalRollBackTogetherOnFactFailure(){
        var pending=proofs.reserveTrial(original,createOperation,END);var step=acknowledge(reserve(createOperation,ProviderOperation.Kind.SUBSCRIPTION_CREATE),"sub_atomicTrial");
        commands.recordReply(createOperation.id(),new Reply(Outcome.ACCEPTED,"sub_atomicTrial",null));var s=sub(original.id());var o=op(createOperation.id());var period=new Subscription.Period(Instant.ofEpochSecond(START),Instant.ofEpochSecond(END));
        var zero=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Invoice("in_atomicTrial",dev.genericpayments.platform.subscriptions.domain.BillingRecord.Kind.INITIAL,s.billingPrice(),null,period,0,"EUR",dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.ZERO,List.of());
        var evidence=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.TrialEvidence(o.id(),step.id(),"in_atomicTrial","si_atomicTrial",period,s.billingPrice(),null);
        var snapshot=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot(s.providerReference(),Subscription.Status.TRIALING,s.billingPrice(),period,period,null,List.of(zero),o.providerOperationKey(),false,0,INITIAL_TIME,new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OperationObservation(o.providerOperationKey(),dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OperationState.APPLIED),List.of(),evidence);
        long sequence=observations.captureFence(s);var command=new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command(account,s.id(),snapshot,dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent.Source.RECONCILIATION,"atomic-trial","task7",new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence(s.revision(),o.id(),o.version(),null,null,sequence));
        int events=count("subscription_events"),outbox=count("outbox_events");
        jdbc.execute("create function task7_reject_trial_journal() returns trigger language plpgsql as $$ begin if new.event_type='TRIAL_STARTED' then raise exception 'injected journal failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task7_reject_trial_journal before insert on subscription_events for each row execute function task7_reject_trial_journal()");
        try {
            sqlState("23514",()->tx.required(()->{facts.apply(command);return null;}));
            assertThat(proofs.trial(account.id(),s.id())).contains(pending);assertThat(trials.find(site,customer.id(),s.productId()).orElseThrow().consumedAt()).isNull();
            assertThat(sub(s.id())).isEqualTo(s);assertThat(observations.captureFence(s)).isEqualTo(sequence);assertThat(count("subscription_billing_records")).isZero();assertThat(count("subscription_events")).isEqualTo(events);assertThat(count("outbox_events")).isEqualTo(outbox);assertThat(steps.findById(step.id())).contains(step);
        } finally {jdbc.execute("drop trigger task7_reject_trial_journal on subscription_events");jdbc.execute("drop function task7_reject_trial_journal()");}
        tx.required(()->{facts.apply(command);return null;});assertThat(proofs.trial(account.id(),s.id()).orElseThrow().verified()).isTrue();assertThat(trials.find(site,customer.id(),s.productId()).orElseThrow().consumedAt()).isEqualTo(INITIAL_TIME);assertThat(sub(s.id()).trial()).isEqualTo(period);assertThat(count("subscription_billing_records")).isEqualTo(1);assertThat(count("payments")).isZero();
    }

    @Test void independentProofReservationSurvivesCallerRollback() {
        assertThatThrownBy(() -> tx.required(() -> {
            proofs.reserveTrial(original, createOperation, END);
            throw new IllegalStateException("injected caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("injected caller rollback");
        assertThat(proofs.trial(account.id(), original.id()).orElseThrow().requestedEnd()).isEqualTo(END);
        assertThat(trials.find(site, customer.id(), original.productId()).orElseThrow().consumedAt()).isNull();
    }

    @Test void anonymizationRetainsTrialSetupResumeAndOriginalAcknowledgedReceipts() {
        var pending = proofs.reserveTrial(original, createOperation, END);
        var createStep = acknowledge(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_owned");
        var trial = proofs.bindTrial(pending, createStep.id(), "sub_owned", "si_owned", "in_trial", START);
        commands.recordReply(createOperation.id(), new Reply(Outcome.ACCEPTED, "sub_owned", null));
        trials.consume(createOperation.id(), INITIAL_TIME);
        finish(createOperation);
        methodUpdate("Privacy");
        var setup = jdbc.queryForObject("select operation_id from stripe_subscription_setup_proofs", UUID.class);
        var setupProof = proofs.setup(account.id(), setup).orElseThrow();
        var resume = operation(SubscriptionOperation.Kind.RESUME);
        var resumeStep = acknowledge(reserve(resume, ProviderOperation.Kind.SUBSCRIPTION_UPDATE), "sub_owned");
        var resumeProof = proofs.recordResume(sub(original.id()), resume, resumeStep.id(), "in_resume", resumePeriod());
        finish(resume);
        var before = jdbc.queryForList("select * from provider_operations order by id");
        var stored = sub(original.id()); subscriptions.save(stored.cancel("CONFIRMED", INITIAL_TIME), stored.revision());
        var result = anonymizer.anonymize(new AnonymizeCustomerUseCase.Command(site, customer.id(), customer.id().value(), "erase-proof-owner", "ADMIN", "00000000-0000-0000-0000-000000000001", "task7-proof-privacy", "127.0.0.1"));
        assertThat(result.status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(proofs.trial(account.id(), original.id())).contains(trial);
        assertThat(proofs.setup(account.id(), setup)).contains(setupProof);
        assertThat(proofs.resume(account.id(), resume.id())).contains(resumeProof);
        assertThat(jdbc.queryForList("select * from provider_operations order by id")).isEqualTo(before);
        assertThat(trials.find(site, customer.id(), original.productId()).orElseThrow().consumedAt()).isEqualTo(INITIAL_TIME);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name in ('stripe_subscription_trial_proofs','stripe_subscription_setup_proofs','stripe_subscription_resume_proofs')", String.class))
            .doesNotContain("client_secret", "payload", "email", "provider_status", "payment_method_ready", "paid");
    }

    private record Original(Subscription subscription, SubscriptionOperation operation) {}
    private Original newOriginal() {
        price = price(3);
        var r = commands.reserveCreate(command("proof-create-" + UUID.randomUUID()));
        return new Original(r.subscription(), r.operation());
    }
    private ProviderOperation reserve(SubscriptionOperation o, ProviderOperation.Kind kind) {
        String suffix = kind == ProviderOperation.Kind.SETUP_INTENT_CREATE ? ":SETUP"
            : kind == ProviderOperation.Kind.SUBSCRIPTION_UPDATE && o.kind() == SubscriptionOperation.Kind.SETUP ? ":METHOD" : "";
        return steps.reserve(account, kind, o.id(), o.providerOperationKey() + suffix, "proof-" + o.id() + "-" + kind);
    }
    private ProviderOperation acknowledge(ProviderOperation reserved, String reference) {
        var claimed = steps.claim(reserved.id(), "proof-owner", Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        return steps.acknowledge(claimed.id(), "proof-owner", claimed.version(), reference);
    }
    private void existingSubscription() {
        acknowledge(reserve(createOperation, ProviderOperation.Kind.SUBSCRIPTION_CREATE), "sub_owned");
        commands.recordReply(createOperation.id(), new Reply(Outcome.ACCEPTED, "sub_owned", null));
        finish(createOperation);
    }
    private SubscriptionOperation operation(SubscriptionOperation.Kind kind) {
        var s = sub(original.id()); UUID id = UUID.randomUUID();
        var candidate = new SubscriptionOperation(id, site, customer.id(), s.id(), kind, SubscriptionOperation.Status.RESERVED,
            "proof-authority", "proof-" + id, kind==SubscriptionOperation.Kind.RESUME?"v1.resume.none."+"a".repeat(64):"proof-fingerprint-" + id, "subscription:" + account.id().value() + ":" + id + ":" + kind,
            s.revision(), policies.load(site).version(), null, null, INITIAL_TIME, INITIAL_TIME, 0);
        return tx.required(() -> operations.claim(candidate).operation());
    }
    private void finish(SubscriptionOperation operation) {
        tx.required(() -> {
            var o = operations.lock(operation.id());
            return operations.save(new SubscriptionOperation(o.id(), o.siteId(), o.customerId(), o.subscriptionId(), o.kind(), SubscriptionOperation.Status.SUCCEEDED,
                o.authorityScope(), o.keyDigest(), o.fingerprint(), o.providerOperationKey(), o.expectedRevision(), o.policyVersion(), o.providerReference(), null,
                o.createdAt(), o.updatedAt(), o.version() + 1), o.version());
        });
    }
    private ProviderOperation methodUpdate(String suffix) {
        var setup = setupOperationWithMethod(suffix);
        var update = acknowledge(reserve(setup, ProviderOperation.Kind.SUBSCRIPTION_UPDATE), "sub_owned");
        finish(setup); return update;
    }
    private SubscriptionOperation setupOperationWithMethod(String suffix) {
        var setup = operation(SubscriptionOperation.Kind.SETUP);
        var seti = acknowledge(reserve(setup, ProviderOperation.Kind.SETUP_INTENT_CREATE), "seti_" + suffix);
        var proof = proofs.recordSetup(sub(original.id()), setup, seti.id(), seti.providerReference());
        proofs.bindMethod(proof, "pm_" + suffix);
        return setup;
    }
    private ProviderOperation unknown(ProviderOperation reserved) {
        var claimed = steps.claim(reserved.id(), "recovery-owner", Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        return steps.unknown(claimed.id(), "recovery-owner", claimed.version());
    }
    private boolean bindAfterGate(CyclicBarrier gate, SubscriptionBillingProofStore.Setup pending, String method) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        try { proofs.bindMethod(pending, method); return true; }
        catch (PaymentApplicationException e) { assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"); return false; }
    }
    private Object[] trialRow(Subscription s, SubscriptionOperation o, ProviderOperation step, String item, String invoice) {
        return new Object[] {o.id(), s.siteId().value(), s.customerId().value(), s.id(), s.paymentAccountId().value(), END, step.id(), step.providerReference(), item, invoice, START};
    }
    private Object[] setupRow(Subscription s, SubscriptionOperation o, ProviderOperation step) {
        return new Object[] {o.id(), s.siteId().value(), s.customerId().value(), s.id(), s.paymentAccountId().value(), o.kind().name(), step.id(), step.providerReference(), null};
    }
    private Object[] resumeRow(SubscriptionOperation o, ProviderOperation step, String invoice) {
        return new Object[] {o.id(), site.value(), customer.id().value(), original.id(), account.id().value(), step.id(), "sub_owned", invoice, END, END + 86400};
    }
    private Subscription.Period resumePeriod() { return new Subscription.Period(Instant.ofEpochSecond(END), Instant.ofEpochSecond(END + 86400)); }
    private void insert(String table, String columns, Object... values) {
        jdbc.update("insert into " + table + "(" + columns + ") values (" + String.join(",", Collections.nCopies(values.length, "?")) + ")", values);
    }
    private void rejectOwnerSubstitutions(String table, String columns, Object[] valid, Subscription other) {
        // Every substituted identifier exists; the rejection must come from a composite proof FK.
        var otherCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        UUID otherAccount = UUID.randomUUID(), otherSite = UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,?,'STRIPE','SANDBOX','ACTIVE','acct_other','stripe-sandbox-default',now(),0)", otherAccount, "proof-account-" + otherAccount);
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,?,'Other','ACTIVE',?,'AUTOMATIC',now(),now(),0)", otherSite, "proof-site-" + otherSite, otherAccount);
        Object[] alternate = {otherSite, otherCustomer.id().value(), other.id(), otherAccount};
        for (int i = 0; i < alternate.length; i++) {
            var bad = valid.clone(); bad[i + 1] = alternate[i];
            sqlState("23503", () -> insert(table, columns, bad));
        }
    }
    private Payment payment() {
        return payments.save(Payment.dynamic(new PaymentId(UUID.randomUUID()), site, account.id(), "proof-order-" + UUID.randomUUID(), Money.of(2000, "EUR"), TaxMode.NONE, Map.of(), INITIAL_TIME));
    }
    private UUID refund(Payment payment) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?,?,100,'EUR','PENDING',?,now(),now())", id, payment.id().value(), "refund-" + id);
        return id;
    }
    private void assertReferenceCollision(ProviderOperation first, ProviderOperation second, String reference) {
        var acknowledged = acknowledge(first, reference);
        var claimed = steps.claim(second.id(), "collision-owner", Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        assertThatThrownBy(() -> steps.acknowledge(claimed.id(), "collision-owner", claimed.version(), reference))
            .isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(steps.findById(first.id())).contains(acknowledged);
        assertThat(steps.findById(second.id())).contains(claimed);
    }
    private static void evidenceConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));
    }
    private static void providerConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
    }
    private static void sqlState(String expected, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DataIntegrityViolationException.class,
            failure -> assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo(expected)));
    }
}
