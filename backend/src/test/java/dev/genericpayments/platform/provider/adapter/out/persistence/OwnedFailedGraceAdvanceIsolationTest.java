package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.payments.adapter.out.persistence.PendingProviderRecoveryPersistenceAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRecoveryProofAdapter;
import dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Shares scripted negative rows with the existing confirmation isolation test; no connection. */
class OwnedFailedGraceAdvanceIsolationTest {
    private static final Instant NOW=Instant.parse("2026-11-08T12:00:00Z");
    @Test void ordinaryReserveAndAllGenericMutationBoundariesRefuseGraceBeforeAccountWork(){
        for(var action:List.of("reserve","claim","ack","ackTx","unknown","bind","bindTx","recovery","resume","plan","mutation")){
            var j=new OwnedRecoveryConfirmIsolationTest.BoundaryJdbc(Kind.OWNED_GRACE_CLOCK_ADVANCE);var s=new JdbcProviderOperationStore(j);var o=j.row;
            assertThatThrownBy(()->{switch(action){
                case "reserve"->s.reserve(j.account,o.kind(),o.resourceId(),o.operationKey(),o.fingerprint());
                case "claim"->s.claim(o.id(),"foreign",NOW,Duration.ofSeconds(60));
                case "ack"->s.acknowledge(o.id(),o.leaseOwner(),o.version(),"clock_inert");case "ackTx"->s.acknowledgeInTransaction(o.id(),o.leaseOwner(),o.version(),"clock_inert");
                case "unknown"->s.unknown(o.id(),o.leaseOwner(),o.version());case "bind"->s.bindRecovered(o,"clock_inert",o.fingerprint());case "bindTx"->s.bindRecoveredInTransaction(o,"clock_inert",o.fingerprint());
                case "recovery"->s.lockRecovery(o);case "resume"->s.lockResumeReceipt(o);case "plan"->s.lockPlanReceipt(o);default->s.lockSubscriptionMutationReceipt(o);
            }}).as(action).isInstanceOf(PaymentApplicationException.class);
            assertThat(j.writes).isEmpty();assertThat(j.reads).noneSatisfy(sql->assertThat(sql).contains("from payment_accounts"));if(action.equals("reserve"))assertThat(j.reads).isEmpty();
        }
    }
    @Test void schedulerSelectionFinishAndClearCannotConsumeGraceEvenWithForgedCallerKind(){
        for(boolean explicit:List.of(false,true)){
            var j=new OwnedRecoveryConfirmIsolationTest.BoundaryJdbc(Kind.OWNED_GRACE_CLOCK_ADVANCE);var scheduler=new PendingProviderRecoveryPersistenceAdapter(j,OwnedRecoveryConfirmIsolationTest.findOnly(j),Clock.fixed(NOW,ZoneOffset.UTC));
            assertThat(explicit?scheduler.claim(j.row.id()):scheduler.claimNext()).isEmpty();assertThat(j.writes).isEmpty();assertThat(j.schedulerRowReads).isZero();
        }
        for(var outcome:Outcome.values()){
            var j=new OwnedRecoveryConfirmIsolationTest.BoundaryJdbc(Kind.OWNED_GRACE_CLOCK_ADVANCE);var o=j.row;
            var forged=new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),Kind.PAYMENT_PREPARE,o.resourceId(),o.operationKey(),o.fingerprint(),o.status(),null,o.firstDispatchedAt(),o.retryBefore(),o.leaseOwner(),o.leaseExpiresAt(),o.version());
            var scheduler=new PendingProviderRecoveryPersistenceAdapter(j,OwnedRecoveryConfirmIsolationTest.findOnly(j),Clock.fixed(NOW,ZoneOffset.UTC));
            assertThatThrownBy(()->scheduler.finish(new Claim(forged,1,NOW.plusSeconds(120)),outcome)).as(outcome.name()).isInstanceOf(RuntimeException.class);assertThat(j.writes).isEmpty();
            j.reads.clear();scheduler.clearResolved(o);assertThat(j.reads).isEmpty();assertThat(j.writes).isEmpty();
        }
    }
    @Test void ordinaryRecoveryProofRefusesGraceWithoutLoadingAccountCredentialsOrClient(){
        var j=new OwnedRecoveryConfirmIsolationTest.BoundaryJdbc(Kind.OWNED_GRACE_CLOCK_ADVANCE);var touched=new ArrayList<String>();
        var accounts=(dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class},(p,m,a)->{touched.add(m.getName());throw new AssertionError("account touched");});
        var adapter=new StripeRecoveryProofAdapter(accounts,null,null,null,null,null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->adapter.request(j.row)).isInstanceOf(PaymentApplicationException.class);assertThat(touched).isEmpty();
    }
}
