package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockSubscriptionStore;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

class OwnedOptionalTrialClaimIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired StripeBillingClockSubscriptionStore paid;

    private static final String CLAIM = "select claim_owned_optional_trial(?,?,?,?,?,?)";
    private static final String FINISH = "select finish_owned_optional_trial(?,?,?,?,?)";
    @Test void trialControlsHaveTheirOwnHistoryAfterTheProductionMigrations() {
        assertThat(jdbc.queryForList("""
            select version from flyway_schema_history where success and version is not null order by installed_rank
            """, String.class)).containsExactlyElementsOf(dev.genericpayments.platform.support.CurrentSchemaVersions.EXPECTED);
        assertThat(jdbc.queryForList("""
            select version from owned_optional_trial_schema_history where success and type='SQL' order by installed_rank
            """, String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    private record Candidate(UUID run, UUID clock, UUID subscription, UUID operation, String fingerprint) {}

    private Candidate candidate(Reservation r) {
        return jdbc.queryForObject("""
            select run_id,clock_intent,subscription_id,create_operation,fingerprint
            from stripe_billing_optional_trial_candidates where run_id=? and customer_id=? and price_id=?
            """, (rs, index) -> new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5)),
            r.owner().run(),r.owner().customer().value(),r.price());
    }

    private boolean claim(Candidate c, UUID claim) {
        return jdbc.queryForObject(CLAIM,Boolean.class,c.run(),c.clock(),c.subscription(),c.operation(),c.fingerprint(),claim);
    }

    private boolean claim(Connection connection, Candidate c, UUID claim) throws SQLException {
        try (var statement = connection.prepareStatement(CLAIM)) {
            statement.setObject(1, c.run());
            statement.setObject(2, c.clock());
            statement.setObject(3, c.subscription());
            statement.setObject(4, c.operation());
            statement.setString(5, c.fingerprint());
            statement.setObject(6, claim);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private boolean finish(UUID claim) {
        return jdbc.queryForObject(FINISH,Boolean.class,claim,"sub_trial","si_trial","in_trial",FROZEN);
    }

    private boolean uncertain(UUID claim) {
        return jdbc.queryForObject(FINISH,Boolean.class,claim,null,null,null,null);
    }

    private void assertStatus(String value) {
        assertThat(jdbc.queryForList("select status from stripe_billing_owned_optional_trial_creates",String.class)).containsExactly(value);
        assertThat(jdbc.queryForList("select status from provider_operations where kind='SUBSCRIPTION_CREATE'",String.class)).containsExactly(value);
    }

    @Test void genuineCandidateAndAcknowledgementOwnExactlyOneOriginalStepAndVerifiedTrialProof() {
        var r=reserveOptionalTrial();
        var c=candidate(r);
        assertThat(c.clock()).isEqualTo(r.owner().intent());
        assertThat(c.subscription()).isEqualTo(r.reserved().subscription().id());
        assertThat(c.operation()).isEqualTo(r.reserved().operation().id());
        assertThat(c.fingerprint()).matches("[a-f0-9]{64}");
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
        var claim=UUID.randomUUID();
        assertThat(claim(c,claim)).isTrue();
        assertStatus("DISPATCHING");
        assertThat(claim(c,claim)).isFalse();
        assertThat(finish(UUID.randomUUID())).isFalse();
        assertThat(finish(claim)).isTrue();
        assertStatus("ACKNOWLEDGED");
        assertThat(finish(claim)).isFalse();
        assertThat(uncertain(claim)).isFalse();
        assertThat(claim(c,UUID.randomUUID())).isFalse();
        assertThat(proofs.trial(billing.id(),c.subscription()).orElseThrow().verified()).isTrue();
        assertThat(jdbc.queryForObject("""
            select count(*) from stripe_billing_owned_optional_trial_creates t
            join stripe_subscription_trial_proofs v on v.operation_id=t.create_operation and v.step_id=t.step_id
              and v.subscription_reference=t.subscription_reference and v.item_reference=t.item_reference
              and v.invoice_reference=t.invoice_reference and v.trial_start=t.trial_start and v.requested_end=t.trial_end
            where t.claim_id=? and t.trial_start=? and t.trial_end=? and t.subscription_reference='sub_trial'
            """,Integer.class,claim,FROZEN,FROZEN+604800L)).isEqualTo(1);
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events")).isZero();
        assertThat(count("stripe_billing_owned_subscriptions")).isZero();
    }

    @Test void twoDatabaseConnectionsCompeteForTheSameUncommittedClaim() throws Exception {
        var c = candidate(reserveOptionalTrial());
        var winner = UUID.randomUUID();
        var started = new CountDownLatch(1);
        try (var first = jdbc.getDataSource().getConnection();
             var second = jdbc.getDataSource().getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try {
                assertThat(claim(first, c, winner)).isTrue();
                assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
                final int secondPid;
                try (var query = second.createStatement(); var rs = query.executeQuery("select pg_backend_pid()")) {
                    assertThat(rs.next()).isTrue();
                    secondPid = rs.getInt(1);
                }
                var competing = pool.submit(() -> {
                    started.countDown();
                    return claim(second, c, UUID.randomUUID());
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                // Confirm the second session reached the claim before releasing the first.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!competing.isDone() && !jdbc.queryForObject(
                        "select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, secondPid)) {
                    assertThat(System.nanoTime()).as("Competing PostgreSQL claim deadline").isLessThan(deadline);
                    Thread.sleep(10);
                }
                first.commit();
                assertThat(competing.get(10, TimeUnit.SECONDS)).isFalse();
            } finally {
                first.rollback();
            }
        }
        assertStatus("DISPATCHING");
        assertThat(finish(winner)).isTrue();
        assertStatus("ACKNOWLEDGED");
    }

    @Test void aRepeatableReadSnapshotCannotClaimTheOptionalBudget() throws Exception {
        var c = candidate(reserveOptionalTrial());
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> claim(connection, c, UUID.randomUUID()))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                        assertThat(failure.getMessage()).contains("requires READ COMMITTED"));
            } finally {
                connection.rollback();
            }
        }
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
    }

    @Test void aRepeatableReadSnapshotCannotInsertThePaidSideOfTheSameClockBudget() throws Exception {
        var r = reserveTrialProfile(0, "OPTIONAL_DURING_TRIAL", 2000, false);
        paid.admitFresh(billing, r.owner(), r.reserved().subscription().id(), r.reserved().operation().id(), Instant.now());
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var repeat = connection.prepareStatement("""
                insert into stripe_billing_owned_subscriptions select * from stripe_billing_owned_subscriptions where clock_intent=?
                """)) {
                repeat.setObject(1, r.owner().intent());
                assertThatThrownBy(repeat::executeUpdate).isInstanceOfSatisfying(SQLException.class, failure ->
                    assertThat(failure.getMessage()).contains("requires READ COMMITTED"));
            } finally {
                connection.rollback();
            }
        }
        assertThat(count("stripe_billing_owned_subscriptions")).isOne();
    }

    @Test void directReceiptInsertionContendsOnTheSameClockRowAsPaidOwnership() throws Exception {
        var r = reserveOptionalTrial();
        var c = candidate(r);
        var claim = UUID.randomUUID();
        var step = UUID.randomUUID();
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,
                operation_key,fingerprint,status,first_dispatched_at,retry_before,lease_owner,lease_expires_at,version)
            select ?,account_id,external_account,credentials_ref,'SUBSCRIPTION_CREATE',create_operation,
                operation_key,fingerprint,'DISPATCHING',now(),now()+interval '23 hours',?,now()+interval '60 seconds',1
            from stripe_billing_optional_trial_candidates where clock_intent=?
            """, step, claim.toString(), c.clock());
        String insert = """
            insert into stripe_billing_owned_optional_trial_creates
              (clock_intent,subscription_id,create_operation,claim_id,step_id,fingerprint,trial_end,claimed_at,status)
            select c.clock_intent,c.subscription_id,c.create_operation,?::uuid,p.id,c.fingerprint,
              c.trial_end,p.first_dispatched_at,'DISPATCHING'
            from stripe_billing_optional_trial_candidates c join provider_operations p on p.resource_id=c.create_operation
            where p.id=?
            """;
        try (var owner = jdbc.getDataSource().getConnection(); var contender = jdbc.getDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try (var hold = owner.prepareStatement("select id from stripe_billing_clock_intents where id=? for no key update");
                 var write = contender.prepareStatement(insert)) {
                hold.setObject(1, c.clock());
                try (var result = hold.executeQuery()) { assertThat(result.next()).isTrue(); }
                contender.setAutoCommit(false);
                try (var setting = contender.createStatement()) { setting.execute("set local lock_timeout='350ms'"); }
                write.setObject(1, claim);
                write.setObject(2, step);
                // FK key-share locks would be compatible with the owner's lock.
                assertThatThrownBy(write::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } finally {
                contender.rollback();
                owner.rollback();
            }
        }
        assertThat(jdbc.update(insert, claim, step)).isOne();
        assertStatus("DISPATCHING");
    }

    @Test void unknownIsTerminalAndACrashedDispatchCannotBeClaimedAgain() {
        var c=candidate(reserveOptionalTrial());
        var claim=UUID.randomUUID();
        assertThat(claim(c,claim)).isTrue();
        assertThat(claim(c,UUID.randomUUID())).isFalse();
        assertThat(uncertain(claim)).isTrue();
        assertStatus("UNKNOWN");
        assertThat(finish(claim)).isFalse();
        assertThat(uncertain(claim)).isFalse();
        assertThat(claim(c,UUID.randomUUID())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from stripe_subscription_trial_proofs where step_id is not null",Integer.class)).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test void malformedOrStaleAcknowledgementCannotVerifyAnUncertainDispatch() {
        var c = candidate(reserveOptionalTrial());
        var claim = UUID.randomUUID();
        assertThat(claim(c, claim)).isTrue();
        assertThat(jdbc.queryForObject(FINISH, Boolean.class, claim, "sub_trial", null, "in_trial", FROZEN)).isFalse();
        assertThat(jdbc.queryForObject(FINISH, Boolean.class, claim, "sub_trial", "si_trial", "in_trial", FROZEN + 1)).isFalse();
        assertStatus("DISPATCHING");
        assertThat(proofs.trial(billing.id(), c.subscription()).orElseThrow().verified()).isFalse();

        assertThat(uncertain(claim)).isTrue();
        assertStatus("UNKNOWN");
        assertThat(proofs.trial(billing.id(), c.subscription()).orElseThrow().verified()).isFalse();
    }

    @Test void anIncompleteTerminalWriteRollsBackAtCommit() {
        var c = candidate(reserveOptionalTrial());
        var claim = UUID.randomUUID();
        assertThat(claim(c, claim)).isTrue();

        assertThatThrownBy(() -> jdbc.update("""
            update stripe_billing_owned_optional_trial_creates set status='UNKNOWN' where claim_id=?
            """, claim)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("Optional trial evidence must be atomic");

        assertStatus("DISPATCHING");
        assertThat(uncertain(claim)).isTrue();
        assertStatus("UNKNOWN");
    }

    @Test void anotherRunClockReservationOrFingerprintCannotClaim() {
        var c=candidate(reserveOptionalTrial());
        for(var foreign:new Candidate[]{
             new Candidate(UUID.randomUUID(),c.clock(),c.subscription(),c.operation(),c.fingerprint()),
             new Candidate(c.run(),UUID.randomUUID(),c.subscription(),c.operation(),c.fingerprint()),
             new Candidate(c.run(),c.clock(),UUID.randomUUID(),c.operation(),c.fingerprint()),
             new Candidate(c.run(),c.clock(),c.subscription(),UUID.randomUUID(),c.fingerprint()),
             new Candidate(c.run(),c.clock(),c.subscription(),c.operation(),"0".repeat(64))}) {
            assertThat(claim(foreign,UUID.randomUUID())).isFalse();
        }
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='SUBSCRIPTION_CREATE'",Integer.class)).isZero();
    }

    @Test void aSecondAuthenticatedCustomersValidTrialReservationCannotSpendTheOriginalClock() {
        var reservation = reserveOptionalTrial();
        var original = candidate(reservation);
        customer = new CustomerId(UUID.randomUUID());
        createFixtureCustomer();
        var foreign = reserveFor(customer, reservation.price());
        proofs.reserveTrial(foreign.subscription(), foreign.operation(), FROZEN + 604800L);

        assertThat(jdbc.queryForList("select customer_id from stripe_billing_optional_trial_candidates", UUID.class))
            .containsExactly(reservation.owner().customer().value());
        var substituted = new Candidate(original.run(), original.clock(), foreign.subscription().id(),
            foreign.operation().id(), original.fingerprint());
        assertThat(claim(substituted, UUID.randomUUID())).isFalse();
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='SUBSCRIPTION_CREATE'",Integer.class)).isZero();
        assertThat(claim(original, UUID.randomUUID())).isTrue();
    }

    @Test void aDifferentSandboxAccountWithInternallyConsistentClockAndCustomerCannotClaim() {
        var r = reserveTrialProfile(7, "OPTIONAL_DURING_TRIAL", 2000, true, "acct_otherSandbox");
        assertThat(r.owner().externalAccount()).isEqualTo("acct_otherSandbox");
        assertThat(clockOperation(r.owner()).status().name()).isEqualTo("ACKNOWLEDGED");
        assertThat(customerOperation(r.owner()).status().name()).isEqualTo("ACKNOWLEDGED");
        assertThat(proofs.trial(billing.id(), r.reserved().subscription().id()).orElseThrow().verified()).isFalse();
        var fingerprints = jdbc.queryForList(
            "select fingerprint from stripe_billing_optional_trial_candidates where run_id=?", String.class, r.owner().run());
        var attempted = new Candidate(r.owner().run(), r.owner().intent(), r.reserved().subscription().id(),
            r.reserved().operation().id(), fingerprints.isEmpty() ? "0".repeat(64) : fingerprints.getFirst());
        assertThat(claim(attempted, UUID.randomUUID())).isFalse();
        assertThat(fingerprints).isEmpty();
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='SUBSCRIPTION_CREATE'", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"7,OPTIONAL_DURING_TRIAL,2001,true","6,OPTIONAL_DURING_TRIAL,2000,true",
        "7,REQUIRED_BEFORE_TRIAL,2000,true","7,OPTIONAL_DURING_TRIAL,2000,false"})
    void differentPriceTrialTermsOrMissingV27ReservationCannotAdmit(int days,String policy,int amount,boolean reserveProof) {
        var r=reserveTrialProfile(days,policy,amount,reserveProof);
        assertThat(jdbc.queryForList("select run_id from stripe_billing_optional_trial_candidates")).isEmpty();
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
        assertThat(paid.find(billing.id(),r.reserved().subscription().id())).isEmpty();
    }

    @Test void v31PaidCreateStillAdmitsAndCannotBecomeAnOptionalTrial() {
        var r=reserveTrialProfile(0,"OPTIONAL_DURING_TRIAL",2000,false);
        var subscription=r.reserved().subscription().id();
        var owned=paid.admitFresh(billing,r.owner(),subscription,r.reserved().operation().id(),Instant.now());
        assertThat(owned.subscription()).isEqualTo(subscription);
        assertThat(count("stripe_billing_owned_subscriptions")).isOne();
        assertThat(jdbc.queryForList("select run_id from stripe_billing_optional_trial_candidates")).isEmpty();
        assertThat(claim(new Candidate(r.owner().run(),r.owner().intent(),subscription,r.reserved().operation().id(),owned.fingerprint()),UUID.randomUUID())).isFalse();
        assertThat(paid.requireCurrent(owned)).isEqualTo(owned);
        assertThat(count("stripe_billing_owned_optional_trial_creates")).isZero();
    }
}
