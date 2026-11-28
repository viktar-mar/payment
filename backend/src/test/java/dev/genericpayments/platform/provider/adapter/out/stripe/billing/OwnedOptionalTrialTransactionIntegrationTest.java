package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import static org.assertj.core.api.Assertions.*;

class OwnedOptionalTrialTransactionIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired StripeCredentialResolver credentials;
    @Autowired StripeClientFactory clients;

    /** Real pooled connections, with a selectable fault at the JDBC commit boundary. */
    private static final class ManualCommitSource extends DelegatingDataSource {
        int failBefore, failAfter;
        Runnable afterClaim = () -> {};
        final AtomicInteger commits = new AtomicInteger();

        ManualCommitSource(DataSource source) { super(source); }

        @Override public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        int number = commits.incrementAndGet();
                        if (number == failBefore) throw new SQLException("Simulated commit refusal");
                        connection.commit();
                        if (number == 1) afterClaim.run();
                        if (number == failAfter) throw new SQLException("Simulated lost commit acknowledgement");
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        }
    }

    private OwnedOptionalTrialCreate.Scope prepare() {
        var r = reserveOptionalTrial();
        var scope = new OwnedOptionalTrialCreate.Scope(r.owner().run(), r.owner().intent(),
            r.reserved().subscription().id(), r.reserved().operation().id());
        http.calls.clear();
        var authority = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
        OwnedOptionalTrialSdkReplies.queue(http, authority, "");
        return scope;
    }

    private String state(OwnedOptionalTrialCreate.Scope scope) {
        return jdbc.queryForObject("""
            select r.status from stripe_billing_owned_optional_trial_creates r
            join provider_operations p on p.id=r.step_id and p.status=r.status
            where r.clock_intent=? and r.subscription_id=? and r.create_operation=?
            """, String.class, scope.clockIntent(), scope.subscription(), scope.createOperation());
    }

    private long posts() {
        return http.calls.stream().filter(request -> request.method().name().equals("POST")).count();
    }

    private void observeFirstPost(OwnedOptionalTrialCreate.Scope scope) {
        http.afterResponse = () -> {
            if (posts() == 0) {
                observeFirstPost(scope);
            } else {
                assertThat(state(scope)).isEqualTo("DISPATCHING");
                assertThat(proofs.trial(billing.id(), scope.subscription()).orElseThrow().verified()).isFalse();
            }
        };
    }

    private void reenter(OwnedOptionalTrialCreate.Scope scope) {
        int prior = http.calls.size();
        assertThatThrownBy(() -> OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification)
            .create(billing, scope)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(prior);
    }

    @Test void manualCommitMakesTheExactClaimVisibleToAnIndependentConnectionDuringThePost() {
        var scope = prepare();
        var source = new ManualCommitSource(jdbc.getDataSource());
        observeFirstPost(scope);
        OwnedOptionalTrialCreate.withDatabase(new JdbcTemplate(source), credentials, clients, verification).create(billing, scope);

        assertThat(posts()).isOne();
        assertThat(source.commits.get()).isEqualTo(2);
        assertThat(state(scope)).isEqualTo("ACKNOWLEDGED");
        assertThat(proofs.trial(billing.id(), scope.subscription()).orElseThrow().verified()).isTrue();
        reenter(scope);
    }

    @ParameterizedTest
    @CsvSource({"0,1,0,DISPATCHING","2,0,1,UNKNOWN","0,2,1,ACKNOWLEDGED"})
    void commitFailuresNeverRepeatThePost(int failBefore, int failAfter, int expectedPosts, String expectedState) {
        var scope = prepare();
        var source = new ManualCommitSource(jdbc.getDataSource());
        source.failBefore = failBefore;
        source.failAfter = failAfter;
        var create = OwnedOptionalTrialCreate.withDatabase(new JdbcTemplate(source), credentials, clients, verification);
        assertThatThrownBy(() -> create.create(billing, scope)).isInstanceOf(IllegalStateException.class);

        assertThat(posts()).isEqualTo(expectedPosts);
        assertThat(state(scope)).isEqualTo(expectedState);
        assertThat(proofs.trial(billing.id(), scope.subscription()).orElseThrow().verified())
            .isEqualTo(expectedState.equals("ACKNOWLEDGED"));
        reenter(scope);
    }

    @Test void revokedClockBetweenCommittedClaimAndProviderDispatchNeverPosts() {
        var scope = prepare();
        var source = new ManualCommitSource(jdbc.getDataSource());
        source.afterClaim = () -> jdbc.update("""
            update stripe_billing_clock_intents set stopped_at=clock_timestamp(),stop_reason='AUTHORITY_REVOKED' where id=?
            """, scope.clockIntent());
        var create = OwnedOptionalTrialCreate.withDatabase(new JdbcTemplate(source), credentials, clients, verification);
        assertThatThrownBy(() -> create.create(billing, scope)).isInstanceOf(IllegalStateException.class);
        assertThat(posts()).isZero();
        reenter(scope);
    }

    @Test void expiredLeaseWhileClaimAcknowledgementIsDelayedNeverPosts() {
        var scope = prepare();
        jdbc.execute("""
            create function test_short_optional_lease() returns trigger language plpgsql as $$
            begin
              new.lease_expires_at := new.first_dispatched_at + interval '1 millisecond';
              return new;
            end $$;
            create trigger test_short_optional_lease before insert on provider_operations
            for each row when (new.kind='SUBSCRIPTION_CREATE') execute function test_short_optional_lease()
            """);
        try {
            var source = new ManualCommitSource(jdbc.getDataSource());
            source.afterClaim = () -> jdbc.execute("select pg_sleep(0.02)");
            var create = OwnedOptionalTrialCreate.withDatabase(new JdbcTemplate(source), credentials, clients, verification);
            assertThatThrownBy(() -> create.create(billing, scope)).isInstanceOf(IllegalStateException.class);
            assertThat(posts()).isZero();
            reenter(scope);
        } finally {
            jdbc.execute("drop trigger test_short_optional_lease on provider_operations");
            jdbc.execute("drop function test_short_optional_lease()");
        }
    }
}
