package dev.genericpayments.platform.shared.adapter.out.observability;

import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Zero-inclusive, configured contexts only; leases and future retry dates do not hide backlog. */
public class PendingOutboxGaugeRefreshAdapter implements PendingOutboxTelemetryPort,AutoCloseable {
    private final JdbcTemplate jdbc;
    private final Stage2TelemetryPort telemetry;
    private final Set<String> sites;
    private final TransactionTemplate read;
    private final AtomicBoolean requested=new AtomicBoolean();
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ReentrantLock refreshOwnership=new ReentrantLock();
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),task->{
        var thread=new Thread(task,"payment-outbox-gauge-refresh");thread.setDaemon(true);return thread;
    });

    public PendingOutboxGaugeRefreshAdapter(JdbcTemplate jdbc,Stage2TelemetryPort telemetry,Set<String> sites,
        PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.telemetry=telemetry;this.sites=Set.copyOf(sites);
        read=new TransactionTemplate(transactions);read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setTimeout(5);
        for(String site:this.sites)for(var provider:Provider.values())telemetry.requireContext(new Context(site,provider));
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void refreshPending() {
        try {refreshOwnership.lockInterruptibly();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        try {
            // A queued owner may acquire after shutdown; never start a fresh transaction then.
            if(closed.get())return;
            // Explicit template also applies on the owned background thread (no proxy/self-invocation gap).
            read.executeWithoutResult(tx->readCommittedPending());
        } finally {refreshOwnership.unlock();}
    }

    @Override public void requestRefresh() {
        if(closed.get())return;
        requested.set(true);
        if(!scheduled.compareAndSet(false,true))return;
        try {executor.execute(this::refreshRequested);}
        catch(RejectedExecutionException ignored){scheduled.set(false);}
    }

    private void refreshRequested() {
        requested.set(false);
        try {if(!closed.get())refreshPending();}
        catch(RuntimeException failure) {
            // Class only: database/provider exception messages can contain sensitive material.
            LoggerFactory.getLogger(getClass()).warn("Pending outbox telemetry refresh failed: {}",failure.getClass().getSimpleName());
            // Startup/idle worker refresh remains an independent recovery path; never spin on failure.
        } finally {
            scheduled.set(false);
            if(requested.get()&&!closed.get())requestRefresh();
        }
    }

    @Override public void close() {
        closed.set(true);executor.shutdownNow();
        try {if(!executor.awaitTermination(5,TimeUnit.SECONDS))
            LoggerFactory.getLogger(getClass()).warn("Pending outbox telemetry refresh shutdown timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }

    private void readCommittedPending() {
        for(String site:sites)for(var provider:Provider.values()) {
            long count=jdbc.queryForObject("""
                select
                  (select count(*) from outbox_events e join sites s on s.id=e.site_id
                    join payment_accounts a on a.id=e.payment_account_id
                    where s.site_key=? and a.provider=? and e.status='PENDING') +
                  (select count(*) from outbox_retry_requests r join outbox_events e on e.id=r.outbox_event_id
                    join sites s on s.id=e.site_id join payment_accounts a on a.id=e.payment_account_id
                    where s.site_key=? and a.provider=? and r.status<>'PROCESSED')
                """,Long.class,site,provider.name(),site,provider.name());
            telemetry.outboxPending(new Context(site,provider),count);
        }
    }
}
