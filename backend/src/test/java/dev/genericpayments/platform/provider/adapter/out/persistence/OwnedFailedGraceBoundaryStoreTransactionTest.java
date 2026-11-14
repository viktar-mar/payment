package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.Phase;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.lang.reflect.Proxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceBoundaryInertFixture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedFailedGraceBoundaryStoreTransactionTest {
    @Test void registeredCompletionRechecksTheOriginalCallerAroundFlushAndRollsBackOnMissingProof(){
        var f=new OwnedFailedGraceBoundaryInertFixture();var b=f.base.newBudget();var c=f.inspect(Phase.AT,b);
        var claim=f.service.claim(c,"inert-owner",b);
        for(var outcome:List.of("pass","missing","ambiguous","closed-before","foreign-after-flush","read-only")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();TransactionSynchronizationManager.initSynchronization();
            var events=new java.util.ArrayList<String>();
            try{
                var script=new JdbcTemplate(h.dataSource){
                    @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
                        if(args.length==2){assertThat((long)args[0]).isBetween(1L,30000L);assertThat(args[1]).isEqualTo(false);events.add("limit");return List.of();}
                        assertThat(args).containsExactly(c.original().preparation().id(),claim.id(),b.invocation(),Timestamp.from(b.enteredAt()),
                            Timestamp.from(b.deadlineAt()),"claim");events.add("proof");
                        return outcome.equals("missing")?List.of():outcome.equals("ambiguous")?List.of(Map.of(),Map.of()):List.of(Map.of());
                    }
                };
                var store=new JdbcOwnedFailedGraceBoundaryStore(script,h.factory);
                var captured=OwnedGraceCallerConnection.capture(script,h.factory,"lost caller");
                store.completePositive(captured,c,claim.id(),b,claim,"claim");
                h.afterFlush=()->{events.add("flush");if(outcome.equals("foreign-after-flush")){
                    TransactionSynchronizationManager.unbindResource(h.dataSource);
                    TransactionSynchronizationManager.bindResource(h.dataSource,new ConnectionHolder(new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid").physical));
                }};
                var callbacks=TransactionSynchronizationManager.getSynchronizations();assertThat(callbacks).hasSize(1);
                h.closedAfterCapture=outcome.equals("closed-before");boolean[] committed={false};
                Runnable commit=()->{callbacks.getFirst().beforeCommit(outcome.equals("read-only"));committed[0]=true;};
                if(outcome.equals("pass"))commit.run();else assertThatThrownBy(commit::run).isInstanceOf(IllegalStateException.class);
                assertThat(committed[0]).isEqualTo(outcome.equals("pass"));
                if(outcome.equals("closed-before")||outcome.equals("read-only"))assertThat(events).isEmpty();
                else if(outcome.equals("foreign-after-flush"))assertThat(events).containsExactly("limit","flush");
                else assertThat(events).containsExactly("limit","flush","limit","proof");
            }finally{TransactionSynchronizationManager.clearSynchronization();h.unbind();}
        }
    }
    @Test void committedEdgeMapsOnlyOneFullyAuthenticatedRowWithExactPreparationAndPhaseBindings(){
        var fixture=new OwnedFailedGraceBoundaryInertFixture();
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        try{
            for(var phase:Phase.values()){
                if(phase==Phase.AFTER)fixture.complete(Phase.AT);
                var c=fixture.inspect(phase,fixture.base.newBudget());var p=c.predecessor();var obs=p.observation();
                var fields=new HashMap<String,Object>();
                fields.put("original_id",c.originalGraceReadyId());fields.put("previous_id",p.readyId());fields.put("invocation",p.invocation());
                fields.put("operation_id",p.operationId());fields.put("operation_fingerprint",p.operationFingerprint());fields.put("observed_at",Timestamp.from(p.observedAt()));
                fields.put("reference",obs.reference());fields.put("name",obs.name());fields.put("object_type",obs.object());fields.put("livemode",false);
                fields.put("frozen_time",obs.frozenTime());fields.put("provider_created",obs.created());fields.put("provider_deletes_after",obs.deletesAfter());
                fields.put("status","ready");fields.put("advancing_target",obs.advancingTarget());fields.put("target",c.target());
                fields.put("operation_key",c.operationKey());fields.put("fingerprint",c.fingerprint());
                for(var scenario:List.of("exact","missing","ambiguous","foreign-target","foreign-origin")){
                    var output=new HashMap<>(fields);if(scenario.equals("foreign-target"))output.put("target",c.target()+1);
                    if(scenario.equals("foreign-origin"))output.put("original_id",java.util.UUID.randomUUID());
                    var count=scenario.equals("missing")?0:scenario.equals("ambiguous")?2:1;
                    var jdbc=new JdbcTemplate(h.dataSource){
                        @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args){
                            assertThat(args).containsExactly(c.original().preparation().id(),phase==Phase.AT?2:3,true,c.original().preparation().id(),phase==Phase.AT?2:3);
                            var rows=new java.util.ArrayList<T>();for(int i=0;i<count;i++){
                                ResultSet rs=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,m,a)->{
                                    if(!List.of("getObject","getString","getLong","getBoolean","getTimestamp").contains(m.getName())||!output.containsKey(a[0]))throw new AssertionError("unexpected result column");
                                    return output.get(a[0]);
                                });
                                try{rows.add(mapper.mapRow(rs,i));}catch(SQLException ex){throw new AssertionError(ex);}
                            }return rows;
                        }
                    };
                    var store=new JdbcOwnedFailedGraceBoundaryStore(jdbc,h.factory);
                    if(scenario.equals("exact"))assertThat(store.committedEdge(c.original(),phase)).isEqualTo(c);
                    else assertThatThrownBy(()->store.committedEdge(c.original(),phase)).as(scenario).isInstanceOf(RuntimeException.class);
                }
            }
        }finally{h.unbind();}
    }
    @Test void rejectsUnusablePhysicalCallerForEveryPhaseEntryBeforeSql(){
        var now=Instant.parse("2026-11-14T12:00:00Z");
        var budget=new InvocationBudget(Clock.fixed(now,ZoneOffset.UTC),()->0);
        for(var mode:List.of("missingEm","inactive","closedEm","foreignConnection","autoCommit","brokenConnection","repeatableRead")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary(mode);h.bind();
            try{
                var store=new JdbcOwnedFailedGraceBoundaryStore(h.jdbc,h.factory);
                for(Runnable call:List.<Runnable>of(()->store.beforeAuthorityInTransaction(budget),
                    ()->store.beforeFreshEntryInTransaction(null,null,Phase.AT,budget),
                    ()->store.requirePredecessorInTransaction(null,Phase.AFTER,budget),
                    ()->store.firstClaimInTransaction(null,"owner",now,budget),
                    ()->store.requireClaimInTransaction(null,null,now,budget),
                    ()->store.acknowledgeInTransaction(null,null,null,now,now,budget),
                    ()->store.requireAcknowledgedInTransaction(null,null,now,budget),
                    ()->store.recordReadyInTransaction(null,null,null,now,now,budget),
                    ()->store.uncertainInTransaction(null,null,null,budget,now)))
                    assertThatThrownBy(call::run).as(mode).isInstanceOf(IllegalStateException.class);
                assertThat(h.queries).hasValue(0);
            }finally{h.unbind();}
        }
    }
    @Test void knownBoundReadCommittedCallerReachesLimiterWithoutPoolCheckout(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        try{
            var store=new JdbcOwnedFailedGraceBoundaryStore(h.jdbc,h.factory);
            var budget=new InvocationBudget(Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"),ZoneOffset.UTC),()->0);
            assertThatThrownBy(()->store.beforeAuthorityInTransaction(budget)).isInstanceOf(AssertionError.class).hasMessage("INERT_PROOF_BOUNDARY");
            assertThat(h.queries).hasValue(1);
        }finally{h.unbind();}
    }
}
