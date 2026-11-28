package dev.genericpayments.platform.provider.adapter.out.persistence;

import java.util.List;
import java.time.Instant;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.provider.application.service.OwnedRecoveryInertFixture;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit socket-free test: no DataSource, context or provider calls. */
class OwnedFailedRenewalMethodStoreTransactionTest {
    @Test void recoveredFactSnapshotUsesOnlyTheExactTaggedClockForFutureCash(){
        var fixture=new OwnedRecoveryInertFixture(value->"digest:"+value,true);
        var r=fixture.reservation();var original=r.admission().firstRenewalAdmission();var receipt=original.receipt();
        var terms=receipt.intent().terms();var period=r.failure().period();var key=fixture.inspect().recoveryOperation().providerOperationKey();
        var exact=Instant.ofEpochSecond(r.remoteClock().frozenTime());var observed=exact.minusSeconds(30);
        var firstPeriod=new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd()));
        var initial=new Invoice(original.invoice(),BillingRecord.Kind.INITIAL,terms,null,firstPeriod,terms.regularAmount().minorUnits(),terms.regularAmount().currency(),
            SettlementKind.CASH,List.of(new CashObject(original.paymentIntent(),terms.regularAmount().minorUnits(),terms.regularAmount().currency(),PaymentStatus.SUCCEEDED,observed.minusSeconds(1))));
        for(var paidAt:List.of(exact,exact.minusSeconds(1),exact.plusSeconds(1))){
            var renewal=new Invoice(r.failure().invoice(),BillingRecord.Kind.RENEWAL,terms,null,period,terms.regularAmount().minorUnits(),terms.regularAmount().currency(),
                SettlementKind.CASH,List.of(new CashObject(r.failure().paymentIntent(),terms.regularAmount().minorUnits(),terms.regularAmount().currency(),PaymentStatus.SUCCEEDED,paidAt)));
            var snapshot=new Snapshot(receipt.reference(),Subscription.Status.ACTIVE,terms,period,null,null,List.of(initial,renewal),key,false,0,observed,
                new OperationObservation(key,OperationState.APPLIED));
            Runnable tagged=()->JdbcOwnedFailedRenewalStore.requireSnapshot(r.admission(),snapshot,true,r.failure().paymentIntent(),key,r);
            if(paidAt.equals(exact))assertThatCode(tagged::run).doesNotThrowAnyException();
            else assertThatThrownBy(tagged::run).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->JdbcOwnedFailedRenewalStore.requireSnapshot(r.admission(),snapshot,true,r.failure().paymentIntent(),key))
                .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void factReceiptFailureMarksOnlyTheOriginalJpaTransactionEvenAfterCallerRebind(){
        for(String mode:List.of("present","missing","foreign-before","foreign-after")){
            var original=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");
            var foreign=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");original.bind();
            try{
                var caller=OwnedGraceCallerConnection.capture(original.jdbc,original.factory,"original fact caller unavailable");
                var proofCalls=new java.util.concurrent.atomic.AtomicInteger();
                Runnable proof=()->{
                    proofCalls.incrementAndGet();
                    if(mode.equals("missing"))throw new IllegalStateException("missing phase receipt");
                    if(mode.equals("foreign-after")){original.unbind();foreign.bind();}
                };
                if(mode.equals("foreign-before")){original.unbind();foreign.bind();}
                if(mode.equals("present"))caller.requireFactReceiptOrRollback(proof);
                else assertThatThrownBy(()->caller.requireFactReceiptOrRollback(proof))
                    .hasMessage(mode.equals("missing")?"missing phase receipt":"original fact caller unavailable");
                assertThat(proofCalls).hasValue(mode.equals("foreign-before")?0:1);
                assertThat(original.rollbackOnly).isEqualTo(!mode.equals("present"));assertThat(foreign.rollbackOnly).isFalse();
                assertThat(original.queries).hasValue(0);assertThat(foreign.queries).hasValue(0);
            }finally{
                if(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(foreign.factory))foreign.unbind();
                else original.unbind();
            }
        }
    }
    @Test void taggedFactScopeRequiresPrecommitSynchronizationBeforeAnyProofLookup(){
        var fixture=new dev.genericpayments.platform.provider.application.service.OwnedRecoveryInertFixture(value->"digest:"+value,true);
        var r=fixture.reservation();var owner=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner.create();
        // Only a description is supplied. This caller cannot register the required commit proof,
        // so looking up the description or attempting to authenticate an owner is already too late.
        var claim=new dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.ReadClaim(
            java.util.UUID.randomUUID(),r.id(),dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Phase.METHOD,
            java.util.UUID.randomUUID(),fixture.now(),fixture.now().plusSeconds(30),
            new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence(
                r.failure().revision(),r.setupOperationId(),r.initialOperationVersion()+1,null,null,r.failure().observationSequence()),r.billingSample());
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        try{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            var store=new JdbcOwnedFailedRenewalMethodStore(h.jdbc,h.factory);
            assertThatThrownBy(()->store.withSuccessorFactInTransaction(r,claim,owner,()->{
                throw new AssertionError("fact effect entered without a commit check");
            })).hasMessage("Owned method transition authority unavailable");
            assertThat(h.queries).hasValue(0);
        }finally{h.unbind();}
    }
    @Test void typedTerminalPortsFailClosedBeforeAnySqlOrCallerWork(){
        var type=dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class;
        var older=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)
            java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{
                if(!m.isDefault())throw new AssertionError("legacy fallback");return java.lang.reflect.InvocationHandler.invokeDefault(proxy,m,args);
            });
        var unconfigured=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        var owner=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner.create();
        var reason=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Reason.UNCERTAIN;
        for(var store:List.of(older,unconfigured))
            for(Runnable call:List.<Runnable>of(()->store.terminateSuccessorWriteInTransaction(null,null,owner),
                    ()->store.terminateSuccessorReadInTransaction(null,null,reason,owner),
                    ()->store.terminateSuccessorConfirmationInTransaction(null,null,null,owner)))
                assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
    }
    @Test void capturedFactCallerAuthenticatesConfigurationAndStillRejectsReboundPhysicalHolder(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");
        var foreign=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        try{
            var caller=OwnedGraceCallerConnection.capture(h.jdbc,h.factory,"wrong fact caller");
            assertThat(caller.requireReadCommitted(h.jdbc,h.factory)).isSameAs(h.em);
            assertThatThrownBy(()->caller.requireReadCommitted(foreign.jdbc,h.factory)).hasMessage("wrong fact caller");
            assertThatThrownBy(()->caller.requireReadCommitted(h.jdbc,foreign.factory)).hasMessage("wrong fact caller");
            assertThatThrownBy(()->caller.requireReadCommitted(h.jdbc,null)).hasMessage("wrong fact caller");
            var holder=org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(h.dataSource);
            try{
                org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(h.dataSource,new org.springframework.jdbc.datasource.ConnectionHolder(h.physical));
                assertThatThrownBy(()->caller.requireReadCommitted(h.jdbc,h.factory)).hasMessage("wrong fact caller");
            }finally{
                org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(h.dataSource);
                org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(h.dataSource,holder);
            }
            assertThat(h.queries).hasValue(0);assertThat(foreign.queries).hasValue(0);
        }finally{h.unbind();}
    }
    @Test void olderPortsCannotApplySuccessorFactsOrConsumeTaggedReadReceipts() {
        var type=dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class;
        var older=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)
            java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{
                if(!m.isDefault())throw new AssertionError("legacy fallback");return java.lang.reflect.InvocationHandler.invokeDefault(proxy,m,args);
            });
        var owner=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner.create();
        for(Runnable call:List.<Runnable>of(()->older.claimReadInTransaction(null,null,null,null,null,null,owner),
            ()->older.requireReadInTransaction(null,null,owner),()->older.requireMethodContextInTransaction(null,null,owner),
            ()->older.requireRecoveredContextInTransaction(null,null,owner),()->older.recordCompletionInTransaction(null,null,null,null,owner),
            ()->older.recordPublicationInTransaction(null,null,null,owner),
            ()->older.withSuccessorFactInTransaction(null,null,owner,()->{throw new AssertionError("unauthorized callback");})))
            assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
    }
    @Test void configuredHandoffEntriesRejectForeignFactoryPhysicalMismatchAndNonReadCommittedBeforeSql() {
        for(String mode:List.of("foreignFactory","foreignConnection","repeatableRead")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary(mode);h.bind();
            try{
                var factory=mode.equals("foreignFactory")?new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid").factory:h.factory;
                var store=new JdbcOwnedFailedRenewalMethodStore(h.jdbc,factory);
                for(var call:List.<Runnable>of(()->store.requireGraceSuccessorEntryInTransaction(null,null,null),
                        ()->store.reserveBrowserAfterGraceInTransaction(null,null,null,null,null,null,null,null)))
                    assertThatThrownBy(call::run).as(mode).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Owned grace successor requires original caller");
                assertThat(h.queries).hasValue(0);
            }finally{h.unbind();}
        }
    }
    @Test void successorDefaultsAndUnconfiguredJdbcDenyBeforeLookingUpAnySource() {
        var port=dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class;
        var defaults=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)
            java.lang.reflect.Proxy.newProxyInstance(port.getClassLoader(),new Class<?>[]{port},(proxy,method,args)->{
                if(!method.isDefault())throw new AssertionError("unexpected fallback to "+method.getName());
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
            });
        var unconfigured=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        var tx=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        try{
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            for(var store:List.of(defaults,unconfigured)){
                assertThatThrownBy(()->store.requireGraceSuccessorEntryInTransaction(null,null,null)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(()->store.reserveBrowserAfterGraceInTransaction(null,null,null,null,null,null,null,null)).isInstanceOf(IllegalStateException.class);
            }
        }finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(tx);}
    }
    @Test void everyStoreEntryRequiresTheCallerTransactionBeforeAnySql() {
        var s=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        List<Runnable> calls=List.of(
            ()->s.reserveInTransaction(null,null,null,null,null,null),()->s.requireReservationInTransaction(null),
            ()->s.requirePreparedMethodInTransaction(null),
            ()->s.claimReadInTransaction(null,null,null,null,null,null),()->s.requireReadInTransaction(null,null),
            ()->s.requireMethodContextInTransaction(null,null),()->s.recordCompletionInTransaction(null,null,null,null),
            ()->s.requireCompletionInTransaction(null,null),()->s.associateRecoveryInTransaction(null,null,null,null),
            ()->s.requireRecoveryInTransaction(null,null),()->s.requireRecoveredContextInTransaction(null,null),
            ()->s.requireBrowserRecoveryEnrollmentInTransaction(null,null,null),
            ()->s.requireBrowserRecoveryIdentityInTransaction(null,null),
            ()->s.recordPublicationInTransaction(null,null,null),()->s.publicationInTransaction(null),
            ()->s.requireMethodFactInTransaction(null,null,null,null,null),()->s.requireRecoveryFactInTransaction(null,null,null,null,null));
        for(var call:calls)assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class)
            .hasMessage("Owned method transition requires caller transaction");
    }

}
