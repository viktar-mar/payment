package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.*;
import dev.genericpayments.platform.support.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Inert result/provenance boundary only: handwritten rows never authenticate SQL or durable evidence. */
class OwnedFailedGraceBillingTimeTest {
    private static final Instant UTC=Instant.parse("2026-11-09T12:00:00Z");
    private static final long READY2=1702682000L, LAST_SECOND=1703283199L;
    private static UUID id(long value){return new UUID(0,value);}

    @Test void missingForeignClosedAutocommitAndWrongIsolationCallersRefuseBeforeAnySqlOrFallback(){
        for(var mode:List.of("missingEm","inactive","closedEm","foreignConnection","autoCommit","brokenConnection","repeatableRead")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary(mode);h.bind();
            try{
                var jdbc=new Rows(h);var selector=selector(h,jdbc);
                assertThatThrownBy(()->selector.sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
                assertThat(jdbc.calls).isZero();
            }finally{h.unbind();}
        }
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");var jdbc=new Rows(h);
        assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
        assertThat(jdbc.calls).isZero();
    }
    @Test void missingOwnDataSourceHolderRefusesWithAnOtherwiseKnownJpaCaller(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        var holder=TransactionSynchronizationManager.unbindResource(h.dataSource);
        try{var jdbc=new Rows(h);assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");assertThat(jdbc.calls).isZero();}
        finally{TransactionSynchronizationManager.bindResource(h.dataSource,holder);h.unbind();}
    }
    @Test void exactFullMappedSubscriptionReturnsStoredOriginalGraceTimeAndCallerDiagnostic(){
        withRows((h,jdbc)->{
            jdbc.results.add(List.of(candidate(true,LAST_SECOND)));var selector=selector(h,jdbc);
            assertThat(selector.sampleForRead(subscription(),UTC)).isEqualTo(new Sample(Instant.ofEpochSecond(LAST_SECOND),Mode.ISOLATED));
            assertThat(selector.sampledConnectionHolder).isSameAs(TransactionSynchronizationManager.getResource(h.dataSource));
            assertThat(selector.sampledTransaction).isEqualTo("inert-reader-xact");assertThat(jdbc.calls).isEqualTo(1);
        });
    }
    @Test void historicalReturnedReady2StillRequiresTheEntireCurrentSubscription(){
        withRows((h,jdbc)->{jdbc.results.add(List.of(candidate(true,READY2)));assertThat(selector(h,jdbc).sampleForRead(subscription(),UTC)).isEqualTo(new Sample(Instant.ofEpochSecond(READY2),Mode.ISOLATED));assertThat(jdbc.calls).isEqualTo(1);});
    }
    @Test void everyMappedFieldMismatchRefusesBothHistoricalAndGraceCandidatesWithoutFallback(){
        // Mutate the returned current record, while preserving a valid supplied record.
        var mutations=new LinkedHashMap<String,Object>();
        mutations.put("id",id(91));mutations.put("site_id",id(92));mutations.put("customer_id",id(93));
        mutations.put("payment_account_id",id(94));mutations.put("product_id",id(95));mutations.put("revision",99L);
        mutations.put("debt_billing_record_id",id(96));mutations.put("grace_deadline",Timestamp.from(Instant.ofEpochSecond(LAST_SECOND+2)));
        mutations.put("billing_amount_minor",2001L);mutations.put("billing_price_version",2);mutations.put("billing_price_key","changed-price");
        mutations.put("billing_interval","YEAR");mutations.put("billing_trial_days",3);mutations.put("currency","USD");
        mutations.put("entitled_amount_minor",2002L);mutations.put("provider_reference","sub_other");
        mutations.put("paid_through",Timestamp.from(Instant.ofEpochSecond(1702678401L)));
        mutations.put("current_period_end",Timestamp.from(Instant.ofEpochSecond(1705270401L)));
        mutations.put("debt_period_end",Timestamp.from(Instant.ofEpochSecond(1705270401L)));
        mutations.put("created_at",Timestamp.from(Instant.ofEpochSecond(1699999999L)));
        mutations.put("updated_at",Timestamp.from(Instant.ofEpochSecond(1702682001L)));
        mutations.put("last_reconciled_at",Timestamp.from(Instant.ofEpochSecond(1702682001L)));
        for(long frozen:new long[]{READY2,LAST_SECOND})for(var mutation:mutations.entrySet())withRows((h,jdbc)->{
            var row=candidate(true,frozen);row.put(mutation.getKey(),mutation.getValue());jdbc.results.add(List.of(row));
            assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).as(mutation.getKey()).hasMessage("OWNED_TIME_AUTHORITY_MISSING");assertThat(jdbc.calls).isEqualTo(1);
        });
    }
    @Test void incompleteFalseNullAndMissingCurrentCandidatesRefuseWithoutFallback(){
        for(var damage:List.of("false","null-valid","null-time","missing-subscription","malformed-mapping"))withRows((h,jdbc)->{
            var row=candidate(true,LAST_SECOND);
            switch(damage){case "false"->row.put("owned_valid",false);case "null-valid"->row.put("owned_valid",null);case "null-time"->row.put("owned_frozen_time",null);case "missing-subscription"->row.put("id",null);case "malformed-mapping"->row.put("billing_status","invalid");default->throw new AssertionError();}
            jdbc.results.add(List.of(row));assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_AUTHORITY_MISSING");assertThat(jdbc.calls).isEqualTo(1);
        });
    }
    @Test void ambiguityRetainsInvalidAsWellAsValidCandidates(){
        withRows((h,jdbc)->{jdbc.results.add(List.of(candidate(true,LAST_SECOND),candidate(false,LAST_SECOND)));assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_AUTHORITY_AMBIGUOUS");assertThat(jdbc.calls).isEqualTo(1);});
    }
    @Test void callerClosedOrReboundDuringQueryCannotReturnEvenValidTime(){
        for(var damage:List.of("closed","holder","manager"))withRows((h,jdbc)->{
            jdbc.results.add(List.of(candidate(true,LAST_SECOND)));
            jdbc.afterQuery=()->{switch(damage){case "closed"->h.closedAfterCapture=true;case "holder"->{TransactionSynchronizationManager.unbindResource(h.dataSource);TransactionSynchronizationManager.bindResource(h.dataSource,new ConnectionHolder(h.physical));}case "manager"->{TransactionSynchronizationManager.unbindResource(h.factory);TransactionSynchronizationManager.bindResource(h.factory,new org.springframework.orm.jpa.EntityManagerHolder(h.em));}default->throw new AssertionError();}};
            assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");assertThat(jdbc.calls).isEqualTo(1);
        });
    }
    @Test void zeroCandidatesUsesConcreteFallbackAndOrdinaryResultClearsPreviousTransactionDiagnostic(){
        withRows((h,jdbc)->{
            jdbc.results.add(List.of(candidate(true,LAST_SECOND)));for(int n=0;n<4;n++)jdbc.results.add(List.of());
            var selector=selector(h,jdbc);assertThat(selector.sampleForRead(subscription(),UTC).billingAt()).isEqualTo(Instant.ofEpochSecond(LAST_SECOND));
            assertThat(selector.sampleForRead(subscription(),UTC)).isEqualTo(new Sample(UTC,Mode.ORDINARY));
            assertThat(selector.sampledTransaction).isNull();assertThat(jdbc.calls).isEqualTo(5);
        });
    }
    @Test void zeroFailedCandidatesPreservesConcretePredecessorIsolatedTimeAndRefusal(){
        for(boolean valid:new boolean[]{true,false})withRows((h,jdbc)->{
            jdbc.results.add(List.of());jdbc.results.add(List.of(oldCandidate(valid)));var selector=selector(h,jdbc);
            if(valid)assertThat(selector.sampleForRead(subscription(),UTC)).isEqualTo(new Sample(Instant.ofEpochSecond(READY2),Mode.ISOLATED));
            else assertThatThrownBy(()->selector.sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
            assertThat(jdbc.calls).isEqualTo(2);
        });
    }
    @Test void aCallerLostAfterZeroCandidatesMustRefuseBeforeConcreteFallback(){
        withRows((h,jdbc)->{jdbc.results.add(List.of());jdbc.afterQuery=()->h.closedAfterCapture=true;
            assertThatThrownBy(()->selector(h,jdbc).sampleForRead(subscription(),UTC)).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");assertThat(jdbc.calls).isEqualTo(1);
        });
    }
    @Test void historicalWriterAndUnscopedEntryPointsDelegateTheConcreteOldSelector(){
        withRows((h,jdbc)->{
            jdbc.results.add(List.of(oldCandidate(true)));var selector=selector(h,jdbc);var s=subscription();
            assertThat(selector.sample(s.paymentAccountId(),s.siteId(),s.customerId(),UTC)).isEqualTo(new Sample(Instant.ofEpochSecond(READY2),Mode.ISOLATED));
            jdbc.counts.add(1L);assertThatThrownBy(()->selector.requireUnscopedAllowed(s.siteId(),s.customerId())).hasMessage("OWNED_TIME_UNSCOPED");assertThat(jdbc.calls).isEqualTo(2);
        });
    }
    private static OwnedFailedGraceBillingTime selector(OwnedFailedRenewalStoreTransactionTest.JpaBoundary h,Rows jdbc){
        return new OwnedFailedGraceBillingTime(jdbc,h.factory,new OwnedFailedRenewalBillingTime(jdbc,new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc))));
    }
    private interface Body{void run(OwnedFailedRenewalStoreTransactionTest.JpaBoundary h,Rows jdbc);}
    private static void withRows(Body body){var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();try{body.run(h,new Rows(h));}finally{h.unbind();}}
    private static Subscription subscription(){
        var product=new ProductId(id(4));var terms=new SubscriptionPriceTerms(new PriceId(id(5)),"monthly",1,product,Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,TaxMode.EXCLUSIVE);
        var period=new Subscription.Period(Instant.ofEpochSecond(1702678400L),Instant.ofEpochSecond(1705270400L));
        return new Subscription(id(1),new SiteId(id(2)),new CustomerId(id(3)),product,new PaymentAccountId(id(6)),terms,terms,Subscription.Status.PAST_DUE,"sub_owned",period,period.start(),null,null,null,new Subscription.Debt(id(7),period,Instant.ofEpochSecond(LAST_SECOND+1)),null,3,Instant.ofEpochSecond(1700000000L),Instant.ofEpochSecond(READY2),null);
    }
    private static Map<String,Object> candidate(boolean valid,long frozen){
        var row=new HashMap<String,Object>();row.put("owned_valid",valid);row.put("owned_frozen_time",frozen);row.put("owned_transaction_id","inert-reader-xact");
        row.put("id",id(1));row.put("site_id",id(2));row.put("customer_id",id(3));row.put("product_id",id(4));row.put("payment_account_id",id(6));row.put("currency","EUR");
        for(var prefix:List.of("billing","entitled")){
            row.put(prefix+"_price_id",id(5));row.put(prefix+"_price_key","monthly");row.put(prefix+"_price_version",1);row.put(prefix+"_amount_minor",1000L);row.put(prefix+"_interval","MONTH");row.put(prefix+"_trial_days",0);row.put(prefix+"_trial_policy","REQUIRED_BEFORE_TRIAL");row.put(prefix+"_tax_mode","EXCLUSIVE");
        }
        row.put("billing_status","PAST_DUE");row.put("provider_reference","sub_owned");
        for(var prefix:List.of("current_period","debt_period")){row.put(prefix+"_start",Timestamp.from(Instant.ofEpochSecond(1702678400L)));row.put(prefix+"_end",Timestamp.from(Instant.ofEpochSecond(1705270400L)));}
        row.put("paid_through",Timestamp.from(Instant.ofEpochSecond(1702678400L)));row.put("debt_billing_record_id",id(7));row.put("grace_deadline",Timestamp.from(Instant.ofEpochSecond(LAST_SECOND+1)));row.put("revision",3L);
        row.put("created_at",Timestamp.from(Instant.ofEpochSecond(1700000000L)));row.put("updated_at",Timestamp.from(Instant.ofEpochSecond(READY2)));return row;
    }
    private static Map<String,Object> oldCandidate(boolean valid){return Map.of("valid",valid,"frozen_time",READY2,"target",READY2,"transaction_id","inert-old-xact");}
    /** No connection acquisition, driver, SQL, context, threads, provider or database. */
    private static final class Rows extends JdbcTemplate{
        final Deque<List<Map<String,Object>>> results=new ArrayDeque<>();final Deque<Long> counts=new ArrayDeque<>();int calls;Runnable afterQuery=()->{};
        Rows(OwnedFailedRenewalStoreTransactionTest.JpaBoundary h){super(h.dataSource);}
        @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args){
            calls++;if(results.isEmpty())throw new AssertionError("Unexpected proof query/fallback");var mapped=new ArrayList<T>();
            for(var fields:results.removeFirst()){
                Object[] last={null};var rs=(ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
                    if(m.getName().equals("wasNull"))return last[0]==null;Object value=a==null?null:fields.get(a[0]);last[0]=value;
                    return switch(m.getName()){case "getString"->value==null?null:value.toString();case "getLong"->value==null?0L:((Number)value).longValue();case "getInt"->value==null?0:((Number)value).intValue();case "getBoolean"->Boolean.TRUE.equals(value);case "getObject","getTimestamp"->value;default->throw new AssertionError("Unexpected result access "+m.getName());};
                });try{mapped.add(mapper.mapRow(rs,mapped.size()));}catch(SQLException failure){throw new AssertionError(failure);}
            }afterQuery.run();return mapped;
        }
        @Override public <T>T queryForObject(String sql,Class<T> type,Object... args){calls++;if(counts.isEmpty())throw new AssertionError("Unexpected count query");return type.cast(counts.removeFirst());}
    }
}
