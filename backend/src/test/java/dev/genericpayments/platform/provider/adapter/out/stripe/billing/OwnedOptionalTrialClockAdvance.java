package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockReady;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.*;

/** One explicitly constructed test-only advance from the published trial to its original end. */
final class OwnedOptionalTrialClockAdvance {
    private static final Clock UTC = Clock.systemUTC();

    private record Original(Authority authority, Created created, String fingerprint) {}
    private record Claim(UUID id, String status, String fingerprint, boolean live, boolean ready) {}
    private record Current(Original original, Claim claim) {}

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;

    private OwnedOptionalTrialClockAdvance(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                                             StripeClientFactory clients, PaymentAccountVerificationPort verification) {
        this.jdbc = jdbc;
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource());
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(5);
        this.credentials = credentials;
        this.clients = clients;
        this.verification = verification;
    }

    static OwnedOptionalTrialClockAdvance withDatabase(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                                                       StripeClientFactory clients, PaymentAccountVerificationPort verification) {
        return new OwnedOptionalTrialClockAdvance(jdbc, credentials, clients, verification);
    }

    long advance(PaymentAccount account, Scope scope) {
        requireIndependent();
        var budget = new InvocationBudget(UTC, System::nanoTime);
        var state = current(jdbc, scope);
        require(state != null && state.claim() == null);
        var original = state.original();
        var a = original.authority();
        var claim = UUID.randomUUID();
        boolean acquired = false;
        try {
            var client = verifiedClient(account, a);
            budget.remainingNanos(null);
            rereadClockScoped(client, a, original.created());
            budget.remainingNanos(null);
            require(committed("""
                insert into stripe_billing_optional_trial_clock_advances
                  (clock_intent,claim_id,fingerprint,frozen_start,target,claimed_at,status)
                values (?,?,?,?,?,clock_timestamp(),'DISPATCHING') on conflict do nothing
                """, a.clockIntent(), claim, original.fingerprint(), a.frozen(), a.trialEnd()));
            acquired = true;
            var exchange = exchange(client, a, budget);
            exchange.acknowledge(() -> {
                var live = requireClaim(original, claim, "DISPATCHING");
                require(live.live());
                budget.remainingNanos(null);
            }, (post, at) -> {
                budget.remainingNanos(null);
                require(committed("""
                    update stripe_billing_optional_trial_clock_advances
                    set status='ACKNOWLEDGED', post_status=?, post_frozen_time=?, post_advancing_target=?
                    where claim_id=? and status='DISPATCHING'
                    """, post.status(), post.frozenTime(), post.advancingTarget(), claim));
                return null;
            });
            return observe(exchange, original, claim, budget);
        } catch (StripeException | RuntimeException failure) {
            if (acquired) {
                try {
                    committed("""
                        update stripe_billing_optional_trial_clock_advances set status='UNKNOWN'
                        where claim_id=? and status='DISPATCHING'
                        """, claim);
                } catch (RuntimeException ignored) {
                    // An unresolved commit or a surviving DISPATCHING row still consumes the original budget.
                }
            }
            throw unavailable();
        }
    }

    long observeReady(PaymentAccount account, Scope scope) {
        requireIndependent();
        var budget = new InvocationBudget(UTC, System::nanoTime);
        var state = current(jdbc, scope);
        require(state != null && state.claim() != null);
        var original = state.original();
        var a = original.authority();
        requireAccount(account, a);
        var claim = requireClaim(original, state.claim().id(), "ACKNOWLEDGED");
        if (claim.ready()) return a.trialEnd();
        return observe(exchange(verifiedClient(account, a), a, budget), original, claim.id(), budget);
    }

    static boolean ready(JdbcTemplate jdbc, Scope scope, long frozen) {
        var state = current(jdbc, scope);
        return state != null && state.original().authority().trialEnd() == frozen
            && state.claim() != null && "ACKNOWLEDGED".equals(state.claim().status()) && state.claim().ready()
            && state.original().fingerprint().equals(state.claim().fingerprint());
    }

    private long observe(OwnedGraceClockExchange exchange, Original original, UUID claim, InvocationBudget budget) {
        var a = original.authority();
        return exchange.recordReady(() -> {
            requireClaim(original, claim, "ACKNOWLEDGED");
            budget.remainingNanos(null);
        }, (observed, at) -> {
            require(observed.frozenTime() == a.trialEnd() && observed.advancingTarget() == null);
            budget.remainingNanos(null);
            require(committed("""
                update stripe_billing_optional_trial_clock_advances set ready_at=clock_timestamp()
                where claim_id=? and status='ACKNOWLEDGED' and ready_at is null
                """, claim) || ready(jdbc, a.scope(), a.trialEnd()));
            require(requireClaim(original, claim, "ACKNOWLEDGED").ready());
            budget.remainingNanos(null);
            return a.trialEnd();
        });
    }

    private static OwnedGraceClockExchange exchange(StripeClient client, Authority a, InvocationBudget budget) {
        var predecessor = new TestClockReady(a.clockReference(), "test_helpers.test_clock", false, a.clockName(), a.frozen(),
            "ready", a.clockCreated(), a.clockDeletesAfter(), null);
        return new OwnedGraceClockExchange(client, predecessor, a.trialEnd(), "billing-optional-trial-advance-v1:"+a.clockIntent(),
            budget, null, UTC, System::nanoTime, OwnedOptionalTrialClockAdvance::wait);
    }

    private Claim requireClaim(Original original, UUID id, String status) {
        var state = current(jdbc, original.authority().scope());
        require(state != null && state.original().equals(original) && state.claim() != null
            && state.claim().id().equals(id) && state.claim().status().equals(status)
            && state.claim().fingerprint().equals(original.fingerprint()));
        return state.claim();
    }

    private static Current current(JdbcTemplate jdbc, Scope scope) {
        require(scope != null);
        var rows = jdbc.query("""
            with context as materialized (
              select * from stripe_billing_optional_trial_advance_contexts
              where run_id=? and clock_intent=? and subscription_id=? and create_operation=?
            )
            select a.*,c.claim_id,c.status,c.fingerprint as claim_fingerprint,c.ready_at is not null as ready,
                   clock_timestamp()>=c.claimed_at and c.claimed_at+interval '60 seconds'>clock_timestamp() as live
            from context a left join stripe_billing_optional_trial_clock_advances c on c.clock_intent=a.clock_intent
            """, (r, n) -> {
                var original = new Original(JdbcOwnedOptionalTrialCreateStore.authority(r),
                    new Created(r.getString("subscription_reference"), r.getString("item_reference"),
                        r.getString("invoice_reference"), r.getLong("trial_start")), r.getString("advance_fingerprint"));
                var id = r.getObject("claim_id", UUID.class);
                return new Current(original, id == null ? null : new Claim(id, r.getString("status"),
                    r.getString("claim_fingerprint"), r.getBoolean("live"), r.getBoolean("ready")));
            }, scope.run(), scope.clockIntent(), scope.subscription(), scope.createOperation());
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private StripeClient verifiedClient(PaymentAccount account, Authority a) {
        requireAccount(account, a);
        verification.verify(account);
        return clients.create(credentials.resolve(account));
    }

    private static void requireAccount(PaymentAccount account, Authority a) {
        require(account != null && a.account().equals(account.id().value()) && account.status() == PaymentAccountStatus.ACTIVE
            && account.provider() == PaymentProviderType.STRIPE && account.mode() == PaymentAccountMode.SANDBOX
            && APPROVED_ACCOUNT.equals(account.externalAccountId()) && a.externalAccount().equals(account.externalAccountId())
            && "stripe-sandbox-default".equals(account.key()) && a.credentialsRef().equals(account.credentialsRef())
            && a.clockDeletesAfter() > UTC.instant().getEpochSecond());
    }

    private boolean committed(String sql, Object... parameters) {
        requireIndependent();
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.update(sql, parameters) == 1));
    }

    private void requireIndependent() {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.hasResource(dataSource));
    }

    private static void wait(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static void require(boolean allowed) {
        if (!allowed) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Optional trial clock advance authority unavailable");
    }
}
