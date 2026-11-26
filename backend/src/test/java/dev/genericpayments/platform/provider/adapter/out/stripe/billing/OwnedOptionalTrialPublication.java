package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialPaymentConfirmation.PaymentReceipt;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.application.port.in.ResumeSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.OwnedSubscriptionBillingTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.*;

/** Explicit test-only composition. Only the normal FactService writes lifecycle, billing and journal facts. */
public final class OwnedOptionalTrialPublication implements SubscriptionBillingTimePort, SubscriptionIsolatedTrialProofPort {
    record Receipt(Authority authority, Created original, UUID step, long revision, long operationVersion, long sequence) {}
    private record Verified(Receipt receipt, Command command) {}
    private record Lifecycle(Receipt original, UUID bill, UUID started, Long frozen, Subscription.Status status) {}
    private record PendingResume(UUID operation, UUID bill, String invoice, boolean open) {}
    private record VerifiedLifecycle(Lifecycle lifecycle, Command command, UUID setup, boolean ownedAdvance,
                                     PaymentReceipt payment) {}
    private final Object verificationKey = new Object();
    private final JdbcTemplate jdbc;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final SubscriptionTransactionPort transactions;
    private final ObjectProvider<ApplySubscriptionSnapshotUseCase> facts;
    private final Clock operationalClock;
    private final SubscriptionOperationStore operations;
    private final SubscriptionBillingProofStore proofs;

    private OwnedOptionalTrialPublication(JdbcTemplate jdbc, StripeCredentialResolver credentials, StripeClientFactory clients,
            PaymentAccountVerificationPort verification, SubscriptionTransactionPort transactions,
            ObjectProvider<ApplySubscriptionSnapshotUseCase> facts, Clock operationalClock,
            SubscriptionOperationStore operations, SubscriptionBillingProofStore proofs) {
        this.jdbc=jdbc; this.credentials=credentials; this.clients=clients; this.verification=verification;
        this.transactions=transactions; this.facts=facts; this.operationalClock=operationalClock;
        this.operations=operations; this.proofs=proofs;
    }

    @TestConfiguration(proxyBeanMethods=false)
    public static class Configuration {
        @Bean @Primary OwnedOptionalTrialPublication optionalTrialPublication(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                StripeClientFactory clients, PaymentAccountVerificationPort verification, SubscriptionTransactionPort transactions,
                ObjectProvider<ApplySubscriptionSnapshotUseCase> facts, Clock operationalClock,
                SubscriptionOperationStore operations, SubscriptionBillingProofStore proofs) {
            return new OwnedOptionalTrialPublication(jdbc, credentials, clients, verification, transactions, facts, operationalClock,
                operations, proofs);
        }
    }

    public void publish(PaymentAccount account, Scope scope) {
        require(!transactions.active());
        var receipt = pending(scope, false);
        var a = receipt.authority();
        requireAccount(account,a);
        verification.verify(account);
        try {
            OwnedOptionalTrialCreate.reread(clients.create(credentials.resolve(account)), a, receipt.original());
        } catch (StripeException | RuntimeException failure) {
            throw new IllegalStateException("Optional trial graph could not be verified");
        }

        var command = observedCommand(account, receipt, operationalClock.instant());
        transactions.requiresNew(() -> {
            // Bind only this independently observed command for the duration of its actual publication transaction.
            TransactionSynchronizationManager.bindResource(verificationKey, new Verified(receipt, command));
            try {
                facts.getObject().apply(command);
            } finally {
                TransactionSynchronizationManager.unbindResource(verificationKey);
            }
            return null;
        });
    }

    public void pause(PaymentAccount account, Scope scope) {
        publishPause(account,scope,false);
    }

    void advanceAndPause(PaymentAccount account, Scope scope, OwnedOptionalTrialClockAdvance advance) {
        require(!transactions.active());
        var lifecycle=lifecycle(scope);
        requireAccount(account,lifecycle.original().authority());
        require(lifecycle.status()==Subscription.Status.TRIALING&&lifecycle.frozen()==null);
        advance.advance(account,scope);
        publishAdvancedPause(account,scope);
    }

    void publishAdvancedPause(PaymentAccount account, Scope scope) {
        publishPause(account,scope,true);
    }

    private void publishPause(PaymentAccount account, Scope scope, boolean ownedAdvance) {
        require(!transactions.active());
        var lifecycle=lifecycle(scope);
        var receipt=lifecycle.original();
        var a=receipt.authority();
        requireAccount(account,a);
        require(lifecycle.status()==Subscription.Status.TRIALING&&lifecycle.frozen()==null);
        if(ownedAdvance||Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from stripe_billing_optional_trial_clock_advances where clock_intent=?)
            """,Boolean.class,a.clockIntent()))) {
            require(OwnedOptionalTrialClockAdvance.ready(jdbc,scope,a.trialEnd()));
            ownedAdvance=true;
        }
        verification.verify(account);
        try {
            OwnedOptionalTrialCreate.rereadPaused(clients.create(credentials.resolve(account)),a,receipt.original(),a.trialEnd());
        } catch (StripeException | RuntimeException failure) {
            throw new IllegalStateException("Optional trial pause could not be verified");
        }
        applyLifecycle(new VerifiedLifecycle(lifecycle,command(account,lifecycle,paused(lifecycle,null),null),
            null,ownedAdvance,null));
    }

    void requirePaused(PaymentAccount account, Scope scope) {
        var lifecycle=lifecycle(scope);
        requireAccount(account,lifecycle.original().authority());
        require(lifecycle.status()==Subscription.Status.PAUSED&&lifecycle.frozen()!=null);
    }

    void publishSetup(PaymentAccount account, Scope scope, UUID setupId) {
        require(!transactions.active());
        var lifecycle=lifecycle(scope);
        requireAccount(account,lifecycle.original().authority());
        require(lifecycle.status()==Subscription.Status.PAUSED&&lifecycle.frozen()!=null);
        var setup=operation(lifecycle,setupId,SubscriptionOperation.Kind.SETUP);
        requireSetupProof(account,lifecycle,setup.id());
        requireLifecycleOperations(lifecycle,setup.id(),null,true);
        applyLifecycle(new VerifiedLifecycle(lifecycle,command(account,lifecycle,paused(lifecycle,setup),setup),
            setup.id(),false,null));
    }

    SubscriptionOperationResult startResume(PaymentAccount account, Scope scope, UUID setupId,
                                            ResumeSubscriptionUseCase.Command command, OwnedOptionalTrialResume resumer) {
        require(!transactions.active());
        var lifecycle=lifecycle(scope);
        var receipt=lifecycle.original();
        requireAccount(account,receipt.authority());
        require(lifecycle.status()==Subscription.Status.PAUSED&&lifecycle.frozen()!=null);
        requireSetupProof(account,lifecycle,setupId);
        requireLifecycleOperations(lifecycle,setupId,null,false);
        return resumer.resume(account,receipt.authority(),receipt.original(),lifecycle.frozen(),setupId,command,()->{
            require(lifecycle(scope).equals(lifecycle));
            requireSetupProof(account,lifecycle,setupId);
        });
    }

    void confirmResumePayment(PaymentAccount account, Scope scope, UUID setupId, UUID resumeId,
                              OwnedOptionalTrialPaymentConfirmation confirmation) {
        require(!transactions.active());
        var lifecycle=lifecycle(scope);
        var receipt=lifecycle.original();
        requireAccount(account,receipt.authority());
        require(lifecycle.status()==Subscription.Status.PAUSED&&lifecycle.frozen()!=null);
        requireSetupProof(account,lifecycle,setupId);
        var resume=operation(lifecycle,resumeId,SubscriptionOperation.Kind.RESUME);
        requireLifecycleOperations(lifecycle,setupId,resumeId,false);
        var pending=pendingResume(lifecycle,resumeId,true);
        confirmation.confirm(account,receipt.authority(),receipt.original(),lifecycle.frozen(),setupId,resumeId,()->{
            require(lifecycle(scope).equals(lifecycle)&&operation(lifecycle,resumeId,SubscriptionOperation.Kind.RESUME).equals(resume));
            requireSetupProof(account,lifecycle,setupId);
            requireLifecycleOperations(lifecycle,setupId,resumeId,false);
            require(pendingResume(lifecycle,resumeId,true).equals(pending));
        });
    }

    void observeResume(PaymentAccount account, Scope scope, UUID setupId, UUID resumeId,
                       OwnedOptionalTrialResume resumer, boolean paid) {
        require(!transactions.active());
        var lifecycle=lifecycle(scope);
        var receipt=lifecycle.original();
        var a=receipt.authority();
        requireAccount(account,a);
        require(lifecycle.status()==Subscription.Status.PAUSED&&lifecycle.frozen()!=null);
        requireSetupProof(account,lifecycle,setupId);
        var resume=operation(lifecycle,resumeId,SubscriptionOperation.Kind.RESUME);
        requireLifecycleOperations(lifecycle,setupId,resumeId,false);
        var pending=pendingResume(lifecycle,resume.id(),paid);
        var payment=paid?OwnedOptionalTrialPaymentConfirmation.acknowledged(jdbc,a,setupId,resumeId):null;
        if(paid)require(payment.invoice().equals(pending.invoice()));
        Runnable requireCurrent=()->{
            require(lifecycle(scope).equals(lifecycle)&&operation(lifecycle,resumeId,SubscriptionOperation.Kind.RESUME).equals(resume));
            requireLifecycleOperations(lifecycle,setupId,resumeId,false);
            require(java.util.Objects.equals(pendingResume(lifecycle,resumeId,paid),pending));
        };
        var trial=trialEvidence(receipt);
        var snapshot=paid
            ?resumer.observe(account,a,receipt.original(),lifecycle.frozen(),setupId,resumeId,trial,payment,requireCurrent)
            :resumer.observe(account,a,receipt.original(),lifecycle.frozen(),setupId,resumeId,trial,false,requireCurrent);
        require(snapshot.status()==(paid?Subscription.Status.ACTIVE:Subscription.Status.PAUSED));
        applyLifecycle(new VerifiedLifecycle(lifecycle,command(account,lifecycle,snapshot,resume),setupId,false,payment));
    }

    private void requireSetupProof(PaymentAccount account, Lifecycle lifecycle, UUID setupId) {
        var proof=proofs.setup(account.id(),setupId).orElseThrow(OwnedOptionalTrialPublication::unavailable);
        var a=lifecycle.original().authority();
        require(proof.operation().equals(setupId)&&proof.subscription().equals(a.subscription())&&proof.method()!=null);
        require(jdbc.queryForObject("""
            select count(*) from stripe_billing_optional_trial_setup_steps owned
            join provider_operations step on step.id=owned.step_id and step.resource_id=owned.setup_operation
              and step.kind=owned.step_kind and step.payment_account_id=? and step.external_account_id=? and step.credentials_ref=?
              and step.status='ACKNOWLEDGED' and step.recovery_attempts=0 and not step.manual_review_required
            where owned.clock_intent=? and owned.setup_operation=?
              and ((owned.step_kind='SETUP_INTENT_CREATE' and step.id=? and step.provider_reference=?)
                or (owned.step_kind='SUBSCRIPTION_UPDATE' and step.provider_reference=? and owned.method_reference=?))
            """,Integer.class,a.account(),a.externalAccount(),a.credentialsRef(),a.clockIntent(),setupId,proof.step(),
            proof.reference(),lifecycle.original().original().subscription(),proof.method())==2);
    }

    private static void requireAccount(PaymentAccount account, Authority a) {
        require(a.account().equals(account.id().value()) && account.status()==PaymentAccountStatus.ACTIVE
            && account.provider()==PaymentProviderType.STRIPE && account.mode()==PaymentAccountMode.SANDBOX
            && a.externalAccount().equals(account.externalAccountId()) && a.credentialsRef().equals(account.credentialsRef())
            && "stripe-sandbox-default".equals(account.key()));
    }

    private SubscriptionOperation operation(Lifecycle lifecycle, UUID id, SubscriptionOperation.Kind kind) {
        var a=lifecycle.original().authority();
        var operation=operations.find(new SiteId(a.site()),a.subscription(),id).orElseThrow(OwnedOptionalTrialPublication::unavailable);
        require(operation.kind()==kind&&operation.status()==SubscriptionOperation.Status.PROCESSING
            &&operation.expectedRevision()==lifecycle.original().revision()
            &&operation.customerId().equals(new CustomerId(a.customer()))
            &&operation.providerOperationKey().equals("subscription:"+a.account()+":"+id+":"+kind));
        return operation;
    }

    private void applyLifecycle(VerifiedLifecycle verified) {
        var lifecycle=verified.lifecycle();
        var command=verified.command();
        transactions.requiresNew(()->{
            require(lifecycle(lifecycle.original().authority().scope()).equals(lifecycle));
            TransactionSynchronizationManager.bindResource(verificationKey,verified);
            try {
                facts.getObject().apply(command);
                if(isPendingResume(command))rememberPendingResume(lifecycle,command);
            } finally {
                TransactionSynchronizationManager.unbindResource(verificationKey);
            }
            return null;
        });
    }

    private record LocalOperation(UUID id, String kind, String status) {}

    private void requireLifecycleOperations(Lifecycle lifecycle, UUID setup, UUID resume, boolean setupProcessing) {
        var a=lifecycle.original().authority();
        var expected=new java.util.HashSet<LocalOperation>();
        expected.add(new LocalOperation(a.createOperation(),"CREATE","SUCCEEDED"));
        if(setup!=null)expected.add(new LocalOperation(setup,"SETUP",setupProcessing?"PROCESSING":"SUCCEEDED"));
        if(resume!=null)expected.add(new LocalOperation(resume,"RESUME","PROCESSING"));
        var rows=jdbc.query("""
            select id,kind,status from subscription_operations where subscription_id=? and site_id=? and customer_id=?
            """,(r,n)->new LocalOperation(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status")),
            a.subscription(),a.site(),a.customer());
        require(rows.size()==expected.size()&&expected.containsAll(rows));
    }

    private static boolean isPendingResume(Command command) {
        return command.snapshot().status()==Subscription.Status.PAUSED
            &&command.snapshot().operationObservation()!=null
            &&command.snapshot().operationObservation().state()==OperationState.PENDING;
    }

    private void rememberPendingResume(Lifecycle lifecycle, Command command) {
        var a=lifecycle.original().authority();
        var operation=command.fence().operationId();
        var invoice=command.snapshot().invoices().getLast();
        require(jdbc.update("""
            insert into stripe_billing_optional_trial_pending_resumptions (clock_intent,resume_operation,billing_record_id)
            select ?,?,b.id from subscription_billing_records b where b.site_id=? and b.subscription_id=?
              and b.payment_account_id=? and b.provider_invoice_reference=? and b.settlement='OPEN'
            """,a.clockIntent(),operation,a.site(),a.subscription(),a.account(),invoice.reference())==1);
    }

    private PendingResume pendingResume(Lifecycle lifecycle, UUID operation, boolean paid) {
        var a=lifecycle.original().authority();
        var rows=jdbc.query("""
            select p.resume_operation,b.id,b.provider_invoice_reference,
              (b.subscription_id=? and b.payment_account_id=? and b.site_id=? and b.settlement='OPEN'
                and b.kind='RENEWAL' and b.billed_price_id=? and b.amount_due_minor=2000 and b.currency='EUR'
                and b.provider_invoice_reference=r.invoice_reference
                and b.period_start=to_timestamp(r.period_start) and b.period_end=to_timestamp(r.period_end)
                and not exists(select 1 from payments pay where pay.site_id=b.site_id)) as is_open
            from stripe_billing_optional_trial_pending_resumptions p
            join subscription_billing_records b on b.id=p.billing_record_id
            join stripe_subscription_resume_proofs r on r.operation_id=p.resume_operation
            where p.clock_intent=?
            """,(r,n)->new PendingResume(r.getObject("resume_operation",UUID.class),r.getObject("id",UUID.class),
                r.getString("provider_invoice_reference"),r.getBoolean("is_open")),
            a.subscription(),a.account(),a.site(),a.price(),a.clockIntent());
        if(!paid) {
            require(rows.isEmpty());
            return null;
        }
        require(rows.size()==1&&rows.getFirst().open()&&rows.getFirst().operation().equals(operation));
        return rows.getFirst();
    }

    private Snapshot paused(Lifecycle lifecycle, SubscriptionOperation setup) {
        var receipt=lifecycle.original();
        var trial=trialEvidence(receipt);
        var original=new Invoice(trial.invoice(),BillingRecord.Kind.INITIAL,trial.price(),null,trial.period(),0,
            "EUR",SettlementKind.ZERO,List.of());
        var marker=setup==null?null:new OperationObservation(setup.providerOperationKey(),OperationState.APPLIED);
        return new Snapshot(receipt.original().subscription(),Subscription.Status.PAUSED,trial.price(),trial.period(),trial.period(),
            null,List.of(original),setup==null?null:setup.providerOperationKey(),setup!=null,0,operationalClock.instant(),marker,List.of(),trial);
    }

    private static TrialEvidence trialEvidence(Receipt receipt) {
        var a=receipt.authority();
        var trialPeriod=new Subscription.Period(Instant.ofEpochSecond(receipt.original().trialStart()),Instant.ofEpochSecond(a.trialEnd()));
        return new TrialEvidence(a.createOperation(),receipt.step(),receipt.original().invoice(),receipt.original().item(),
            trialPeriod,a.published().terms(),null);
    }

    private static Command command(PaymentAccount account, Lifecycle lifecycle, Snapshot snapshot, SubscriptionOperation operation) {
        var receipt=lifecycle.original();
        var a=receipt.authority();
        var fence=new ApplySubscriptionSnapshotUseCase.Fence(receipt.revision(),operation==null?null:operation.id(),
            operation==null?null:operation.version(),null,null,receipt.sequence());
        var key=operation==null?"pause-"+a.createOperation():"trial-"+operation.id()+"-"+snapshot.status();
        return new Command(account,a.subscription(),snapshot,SubscriptionEvent.Source.RECONCILIATION,
            "owned-optional-trial-"+key,"owned-optional-trial",fence,new Sample(Instant.ofEpochSecond(a.trialEnd()),Mode.ISOLATED));
    }

    private Lifecycle lifecycle(Scope scope) {
        var rows=jdbc.query("""
            with admitted as materialized (
                select * from stripe_billing_optional_trial_acknowledgements
                where run_id=? and clock_intent=? and subscription_id=? and create_operation=?
            )
            select a.*,s.revision,s.observation_sequence,s.billing_status,o.version,b.id as bill_id,
                   e.id as started_id,p.frozen_time as paused_time
            from admitted a
            join subscriptions s on s.id=a.subscription_id and s.provider_reference=a.subscription_reference
              and s.trial_start=to_timestamp(a.trial_start) and s.trial_end=to_timestamp(a.trial_end)
              and s.billing_price_id=a.price_id and s.entitled_price_id=a.price_id
              and s.paid_through is null and s.debt_billing_record_id is null
              and s.cancellation_effective_at is null and s.pending_operation_id is null
            join subscription_operations o on o.id=a.create_operation and o.status='SUCCEEDED'
              and o.provider_reference=a.subscription_reference and o.safe_failure_code is null
            join subscription_trial_eligibility t on t.site_id=a.site_id and t.customer_id=a.customer_id
              and t.product_id=a.product_id and t.reserved_by_operation=o.id and t.consumed_at is not null
            join subscription_billing_records b on b.subscription_id=a.subscription_id and b.site_id=a.site_id
              and b.payment_account_id=a.account_id and b.billed_price_id=a.price_id
              and b.provider_invoice_reference=a.invoice_reference and b.currency='EUR'
              and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0
              and b.period_start=s.trial_start and b.period_end=s.trial_end
            join subscription_events e on e.subscription_id=s.id and e.site_id=a.site_id and e.payment_account_id=a.account_id
              and e.event_type='TRIAL_STARTED' and e.payment_id is null
              and (e.billing_record_id is null or e.billing_record_id=b.id)
            left join stripe_billing_optional_trial_pauses p on p.clock_intent=a.clock_intent and p.subscription_id=s.id
              and p.create_operation=o.id and p.original_bill_id=b.id and p.trial_started_event_id=e.id
            where ((s.billing_status='TRIALING' and p.clock_intent is null)
                or (s.billing_status='PAUSED' and p.frozen_time=a.trial_end))
              and not exists (select 1 from payments cash where cash.site_id=a.site_id)
              and not exists (
                select 1 from subscription_billing_records extra where extra.site_id=a.site_id and extra.id<>b.id
                  and not exists (select 1 from stripe_billing_optional_trial_pending_resumptions allowed
                      where allowed.clock_intent=a.clock_intent and allowed.billing_record_id=extra.id)
              )
            """,(r,n)->new Lifecycle(new Receipt(JdbcOwnedOptionalTrialCreateStore.authority(r),
                new Created(r.getString("subscription_reference"),r.getString("item_reference"),r.getString("invoice_reference"),
                    r.getLong("trial_start")),r.getObject("step_id",UUID.class),r.getLong("revision"),r.getLong("version"),
                r.getLong("observation_sequence")),r.getObject("bill_id",UUID.class),r.getObject("started_id",UUID.class),
                r.getObject("paused_time",Long.class),Subscription.Status.valueOf(r.getString("billing_status"))),
            scope.run(),scope.clockIntent(),scope.subscription(),scope.createOperation());
        require(rows.size()==1);
        return rows.getFirst();
    }

    static Command observedCommand(PaymentAccount account, Receipt receipt, Instant observedAt) {
        var a = receipt.authority();
        var original = receipt.original();
        var terms = a.published().terms();
        var period = new Subscription.Period(Instant.ofEpochSecond(original.trialStart()), Instant.ofEpochSecond(a.trialEnd()));
        var invoice = new Invoice(original.invoice(), BillingRecord.Kind.INITIAL, terms, null, period, 0, "EUR", SettlementKind.ZERO, List.of());
        var snapshot = new Snapshot(original.subscription(), Subscription.Status.TRIALING, terms, period, period, null,
            List.of(invoice), a.operationKey(), false, 0, observedAt,
            new OperationObservation(a.operationKey(), OperationState.APPLIED), List.of(),
            new TrialEvidence(a.createOperation(), receipt.step(), original.invoice(), original.item(), period, terms, null));
        var fence = new ApplySubscriptionSnapshotUseCase.Fence(receipt.revision(), a.createOperation(), receipt.operationVersion(),
            null, null, receipt.sequence());
        return new Command(account, a.subscription(), snapshot, SubscriptionEvent.Source.RECONCILIATION,
            "owned-optional-trial-" + a.createOperation(), "owned-optional-trial", fence, new Sample(period.start(), Mode.ISOLATED));
    }

    private Receipt pending(Scope scope, boolean lock) {
        var sql = """
            select a.*,s.revision,s.observation_sequence,o.version from stripe_billing_optional_trial_acknowledgements a
            join subscriptions s on s.id=a.subscription_id join subscription_operations o on o.id=a.create_operation
            join subscription_trial_eligibility e on e.reserved_by_operation=o.id
            where a.run_id=? and a.clock_intent=? and a.subscription_id=? and a.create_operation=?
              and s.billing_status='INCOMPLETE' and s.revision=o.expected_revision and s.provider_reference is null
              and s.entitled_price_id is null and s.current_period_start is null and s.trial_start is null and s.paid_through is null
              and s.debt_billing_record_id is null and s.cancellation_effective_at is null and s.pending_operation_id is null
              and o.status='RESERVED' and o.provider_reference is null and e.consumed_at is null
              and not exists(select 1 from subscription_operations z where z.subscription_id=s.id and z.id<>o.id)
              and not exists(select 1 from subscription_events z where z.subscription_id=s.id)
              and not exists(select 1 from subscription_billing_records z where z.subscription_id=s.id)
              and not exists(select 1 from payments z where z.site_id=s.site_id)
              and not exists(select 1 from stripe_subscription_setup_proofs z where z.subscription_id=s.id)
            """;
        var rows = jdbc.query(sql + (lock ? " for share" : ""), (r,n) -> new Receipt(JdbcOwnedOptionalTrialCreateStore.authority(r),
            new Created(r.getString("subscription_reference"), r.getString("item_reference"), r.getString("invoice_reference"),
                r.getLong("trial_start")), r.getObject("step_id",UUID.class), r.getLong("revision"), r.getLong("version"),
            r.getLong("observation_sequence")), scope.run(),scope.clockIntent(),scope.subscription(),scope.createOperation());
        require(rows.size()==1);
        return rows.getFirst();
    }

    @Override public void requireAuthorized(Command command, Subscription s, List<SubscriptionOperation> operations,
                                            Instant billingAt, Instant utcAt) {
        require(transactions.active());
        var verified = TransactionSynchronizationManager.getResource(verificationKey);
        if(verified instanceof VerifiedLifecycle read) {
            var lifecycle=read.lifecycle();
            var a=lifecycle.original().authority();
            require(jdbc.queryForList("""
                select id from stripe_billing_clock_intents where id=? and run_id=? and payment_account_id=?
                  and site_id=? and customer_id=? and stopped_at is null for share
                """,UUID.class,a.clockIntent(),a.run(),a.account(),a.site(),a.customer()).size()==1);
            require(read.command()==command&&s.id().equals(a.subscription())
                &&billingAt.equals(Instant.ofEpochSecond(a.trialEnd()))&&lifecycle(a.scope()).equals(lifecycle));
            if(lifecycle.frozen()==null) {
                require(operations.isEmpty());
                requireLifecycleOperations(lifecycle,null,null,false);
                if(read.ownedAdvance())require(OwnedOptionalTrialClockAdvance.ready(jdbc,a.scope(),a.trialEnd()));
                require(jdbc.update("""
                    insert into stripe_billing_optional_trial_pauses
                      (clock_intent,subscription_id,create_operation,frozen_time,original_bill_id,trial_started_event_id)
                    values (?,?,?,?,?,?)
                    """,a.clockIntent(),a.subscription(),a.createOperation(),a.trialEnd(),lifecycle.bill(),lifecycle.started())==1);
            } else if(operations.size()==1&&operations.getFirst().kind()==SubscriptionOperation.Kind.SETUP) {
                requireLifecycleOperations(lifecycle,read.setup(),null,true);
                requireSetupProof(command.account(),lifecycle,operations.getFirst().id());
            } else {
                require(operations.size()==1&&operations.getFirst().kind()==SubscriptionOperation.Kind.RESUME);
                requireLifecycleOperations(lifecycle,read.setup(),operations.getFirst().id(),false);
                requireSetupProof(command.account(),lifecycle,read.setup());
                var resume=operations.getFirst().id();
                var paid=command.snapshot().status()==Subscription.Status.ACTIVE;
                var pending=pendingResume(lifecycle,resume,paid);
                if(paid) {
                    require(read.payment()!=null&&pending.invoice().equals(read.payment().invoice())
                        &&pending.invoice().equals(command.snapshot().invoices().getLast().reference()));
                    require(read.payment().equals(OwnedOptionalTrialPaymentConfirmation.acknowledged(jdbc,a,read.setup(),resume)));
                }
            }
            return;
        }
        require(verified instanceof Verified);
        var read = (Verified)verified;
        require(read.command()==command && s.id().equals(read.receipt().authority().subscription())
            && operations.size()==1 && operations.getFirst().id().equals(read.receipt().authority().createOperation())
            && billingAt.equals(command.expectedBillingSample().billingAt())
            && pending(read.receipt().authority().scope(), true).equals(read.receipt()));
    }

    @Override public Sample sample(PaymentAccountId account, SiteId site, CustomerId customer, Instant operationalAt) {
        require(transactions.active());
        var times = jdbc.query("""
            select coalesce(p.frozen_time,a.frozen_time) from stripe_billing_clock_intents c
            left join lateral (
                select admitted.* from stripe_billing_optional_trial_acknowledgements admitted
                where admitted.clock_intent=c.id and admitted.account_id=? offset 0
            ) a on true
            left join stripe_billing_optional_trial_pauses p on p.clock_intent=a.clock_intent and p.subscription_id=a.subscription_id
              and p.create_operation=a.create_operation and p.frozen_time=a.trial_end
            where c.site_id=? and c.customer_id=?
            """, (r,n)->r.getObject(1,Long.class), account.value(),site.value(),customer.value());
        if (times.isEmpty()) return new Sample(operationalAt, Mode.ORDINARY);
        require(times.size()==1 && times.getFirst()!=null);
        var verified=TransactionSynchronizationManager.getResource(verificationKey);
        if(verified instanceof VerifiedLifecycle read&&read.lifecycle().frozen()==null) {
            var a=read.lifecycle().original().authority();
            require(a.account().equals(account.value())&&a.site().equals(site.value())&&a.customer().equals(customer.value())
                &&times.getFirst()==a.frozen());
            return new Sample(Instant.ofEpochSecond(a.trialEnd()),Mode.ISOLATED);
        }
        return new Sample(Instant.ofEpochSecond(times.getFirst()), Mode.ISOLATED);
    }

    @Override public Sample sampleForRead(Subscription s, Instant at) {
        var time = sample(s.paymentAccountId(), s.siteId(), s.customerId(), at);
        if (time.isolated()) {
            require(jdbc.queryForObject("""
                select count(*) from stripe_billing_optional_trial_acknowledgements where subscription_id=? and account_id=?
                """, Integer.class, s.id(), s.paymentAccountId().value())==1);
        }
        return time;
    }

    @Override public void requireUnscopedAllowed(SiteId site, CustomerId customer) {
        new OwnedSubscriptionBillingTime(jdbc).requireUnscopedAllowed(site,customer);
    }

    private static void require(boolean ok) {
        if (!ok) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Optional trial publication authority unavailable");
    }
}
