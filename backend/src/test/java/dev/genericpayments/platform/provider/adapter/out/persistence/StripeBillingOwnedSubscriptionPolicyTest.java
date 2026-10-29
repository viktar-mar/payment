package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;

/** Standalone: no parent, extensions, static initialization, DataSource or SQL execution. */
class StripeBillingOwnedSubscriptionPolicyTest {
    @Test void unknownOriginalOwnedSubscriptionCannotPostAgain() { refuses(ProviderOperation.Status.UNKNOWN); }
    @Test void abandonedOriginalOwnedSubscriptionCannotPostAgain() { refuses(ProviderOperation.Status.DISPATCHING); }
    @Test void genericAckRefusesOwnedSubscriptionBeforeAnyUpdate() { genericAckRefused(false); }
    @Test void participatingGenericAckRefusesOwnedSubscriptionBeforeAnyUpdate() { genericAckRefused(true); }
    private void genericAckRefused(boolean participating) {
        var db=new AckJdbc();var store=new JdbcProviderOperationStore(db);
        assertThatThrownBy(()->{
            if(participating)store.acknowledgeInTransaction(db.step.id(),"winner",1,"sub_owned");
            else store.acknowledge(db.step.id(),"winner",1,"sub_owned");
        }).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(db.updates).isZero();assertThat(db.receipt).isNull();
    }
    @Test void originalOwnedClaimCanStillBeMarkedUnknown() {
        var db=new AckJdbc();var result=new JdbcProviderOperationStore(db).unknown(db.step.id(),"winner",1);
        assertThat(result.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(db.updates).isEqualTo(1);assertThat(db.receipt).isNull();
    }
    @Test void dedicatedOwnedAckStillReturnsTheCompleteOriginalReceipt() {
        var db=new AckJdbc();
        var receipt=new JdbcProviderOperationStore(db).acknowledgeOwnedBillingSubscription(db.intent,db.step,"sub_owned",4000,"si_owned",900,1100,db.now);
        assertThat(receipt).isEqualTo(new OwnedClockSubscriptionReceipt(db.intent,db.step.id(),"sub_owned",4000,"si_owned",900,1100,db.now));
        assertThat(db.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(db.updates).isEqualTo(1);
    }
    /** Full saved lineage, no DataSource. All invoked JDBC entry points are inert and bounded. */
    static final class AckJdbc extends JdbcTemplate {
        final Instant now=Instant.parse("2026-10-29T10:00:00Z");
        final PaymentAccountId account=new PaymentAccountId(UUID.randomUUID());
        final ClockOwner owner=new ClockOwner(UUID.randomUUID(),UUID.randomUUID(),account,new SiteId(UUID.randomUUID()),new CustomerId(UUID.randomUUID()),
            "acct_test","alias","api",1000,"name","clock-key","a".repeat(64),now.minusSeconds(100));
        final ReadyClock ready=new ReadyClock(owner,UUID.randomUUID(),"clock_owned",1000,2000,3000,now.minusSeconds(90),"b".repeat(64));
        final UUID operation=UUID.randomUUID(),subscription=UUID.randomUUID();
        final OwnedClockSubscription intent=new OwnedClockSubscription(UUID.randomUUID(),ready,UUID.randomUUID(),UUID.randomUUID(),"cus_owned",subscription,operation,
            "subscription:"+account.value()+":"+operation+":CREATE","v1.paid."+"c".repeat(64),0,1,
            new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"monthly",1,new ProductId(UUID.randomUUID()),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE),
            "price_owned","prod_owned","d".repeat(64),now.minusSeconds(80));
        ProviderOperation step=new ProviderOperation(UUID.randomUUID(),account,"acct_test","alias",ProviderOperation.Kind.SUBSCRIPTION_CREATE,operation,intent.operationKey(),intent.fingerprint(),
            ProviderOperation.Status.DISPATCHING,null,now.minusSeconds(10),now.plusSeconds(82790),"winner",now.plusSeconds(50),1);
        OwnedClockSubscriptionReceipt receipt;int updates;
        @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            Object result;
            if(sql.contains("update provider_operations")) {
                updates++;var status=sql.contains("status=?")?ProviderOperation.Status.valueOf((String)args[0]):ProviderOperation.Status.ACKNOWLEDGED;
                String reference=status==ProviderOperation.Status.ACKNOWLEDGED?(String)args[sql.contains("status=?")?1:0]:null;
                step=new ProviderOperation(step.id(),account,"acct_test","alias",step.kind(),operation,step.operationKey(),step.fingerprint(),status,reference,step.firstDispatchedAt(),step.retryBefore(),null,null,2);
                result=step;
            }else if(sql.contains("from stripe_billing_owned_subscription_receipts")){return receipt==null?List.of():(List<T>)List.of(receipt);}
            else if(sql.contains("from stripe_billing_owned_subscriptions"))result=intent;
            else if(sql.contains("from stripe_billing_clock_intents"))result=owner;
            else if(sql.contains("from stripe_billing_clock_ready"))result=ready;
            else if(sql.contains("from provider_operations"))result=step;
            else throw new AssertionError("Unexpected query template");
            return (List<T>)List.of(result);
        }
        @Override @SuppressWarnings("unchecked") public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            return (List<T>)List.of(sql.startsWith("select subscription_id")?subscription:step.id());
        }
        @Override public int update(String sql,Object... args) {
            if(!sql.contains("insert into stripe_billing_owned_subscription_receipts"))throw new AssertionError("Unexpected write template");
            receipt=new OwnedClockSubscriptionReceipt(intent,(UUID)args[3],(String)args[4],(Long)args[5],(String)args[6],(Long)args[7],(Long)args[8],((java.sql.Timestamp)args[9]).toInstant());return 1;
        }
    }
    private void refuses(ProviderOperation.Status status) {
        Instant now=Instant.parse("2026-10-29T10:00:00Z");
        var step=new ProviderOperation(UUID.randomUUID(),new PaymentAccountId(UUID.randomUUID()),"acct_test","alias",
            ProviderOperation.Kind.SUBSCRIPTION_CREATE,UUID.randomUUID(),"original-key","fingerprint",status,null,
            now.minusSeconds(120),now.plusSeconds(3600),null,null,1);
        var db=new JdbcTemplate() {
            @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                return (List<T>)List.of(step);
            }
            @Override @SuppressWarnings("unchecked") public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                // Existing ordinary lifecycle fixture; the new persisted intent marks its one-use authority.
                if(sql.contains("join stripe_billing_clock_intents"))return List.of();
                return (List<T>)List.of(step.id());
            }
        };
        assertThat(new JdbcProviderOperationStore(db).claim(step.id(),"second",now,Duration.ofSeconds(60))).isEmpty();
    }
}
