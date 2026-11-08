package dev.genericpayments.platform.support;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.Mode;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.Sample;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** Inert result-boundary tests only. Returned rows do not authenticate SQL or persisted authority.
 * The Spring thread-local flag is an inert precondition, never a real transaction or connection. */
class OwnedFailedRenewalBillingTimeTest {
    private final PaymentAccountId account=new PaymentAccountId(new UUID(0,1));
    private final SiteId site=new SiteId(new UUID(0,2));
    private final CustomerId customer=new CustomerId(new UUID(0,3));
    private final Instant utc=Instant.parse("2026-11-08T12:00:00Z");
    private boolean previousActive;
    private final List<DataSource> boundSources=new ArrayList<>();

    @BeforeEach void inertTransactionPrecondition() {
        previousActive=TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void restoreTransactionPrecondition() {
        try {
            for(var source:boundSources)TransactionSynchronizationManager.unbindResource(source);
        } finally {TransactionSynchronizationManager.setActualTransactionActive(previousActive);}
    }
    /** Inert caller-holder precondition only; never establishes database transaction authority. */
    private OwnedFailedRenewalBillingTime selector(MemoryJdbc jdbc) {
        if(!TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            TransactionSynchronizationManager.bindResource(jdbc.getDataSource(),new Object());
            boundSources.add(jdbc.getDataSource());
        }
        return new OwnedFailedRenewalBillingTime(jdbc,new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc)));
    }

    @Test void constructorRefusesMissingDependencies() {
        var jdbc=new MemoryJdbc();var original=new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc));
        assertThatThrownBy(()->new OwnedFailedRenewalBillingTime(null,original)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->new OwnedFailedRenewalBillingTime(jdbc,null)).isInstanceOf(NullPointerException.class);
    }
    @Test void absentActualTransactionRefusesBeforeJdbcOrFallback() {
        var jdbc=new MemoryJdbc();var time=selector(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(()->time.sample(account,site,customer,utc)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
        assertThat(jdbc.calls).isEmpty();
    }
    @Test void foreignDataSourceTransactionRefusesBeforeJdbcOrFallback() {
        var jdbc=new MemoryJdbc();var foreign=new MemoryJdbc();
        // Bypass the positive helper so this selector's own source remains unbound.
        var time=new OwnedFailedRenewalBillingTime(jdbc,new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc)));
        jdbc.results.add(List.of(row(true,1702682000L)));
        TransactionSynchronizationManager.bindResource(foreign.getDataSource(),new Object());
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(jdbc.getDataSource())).isNull();
            assertThatThrownBy(()->time.sample(account,site,customer,utc)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
            assertThat(jdbc.calls).isEmpty();
        } finally {TransactionSynchronizationManager.unbindResource(foreign.getDataSource());}
    }
    @Test void oneReturnedValidCandidateMapsStoredEpochAndCallerHolder() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of(row(true,1702682000L)));
        var holder=new Object();TransactionSynchronizationManager.bindResource(jdbc.getDataSource(),holder);
        try {
            var time=selector(jdbc);
            assertThat(time.sample(account,site,customer,utc)).isEqualTo(new Sample(Instant.ofEpochSecond(1702682000L),Mode.ISOLATED));
            assertThat(time.sampledConnectionHolder).isSameAs(holder);assertThat(time.sampledTransaction).isEqualTo("memory-xact");
            assertThat(jdbc.calls).hasSize(1);assertThat(jdbc.calls.getFirst()).containsExactly(account.value(),site.value(),customer.value());
        } finally {TransactionSynchronizationManager.unbindResource(jdbc.getDataSource());}
    }
    @Test void invalidReturnedCandidateCannotDisappearIntoFallback() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of(row(false,1702682000L)));
        assertThatThrownBy(()->selector(jdbc).sample(account,site,customer,utc)).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThat(jdbc.calls).hasSize(1);
    }
    @Test void multipleReturnedCandidatesRefuseAmbiguousOwnership() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of(row(true,1702682000L),row(true,1702682001L)));
        assertThatThrownBy(()->selector(jdbc).sample(account,site,customer,utc)).hasMessage("OWNED_TIME_AUTHORITY_AMBIGUOUS");
        assertThat(jdbc.calls).hasSize(1);
    }
    @Test void missingFailedCandidateUsesConcreteOriginalRenewalSelector() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of());jdbc.results.add(List.of(row(true,1702682000L)));
        var time=selector(jdbc);
        assertThat(time.sample(account,site,customer,utc)).isEqualTo(new Sample(Instant.ofEpochSecond(1702682000L),Mode.ISOLATED));
        assertThat(time.sampledTransaction).isEqualTo("memory-xact");assertThat(jdbc.calls).hasSize(2);
    }
    @Test void missingFailedCandidatePreservesOriginalRenewalRefusal() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of());jdbc.results.add(List.of(row(false,1702682000L)));
        assertThatThrownBy(()->selector(jdbc).sample(account,site,customer,utc)).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThat(jdbc.calls).hasSize(2);
    }
    @Test void completeAbsenceDelegatesBothConcreteSelectorsAndPreservesOrdinaryUtc() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of());jdbc.results.add(List.of());jdbc.results.add(List.of());
        assertThat(selector(jdbc).sample(account,site,customer,utc)).isEqualTo(new Sample(utc,Mode.ORDINARY));
        assertThat(jdbc.calls).hasSize(3);
    }
    @Test void ordinaryFallbackCannotReportThePreviousIsolatedFallbackTransaction() {
        var jdbc=new MemoryJdbc();jdbc.results.add(List.of());jdbc.results.add(List.of());jdbc.results.add(List.of(row(true,1702682000L)));
        jdbc.results.add(List.of());jdbc.results.add(List.of());jdbc.results.add(List.of());
        var time=selector(jdbc);
        assertThat(time.sample(account,site,customer,utc)).isEqualTo(new Sample(Instant.ofEpochSecond(1702682000L),Mode.ISOLATED));
        assertThat(time.sampledTransaction).isEqualTo("memory-xact");
        assertThat(time.sample(account,site,customer,utc)).isEqualTo(new Sample(utc,Mode.ORDINARY));
        assertThat(time.sampledTransaction).isNull();
        assertThat(jdbc.calls).hasSize(6); // Both samples traverse the bottom original selector; no extra diagnostic query is allowed.
    }
    @Test void matchingUnscopedCandidateRefusesBeforeUpstreamDelegation() {
        var jdbc=new MemoryJdbc();jdbc.counts.add(1L);
        assertThatThrownBy(()->selector(jdbc).requireUnscopedAllowed(site,customer)).hasMessage("OWNED_TIME_UNSCOPED");
        assertThat(jdbc.calls).hasSize(1);assertThat(jdbc.calls.getFirst()).containsExactly(site.value(),customer.value());
    }
    @Test void emptyUnscopedCandidateDelegatesBothConcreteSelectors() {
        var jdbc=new MemoryJdbc();jdbc.counts.add(0L);jdbc.counts.add(0L);jdbc.counts.add(0L);
        assertThatCode(()->selector(jdbc).requireUnscopedAllowed(null,null)).doesNotThrowAnyException();
        assertThat(jdbc.calls).hasSize(3).allSatisfy(args->assertThat(args).isEmpty());
    }

    private static Map<String,Object> row(boolean valid,long frozen) {
        return Map.of("valid",valid,"frozen_time",frozen,"target",frozen,"transaction_id","memory-xact");
    }
    /** Only the JDBC result boundary is replaced. No driver, DataSource connection, SQL execution,
     * Spring context, provider, executor or real transaction is constructed. */
    private static final class MemoryJdbc extends JdbcTemplate {
        final Deque<List<Map<String,Object>>> results=new ArrayDeque<>();
        final Deque<Long> counts=new ArrayDeque<>();
        final List<List<Object>> calls=new ArrayList<>();
        MemoryJdbc() {super(new AbstractDataSource() {
            @Override public Connection getConnection() {throw new AssertionError("No connection permitted");}
            @Override public Connection getConnection(String user,String password) {throw new AssertionError("No connection permitted");}
        });}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            calls.add(List.of(args));
            if(results.isEmpty())throw new AssertionError("Unexpected JDBC query or fallback");
            var values=results.removeFirst();var mapped=new ArrayList<T>();
            for(int n=0;n<values.size();n++) {
                var value=values.get(n);
                var result=(ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,arguments)->{
                    Object field=arguments==null?null:value.get(arguments[0]);
                    return switch(method.getName()) {
                        case "getString" -> (String)field;
                        case "getBoolean" -> Boolean.TRUE.equals(field);
                        case "getLong" -> field==null?0L:((Number)field).longValue();
                        case "wasNull" -> false;
                        default -> throw new AssertionError("Unsupported inert result access: "+method.getName());
                    };
                });
                try {mapped.add(mapper.mapRow(result,n));}catch(SQLException failure){throw new AssertionError(failure);}
            }
            return mapped;
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            calls.add(List.of(args));
            if(counts.isEmpty())throw new AssertionError("Unexpected JDBC count or fallback");
            return type.cast(counts.removeFirst());
        }
    }
}
