package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
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
    private final Object verificationKey = new Object();
    private final JdbcTemplate jdbc;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final SubscriptionTransactionPort transactions;
    private final ObjectProvider<ApplySubscriptionSnapshotUseCase> facts;
    private final Clock operationalClock;

    private OwnedOptionalTrialPublication(JdbcTemplate jdbc, StripeCredentialResolver credentials, StripeClientFactory clients,
            PaymentAccountVerificationPort verification, SubscriptionTransactionPort transactions,
            ObjectProvider<ApplySubscriptionSnapshotUseCase> facts, Clock operationalClock) {
        this.jdbc=jdbc; this.credentials=credentials; this.clients=clients; this.verification=verification;
        this.transactions=transactions; this.facts=facts; this.operationalClock=operationalClock;
    }

    @TestConfiguration(proxyBeanMethods=false)
    public static class Configuration {
        @Bean @Primary OwnedOptionalTrialPublication optionalTrialPublication(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                StripeClientFactory clients, PaymentAccountVerificationPort verification, SubscriptionTransactionPort transactions,
                ObjectProvider<ApplySubscriptionSnapshotUseCase> facts, Clock operationalClock) {
            return new OwnedOptionalTrialPublication(jdbc, credentials, clients, verification, transactions, facts, operationalClock);
        }
    }

    public void publish(PaymentAccount account, Scope scope) {
        require(!transactions.active());
        var receipt = pending(scope, false);
        var a = receipt.authority();
        require(a.account().equals(account.id().value()) && account.status()==PaymentAccountStatus.ACTIVE
            && account.provider()==PaymentProviderType.STRIPE && account.mode()==PaymentAccountMode.SANDBOX
            && a.externalAccount().equals(account.externalAccountId()) && a.credentialsRef().equals(account.credentialsRef())
            && "stripe-sandbox-default".equals(account.key()));
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
            select a.frozen_time from stripe_billing_clock_intents c
            left join stripe_billing_optional_trial_acknowledgements a on a.clock_intent=c.id and a.account_id=?
            where c.site_id=? and c.customer_id=?
            """, (r,n)->r.getObject(1,Long.class), account.value(),site.value(),customer.value());
        if (times.isEmpty()) return new Sample(operationalAt, Mode.ORDINARY);
        require(times.size()==1 && times.getFirst()!=null);
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
        if (!ok) throw new IllegalStateException("Optional trial publication authority unavailable");
    }
}
