package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.adapter.out.persistence.PendingProviderRecoveryPersistenceAdapter;
import dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRecoveryProofAdapter;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;

/** Bare scripted JDBC and actual public adapters; no DataSource, Spring, SQL engine or SDK runtime. */
class OwnedRecoveryConfirmIsolationTest {
    private static final Instant NOW=Instant.parse("2026-11-08T12:00:00Z");
    private static final Kind DEDICATED=Kind.OWNED_RECOVERY_INTENT_CONFIRM;

    @Test void ordinaryReservationRefusesDedicatedKindBeforeAccountOrResourceLocks(){
        var j=new BoundaryJdbc();var store=new JdbcProviderOperationStore(j);
        assertThatThrownBy(()->store.reserve(j.account,DEDICATED,j.row.resourceId(),j.row.operationKey(),j.row.fingerprint()))
            .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(j.reads).isEmpty();assertThat(j.writes).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"claim","ack","ack-in-transaction","unknown","bind","bind-in-transaction","lock-recovery","resume","plan","subscription-mutation"})
    void everyGenericMutationBoundaryRefusesPersistedDedicatedKind(String action){
        var j=new BoundaryJdbc();var store=new JdbcProviderOperationStore(j);var original=j.row;
        assertThatThrownBy(()->{
            switch(action){
                case "claim"->store.claim(original.id(),"next-owner",NOW.plusSeconds(120),Duration.ofSeconds(60));
                case "ack"->store.acknowledge(original.id(),original.leaseOwner(),original.version(),"pi_original");
                case "ack-in-transaction"->store.acknowledgeInTransaction(original.id(),original.leaseOwner(),original.version(),"pi_original");
                case "unknown"->store.unknown(original.id(),original.leaseOwner(),original.version());
                case "bind"->store.bindRecovered(original,"pi_original",original.fingerprint());
                case "bind-in-transaction"->store.bindRecoveredInTransaction(original,"pi_original",original.fingerprint());
                case "lock-recovery"->store.lockRecovery(original);
                case "resume"->store.lockResumeReceipt(original);
                case "plan"->store.lockPlanReceipt(original);
                default->store.lockSubscriptionMutationReceipt(original);
            }
        }).as(action).isInstanceOf(PaymentApplicationException.class);
        assertThat(j.writes).as(action).isEmpty();assertThat(j.row).isEqualTo(original);
        assertThat(j.reads).noneSatisfy(sql->assertThat(sql).contains("from payment_accounts"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void genericUnknownCannotAcquireADedicatedRowFromALaterLockingSnapshot(boolean initialMiss){
        var j=new LaterSnapshotJdbc(initialMiss);var o=j.boundary.row;
        assertThatThrownBy(()->new JdbcProviderOperationStore(j).unknown(o.id(),o.leaseOwner(),o.version()))
            .isInstanceOf(PaymentApplicationException.class);
        assertThat(j.writes).isEmpty();
        if(initialMiss)assertThat(j.lockingQueries).isEmpty();
        else assertThat(j.lockingQueries).singleElement().satisfies(sql->assertThat(sql).containsPattern("kind\\s*<>\\s*'OWNED_RECOVERY_INTENT_CONFIRM'"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void schedulerPollingAndExplicitIdExcludeDedicatedKindBeforeSelectionAndRowLock(boolean explicit){
        var j=new BoundaryJdbc();var scheduler=new PendingProviderRecoveryPersistenceAdapter(j,findOnly(j),Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(explicit?scheduler.claim(j.row.id()):scheduler.claimNext()).isEmpty();
        assertThat(j.writes).isEmpty();assertThat(j.schedulerSelections).isEqualTo(1);
        assertThat(j.schedulerRowReads).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"COMPLETE","FAILED","RETRY_PENDING","MANUAL_REVIEW","REPLAY_ACKNOWLEDGED","CATALOG_PUBLICATION_REQUIRED"})
    void schedulerFinishUsesPersistedKindWhenCallerForgesAnOrdinaryClaim(String outcome){
        var j=new BoundaryJdbc();var o=j.row;
        var forged=new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),Kind.PAYMENT_PREPARE,o.resourceId(),o.operationKey(),o.fingerprint(),o.status(),o.providerReference(),o.firstDispatchedAt(),o.retryBefore(),o.leaseOwner(),o.leaseExpiresAt(),o.version());
        var scheduler=new PendingProviderRecoveryPersistenceAdapter(j,findOnly(j),Clock.fixed(NOW,ZoneOffset.UTC));
        assertThatThrownBy(()->scheduler.finish(new Claim(forged,1,NOW.plusSeconds(120)),Outcome.valueOf(outcome)))
            .isInstanceOf(RuntimeException.class);
        assertThat(j.reads).anySatisfy(sql->assertThat(sql).contains("kind"));
        assertThat(j.writes).isEmpty();assertThat(j.row).isEqualTo(o);
    }

    @Test void ordinaryRecoveryProofRefusesBeforeAnyAccountOrCredentialResolution(){
        var j=new BoundaryJdbc();var touched=new ArrayList<String>();
        var accounts=(dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort)Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class},
            (proxy,method,args)->{touched.add(method.getName());throw new AssertionError("account must not be resolved");});
        var adapter=new StripeRecoveryProofAdapter(accounts,null,null,null,null,null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->adapter.request(j.row)).isInstanceOf(PaymentApplicationException.class);
        assertThat(touched).isEmpty();
    }

    @Test void schedulerClearCannotTouchDedicatedAcknowledgment(){
        var j=new BoundaryJdbc();var o=j.row;
        var ack=new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),o.kind(),o.resourceId(),o.operationKey(),o.fingerprint(),Status.ACKNOWLEDGED,"pi_original",o.firstDispatchedAt(),o.retryBefore(),null,null,2);
        new PendingProviderRecoveryPersistenceAdapter(j,findOnly(j),Clock.fixed(NOW,ZoneOffset.UTC)).clearResolved(ack);
        assertThat(j.reads).isEmpty();assertThat(j.writes).isEmpty();
    }

    private static ProviderOperationStore findOnly(BoundaryJdbc jdbc){
        return (ProviderOperationStore)Proxy.newProxyInstance(ProviderOperationStore.class.getClassLoader(),new Class<?>[]{ProviderOperationStore.class},(p,m,a)->{
            assertThat(m.getName()).isEqualTo("findById");assertThat(a[0]).isEqualTo(jdbc.row.id());return Optional.of(jdbc.row);
        });
    }

    /** Separate READ COMMITTED snapshots. A missing early guard reaches a real modeled UNKNOWN write. */
    private static final class LaterSnapshotJdbc extends JdbcTemplate {
        final BoundaryJdbc boundary=new BoundaryJdbc();final boolean initialMiss;final List<String> lockingQueries=new ArrayList<>(),writes=new ArrayList<>();
        LaterSnapshotJdbc(boolean initialMiss){this.initialMiss=initialMiss;}
        @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            if(sql.stripLeading().startsWith("update ")){writes.add(sql);return (List<T>)List.of(boundary.row);}
            if(sql.contains("for update")){
                lockingQueries.add(sql);
                boolean excluded=java.util.regex.Pattern.compile("kind\\s*<>\\s*'OWNED_RECOVERY_INTENT_CONFIRM'").matcher(sql).find();
                return (List<T>)(excluded?List.<ProviderOperation>of():List.of(boundary.row));
            }
            assertThat(sql).contains("select * from provider_operations where id=?");
            if(initialMiss)return List.of();var o=boundary.row;
            return (List<T>)List.of(new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),Kind.PAYMENT_PREPARE,o.resourceId(),o.operationKey(),o.fingerprint(),o.status(),o.providerReference(),o.firstDispatchedAt(),o.retryBefore(),o.leaseOwner(),o.leaseExpiresAt(),o.version()));
        }
    }

    /** Dedicated row is negative input only. Missing exclusions reach observable modeled mutation,
     * rather than an artificial exception that would accidentally satisfy a refusal assertion. */
    private static final class BoundaryJdbc extends JdbcTemplate {
        final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"inert",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_inert","no-key-alias");
        final ProviderOperation row=new ProviderOperation(UUID.randomUUID(),account.id(),account.externalAccountId(),account.credentialsRef(),DEDICATED,UUID.randomUUID(),"original:RECOVER:CONFIRM","original-fingerprint",Status.DISPATCHING,null,NOW.minusSeconds(5),NOW.plus(Duration.ofHours(22)),"original-owner",NOW.plusSeconds(55),1);
        final List<String> reads=new ArrayList<>(),writes=new ArrayList<>();int schedulerSelections,schedulerRowReads;
        @Override @SuppressWarnings("unchecked") public <T> List<T> queryForList(String sql,Class<T> type,Object... args){
            reads.add(sql);assertThat(type).isEqualTo(UUID.class);
            if(sql.contains("for update of o skip locked")){
                schedulerSelections++;
                boolean excluded=sql.contains("OWNED_RECOVERY_INTENT_CONFIRM") && (sql.contains("o.kind<>")||sql.contains("o.kind <>")||sql.contains("o.kind not in")||sql.contains("o.kind NOT IN"));
                return (List<T>)(excluded?List.<UUID>of():List.of(row.id()));
            }
            if(sql.contains("join stripe_billing_clock_intents"))return List.of();
            return (List<T>)List.of(row.resourceId());
        }
        @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            if(sql.stripLeading().startsWith("update ")||sql.stripLeading().startsWith("insert "))writes.add(sql);else reads.add(sql);
            return (List<T>)List.of(row);
        }
        @Override public int update(String sql,Object... args){writes.add(sql);return 1;}
        @Override public Map<String,Object> queryForMap(String sql,Object... args){
            reads.add(sql);schedulerRowReads++;
            return Map.of("kind",DEDICATED.name(),"recovery_attempts",1,"next_attempt_at",Timestamp.from(NOW.plusSeconds(120)),"lease_expires_at",Timestamp.from(NOW.minusSeconds(1)),"manual_review_required",false);
        }
    }
}
