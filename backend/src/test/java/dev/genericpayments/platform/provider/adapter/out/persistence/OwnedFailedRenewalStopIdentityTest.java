package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Pure descriptor-mapping boundary tests: no DataSource, SQL execution, Spring context or mock engine. */
class OwnedFailedRenewalStopIdentityTest {
    private final Instant now=Instant.parse("2026-11-01T12:00:00Z");

    @Test void terminalDescriptorReloadDoesNotConsultMutableProviderStatus() {
        var expected=admission();
        // This storage boundary provides only the immutable failed-admission descriptor. Any
        // attempt to re-enter live provider/paid/lineage SQL is rejected, including the old loader.
        var jdbc=new DescriptorRows(descriptor(expected));
        inTransaction(()->assertThat(new JdbcOwnedFailedRenewalStore(jdbc).storedIdentityForStop(expected)).isEqualTo(expected));
    }

    @Test void descriptiveStopReloadStillRejectsForeignOwnerAndPredecessorMetadata() {
        var expected=admission();
        for(var field:List.of("first_renewal_admission","payment_account_id","site_id","customer_id","subscription_id","ready_id","boundary_id")) {
            var row=descriptor(expected);row.put(field,id(999));
            inTransaction(()->assertThatThrownBy(()->new JdbcOwnedFailedRenewalStore(new DescriptorRows(row)).storedIdentityForStop(expected))
                .as(field).isInstanceOf(IllegalStateException.class));
        }
    }

    @Test void changedSourceAndAdmittedTimestampCannotAuthenticateTheExpectedDescriptor() {
        var expected=admission();var source=descriptor(expected);source.put("source_sha","f".repeat(40));
        inTransaction(()->assertThatThrownBy(()->new JdbcOwnedFailedRenewalStore(new DescriptorRows(source)).storedIdentityForStop(expected))
            .isInstanceOf(IllegalArgumentException.class));
        var admitted=descriptor(expected);admitted.put("admitted_at",Timestamp.from(now.plusSeconds(1)));
        inTransaction(()->assertThat(new JdbcOwnedFailedRenewalStore(new DescriptorRows(admitted)).storedIdentityForStop(expected)).isNotEqualTo(expected));
    }

    private static void inTransaction(Runnable action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { action.run(); } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    private static final class DescriptorRows extends JdbcTemplate {
        private final Map<String,Object> row;
        DescriptorRows(Map<String,Object> row) { this.row=row; }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args) {
            if(!sql.equals("select * from stripe_billing_failed_renewals where id=?"))
                throw new AssertionError("Stop descriptor attempted a live/predecessor query: "+sql);
            if(args.length!=1||!Objects.equals(args[0],row.get("id")))return List.of();
            var result=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,arguments)-> {
                if(Set.of("getObject","getString","getTimestamp").contains(method.getName()))return row.get(arguments[0]);
                throw new UnsupportedOperationException("Unexpected result accessor: "+method.getName());
            });
            try {return List.of(mapper.mapRow(result,0));}catch(SQLException impossible){throw new AssertionError(impossible);}
        }
    }
    private Map<String,Object> descriptor(OwnedFailedRenewal.Admission admission) {
        var upstream=admission.firstRenewalAdmission();var owner=upstream.receipt().intent().clock().owner();
        var row=new HashMap<String,Object>();
        row.put("id",admission.id());row.put("first_renewal_admission",upstream.id());row.put("payment_account_id",owner.account().value());
        row.put("site_id",owner.site().value());row.put("customer_id",owner.customer().value());row.put("subscription_id",upstream.receipt().intent().subscription());
        row.put("ready_id",admission.ready().id());row.put("boundary_id",admission.boundary().id());row.put("source_sha",admission.sourceSha());
        row.put("admitted_at",Timestamp.from(admission.admittedAt()));return row;
    }
    private UUID id(long n) {return new UUID(0,n);}
    private OwnedFailedRenewal.Admission admission() {
        var owner=new ClockOwner(id(1),id(2),new PaymentAccountId(id(3)),new SiteId(id(4)),new CustomerId(id(5)),"acct_owned","alias","2026-09-30.endive",1000,"billing_clock_"+id(1),"clock-key","a".repeat(64),now.minusSeconds(100));
        var clock=new ReadyClock(owner,id(6),"clock_owned",1000,2000,3000,now.minusSeconds(50),"b".repeat(64));
        var terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        var intent=new OwnedClockSubscription(id(9),clock,id(10),id(11),"cus_owned",id(12),id(13),"subscription:"+id(3)+":"+id(13)+":CREATE","v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),now.minusSeconds(10));
        var receipt=new OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",900,1100,now.minusSeconds(5));
        var upstream=new OwnedFirstRenewal.Admission(id(16),receipt,"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,"in_initial","inpay_initial","pi_initial",2,1,"e".repeat(64),now);
        var first=ready(upstream,OwnedFirstRenewal.Step.BOUNDARY,null,null,1100);
        var boundary=new OwnedFirstRenewal.Boundary(id(30),upstream.id(),first,"si_owned","in_renewal",new Subscription.Period(Instant.ofEpochSecond(1100),Instant.ofEpochSecond(10000)),"e".repeat(64),now);
        return new OwnedFailedRenewal.Admission(id(40),upstream,ready(upstream,OwnedFirstRenewal.Step.FINALIZATION,first.id(),boundary.id(),4700),boundary,upstream.sourceSha(),now);
    }
    private OwnedFirstRenewal.LaterReady ready(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.Step step,UUID previous,UUID boundary,long target) {
        var advance=new OwnedFirstRenewal.Advance(id(20+step.number()),upstream.id(),step,target,previous,boundary,"billing-renewal-advance-v1:"+upstream.id()+":"+step.number(),"e".repeat(64),now);
        var operation=new ProviderOperation(id(50+step.number()),upstream.receipt().intent().clock().owner().account(),"acct_owned","alias",ProviderOperation.Kind.TEST_CLOCK_ADVANCE,advance.id(),advance.operationKey(),advance.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,"clock_owned",now,null,null,null,1);
        var ack=new OwnedFirstRenewal.Ack(advance,operation,"owner",now.plusSeconds(60),new OwnedFirstRenewal.TestClockAck("clock_owned","test_helpers.test_clock",false,"owned",target,"ready",2000,3000,null),now);
        return new OwnedFirstRenewal.LaterReady(id(60+step.number()),upstream.id(),ack,new OwnedFirstRenewal.TestClockReady("clock_owned","test_helpers.test_clock",false,"owned",target,"ready",2000,3000,null),now);
    }
}
