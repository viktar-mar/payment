package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.support.StripeBillingClockSubscriptionCompositionFixture;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Hosted PostgreSQL/Spring only; never included in the offline pure test selection. */
@Import(StripeBillingOwnedPublicationLockIntegrationTest.RaceConfiguration.class)
class StripeBillingOwnedPublicationLockIntegrationTest extends StripeBillingClockSubscriptionCompositionFixture {
    @Autowired SiteGate siteGate;
    @Autowired ReadGateJdbc readGate;

    @ParameterizedTest @CsvSource({"facts,receipt","facts,current","reply,receipt","reply,current"})
    void actualLifecyclePublicationAndOwnedReadCompleteWithoutLockInversion(String publication,String read)throws Exception {
        createOwned();var receipt=owned.requireReceipt(billing.id(),current().id());
        var snapshot=retrieve();var fence=fence();
        var pool=Executors.newFixedThreadPool(2);
        siteGate.arm();readGate.arm();
        try {
            var writer=pool.submit(()->{
                Thread.currentThread().setName("owned-publication");
                if(publication.equals("facts"))apply(snapshot,fence);
                else commands.recordReply(reserved.operation().id(),new Reply(Outcome.ACCEPTED,"sub_owned",null));
                return true;
            });
            // The real JPA Site write lock has returned inside FactService/recordReply's transaction.
            assertThat(siteGate.locked.await(10,TimeUnit.SECONDS)).isTrue();
            var reader=pool.submit(()->{
                Thread.currentThread().setName("owned-reader");
                if(read.equals("receipt"))assertThat(owned.requireReceipt(billing.id(),receipt.intent().subscription())).isEqualTo(receipt);
                else assertThat(owned.requireCurrent(receipt.intent())).isEqualTo(receipt.intent());
                return true;
            });
            // Under the old order this callback occurs after the reader already holds Account SHARE.
            assertThat(readGate.atSite.await(10,TimeUnit.SECONDS)).isTrue();
            siteGate.proceed.countDown();
            assertThat(writer.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(reader.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(stopped(owner)).isNull();
            assertThat(current().providerReference()).isEqualTo(receipt.reference());
            assertThat(count("subscription_billing_records")).isEqualTo(publication.equals("facts")?1:0);
            assertNoSuccess();
        } finally {
            siteGate.release();readGate.armed=false;
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
        }
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class RaceConfiguration {
        @Bean @Primary SiteGate publicationSiteGate(SitePersistenceAdapter delegate,JdbcTemplate jdbc){return new SiteGate(delegate,jdbc);}
        @Bean @Primary ReadGateJdbc ownedReadGate(DataSource source){return new ReadGateJdbc(source);}
    }
    static final class SiteGate implements LockSitePort {
        final SitePersistenceAdapter delegate;final JdbcTemplate jdbc;
        volatile boolean armed;
        volatile CountDownLatch locked=new CountDownLatch(1),proceed=new CountDownLatch(0);
        SiteGate(SitePersistenceAdapter delegate,JdbcTemplate jdbc){this.delegate=delegate;this.jdbc=jdbc;}
        void arm(){locked=new CountDownLatch(1);proceed=new CountDownLatch(1);armed=true;}
        void release(){armed=false;proceed.countDown();}
        public Optional<Site> findByKeyForUpdate(SiteKey key){return delegate.findByKeyForUpdate(key);}
        public Optional<Site> findByIdForUpdate(SiteId id){
            var result=delegate.findByIdForUpdate(id);
            if(armed&&Thread.currentThread().getName().equals("owned-publication")) {
                jdbc.execute("set local lock_timeout='8s'");
                locked.countDown();
                try {if(!proceed.await(10,TimeUnit.SECONDS))throw new AssertionError("Publication Site gate timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }
            return result;
        }
    }
    static final class ReadGateJdbc extends JdbcTemplate {
        volatile boolean armed;
        volatile CountDownLatch atSite=new CountDownLatch(1);
        ReadGateJdbc(DataSource source){super(source);}
        void arm(){atSite=new CountDownLatch(1);armed=true;}
        @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args){
            if(armed&&Thread.currentThread().getName().equals("owned-reader")&&sql.contains("from sites")&&sql.endsWith("for share")) {
                super.execute("set local lock_timeout='8s'");
                atSite.countDown();
            }
            return super.queryForList(sql,type,args);
        }
    }
}
