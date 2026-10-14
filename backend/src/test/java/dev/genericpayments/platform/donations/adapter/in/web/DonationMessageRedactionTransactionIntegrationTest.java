package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.donations.application.port.in.RedactDonationMessageUseCase;
import dev.genericpayments.platform.donations.domain.DonorMessageState;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DonationMessageRedactionTransactionIntegrationTest extends DonationQueryTestSupport {
    @Autowired RedactDonationMessageUseCase redaction;
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired LockSitePort locks;
    @Autowired PlatformTransactionManager manager;
    @Autowired dev.genericpayments.platform.payments.application.service.IdempotencyService receipts;

    @Test void auditFailureRollsBackTupleAndReceiptThenSameKeyCanSucceed() throws Exception {
        var payment = donation(null, MESSAGE);
        var before = snapshot();
        jdbc.execute("create function task6_reject_audit() returns trigger language plpgsql as $$ begin raise exception 'injected rollback'; end $$");
        jdbc.execute("create trigger task6_reject_audit before insert on audit_log for each row execute function task6_reject_audit()");
        try {
            redact(payment.donationId().value(), payment.donationId().value().toString(), "rollback")
                .andExpect(status().is5xxServerError());
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("drop trigger task6_reject_audit on audit_log");
            jdbc.execute("drop function task6_reject_audit()");
        }
        redact(payment.donationId().value(), payment.donationId().value().toString(), "rollback")
            .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        assertThat(financialSnapshot()).isEqualTo(before.subList(3, before.size()));
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where idempotency_key='rollback' and status='COMPLETED'", Integer.class)).isOne();
    }

    @Test void siteLockSerializesAnonymizationAndExplicitRedactionWithoutStaleResurrection() throws Exception {
        var customer = customer();
        var payment = donation(customer, MESSAGE);
        var stale = donations.findById(site, payment.donationId()).orElseThrow();
        var encrypted = donations.findEncryptedMessage(site, payment.donationId()).orElseThrow();
        var financial = financialSnapshot();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var anonymization = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                locks.findByIdForUpdate(site);
                entered.countDown();
                await(release);
                return anonymizer.anonymize(new AnonymizeCustomerUseCase.Command(site, customer.id(), customer.id().value(),
                    "anonymize", "ADMIN", "safe-actor", "task6-race", "127.0.0.1"));
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var explicit = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                jdbc.execute("set local lock_timeout='10s'");
                pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return redaction.redact(payment.donationId().value(), payment.donationId().value(), UUID.randomUUID(), "explicit");
            }));
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(pid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')", Boolean.class, pid.get()))).isTrue());
            } finally { release.countDown(); }
            anonymization.get(10, TimeUnit.SECONDS);
            var result = explicit.get(10, TimeUnit.SECONDS);
            assertThat(result.messageState()).isEqualTo(DonorMessageState.REDACTED);
            assertThat(result.changed()).isFalse();
        } finally { release.countDown(); }
        assertThat(donations.findEncryptedMessage(site, payment.donationId())).isEmpty();
        assertThatThrownBy(() -> donations.save(stale, encrypted)).isInstanceOf(RuntimeException.class);
        assertThat(donations.findEncryptedMessage(site, payment.donationId())).isEmpty();
        assertThat(financialSnapshot()).isEqualTo(financial);
    }

    @Test void exactSiteAndOperationNamespacesDoNotCollideAndConcurrentReplayTransitionsOnce() throws Exception {
        var first = donation(null, MESSAGE);
        var foreign = foreignDonation();
        var actor = UUID.randomUUID();
        var financial = financialSnapshot();
        var oldScope = dev.genericpayments.platform.payments.application.model.IdempotencyRecord.Scope.admin(site.value(), actor,
            dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation.PAYMENT_CREATE);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var owner = (dev.genericpayments.platform.payments.application.service.IdempotencyService.Owner) receipts.begin(
                oldScope, "shared-site-key", receipts.fingerprint(Map.of("safe", "prior operation")));
            receipts.complete(owner, new dev.genericpayments.platform.payments.application.model.IdempotencyRecord.Resource(
                dev.genericpayments.platform.payments.application.model.IdempotencyRecord.ResourceType.PAYMENT, first.id().value()), 200, null);
        });
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<dev.genericpayments.platform.donations.application.model.DonationViews.Redaction>>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                ready.countDown(); await(start);
                return redaction.redact(first.donationId().value(), first.donationId().value(), actor, "shared-site-key");
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var one = results.getFirst().get(10, TimeUnit.SECONDS);
            var two = results.getLast().get(10, TimeUnit.SECONDS);
            assertThat(List.of(one, two).stream().filter(r -> r.changed()).count()).isOne();
            assertThat(List.of(one, two).stream().filter(r -> r.replayed()).count()).isOne();
        } finally { start.countDown(); }
        assertThat(redaction.redact(foreign.donationId().value(), foreign.donationId().value(), actor, "shared-site-key").messageState()).isEqualTo(DonorMessageState.ABSENT);
        assertThat(jdbc.queryForList("select scope from idempotency_records where idempotency_key='shared-site-key'", String.class))
            .containsExactlyInAnyOrder(oldScope.value(),
                "admin:" + site.value() + ":" + actor + ":donation-messages:redact",
                "admin:" + foreign.siteId().value() + ":" + actor + ":donation-messages:redact");
        assertThat(financialSnapshot()).isEqualTo(financial);
    }

    private List<Object> snapshot() {
        var result = new ArrayList<Object>();
        for (var table : List.of("donations", "idempotency_records", "audit_log"))
            result.add(jdbc.queryForList("select row_to_json(t)::text from " + table + " t order by id"));
        result.addAll(financialSnapshot());
        return result;
    }

    private List<Object> financialSnapshot() {
        var result = new ArrayList<Object>();
        for (var table : List.of("payments", "payment_events", "outbox_events", "outbox_deliveries", "outbox_retry_requests"))
            result.add(jdbc.queryForList("select row_to_json(t)::text from " + table + " t order by id"));
        return result;
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
