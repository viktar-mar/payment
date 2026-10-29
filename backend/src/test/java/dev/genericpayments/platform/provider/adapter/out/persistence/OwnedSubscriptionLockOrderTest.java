package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.shared.adapter.out.security.Sha256DigestAdapter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;

/** Pure call-order tests. No DataSource, Connection, SQL execution, Spring context or Mockito.
 * Reverting any owned boundary to Account-first must fail before its authority checks. */
class OwnedSubscriptionLockOrderTest {
    @ParameterizedTest @ValueSource(strings={"admit","current","receipt","reserve","claim","ack"})
    void ownedBoundariesAcquireLifecycleOwnersBeforeAccount(String entry) {
        var jdbc=new RecordingJdbc(false);
        assertThatThrownBy(()->invoke(entry,jdbc)).isSameAs(jdbc.complete);
        assertThat(jdbc.locks).containsExactly("sites","customers","payment_accounts");
    }

    @ParameterizedTest @ValueSource(strings={"admit","current","receipt","reserve","claim","ack"})
    void rejectedSiteCannotLeaveAnAccountLockHeld(String entry) {
        var jdbc=new RecordingJdbc(true);
        assertThatThrownBy(()->invoke(entry,jdbc)).isInstanceOf(PaymentApplicationException.class);
        assertThat(jdbc.locks).containsExactly("sites");
    }

    private void invoke(String entry,RecordingJdbc jdbc) {
        var f=jdbc.f;var owned=new JdbcStripeBillingClockSubscriptionStore(jdbc,new Sha256DigestAdapter());
        var steps=new JdbcProviderOperationStore(jdbc);
        switch(entry) {
            case "admit"->owned.admitFresh(f.f.account,f.f.owner,f.subscriptionId,f.operationId,f.utc);
            case "current"->owned.requireCurrent(f.intent);
            case "receipt"->owned.receipt(f.intent);
            case "reserve"->steps.reserveOwnedBillingSubscription(f.f.account,f.intent);
            case "claim"->steps.claim(jdbc.claim.id(),"next",f.utc.plusSeconds(61),Duration.ofSeconds(60));
            case "ack"->steps.acknowledgeOwnedBillingSubscription(f.intent,jdbc.claim,"sub_owned",42,"si_owned",1699999900,1702678400,f.utc.plusSeconds(1));
            default->throw new AssertionError(entry);
        }
    }

    static final class RecordingJdbc extends JdbcTemplate {
        final StripeBillingOwnedSubscriptionFixture f=new StripeBillingOwnedSubscriptionFixture();
        final List<String> locks=new ArrayList<>();
        final RuntimeException complete=new IllegalStateException("owner locks observed");
        final boolean rejectSite;
        final ProviderOperation claim=new ProviderOperation(new UUID(0,15),f.f.account.id(),f.f.account.externalAccountId(),f.f.account.credentialsRef(),
            ProviderOperation.Kind.SUBSCRIPTION_CREATE,f.operationId,f.intent.operationKey(),f.intent.fingerprint(),ProviderOperation.Status.DISPATCHING,
            null,f.utc,f.utc.plusSeconds(82800),"original",f.utc.plusSeconds(60),1);
        RecordingJdbc(boolean rejectSite){this.rejectSite=rejectSite;}
        @Override @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            for(String table:List.of("sites","customers","payment_accounts"))if(sql.contains("from "+table)&&sql.contains("for share")) {
                locks.add(table);
                if(table.equals("sites")&&rejectSite)return List.of();
                if(locks.size()==3)throw complete;
                return (List<T>)List.of(new UUID(0,1));
            }
            if(sql.startsWith("select subscription_id from stripe_billing_owned_subscriptions"))return (List<T>)List.of(f.subscriptionId);
            throw new AssertionError("Unexpected JDBC call: "+sql);
        }
        @Override @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            if(sql.equals("select * from provider_operations where id=? for update"))return (List<T>)List.of(claim);
            if(sql.equals("select * from stripe_billing_owned_subscriptions where payment_account_id=? and subscription_id=?"))return (List<T>)List.of(f.intent);
            throw new AssertionError("Unexpected JDBC call: "+sql);
        }
    }
}
