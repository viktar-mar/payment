package dev.genericpayments.platform.donations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DonationPersistenceIntegrationTest extends PostgresIntegrationTest {
    @Autowired DonationCampaignStore campaigns;
    @Autowired DonationStore donations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    SiteId site,other;
    DonationCampaign campaign;
    @BeforeEach void prepare() {
        clearDatabase(); UUID account=UUID.randomUUID(); site=new SiteId(UUID.randomUUID()); other=new SiteId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,'account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        for(SiteId id:List.of(site,other)) jdbc.update("insert into sites values (?,?, 'Site',?,'ACTIVE','AUTOMATIC',now(),now(),0)",id.value(),"site-"+id.value(),account);
        campaign=DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),site,new DonationCampaignKey("winter-fund"),"Winter","Purpose","EUR",List.of(5000L,1000L,3000L),100,10000,true,true,INITIAL_TIME);
    }
    @Test void orderedAmountsAndEveryCampaignFactRoundTripWithoutDoubleIncrementingVersion() {
        campaigns.insert(campaign);
        var loaded=campaigns.findById(site,campaign.id()).orElseThrow();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(campaign);
        assertThat(loaded.suggestedAmounts()).containsExactly(5000L,1000L,3000L);
        var paused=loaded.activate(INITIAL_TIME.plusSeconds(1)).pause(INITIAL_TIME.plusSeconds(2));
        campaigns.update(paused,0);
        assertThat(campaigns.findByKey(site,campaign.campaignKey()).orElseThrow()).usingRecursiveComparison().isEqualTo(paused);
        assertThat(jdbc.queryForObject("select version from donation_campaigns where id=?",Long.class,campaign.id().value())).isEqualTo(2);
        assertThatThrownBy(()->campaigns.update(loaded.activate(INITIAL_TIME.plusSeconds(3)),0)).isInstanceOf(OptimisticLockingFailureException.class);
        var changed=paused.updateConfiguration("New",null,"EUR",List.of(2000L,4000L),100,10000,false,false,INITIAL_TIME.plusSeconds(3));
        campaigns.update(changed,2);
        assertThat(campaigns.findById(site,campaign.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(changed);
        var archived=changed.archive(INITIAL_TIME.plusSeconds(4)); campaigns.update(archived,3);
        assertThat(campaigns.findById(site,campaign.id()).orElseThrow().activatedOnce()).isTrue();
        assertThatThrownBy(()->campaigns.findById(site,campaign.id()).orElseThrow().updateConfiguration("No",null,"USD",List.of(1000L),100,10000,true,true,INITIAL_TIME.plusSeconds(5))).isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class);
    }
    @Test void keysAreUniqueWithinSiteAndAllReadsAndLocksAreTenantScoped() {
        campaigns.insert(campaign);
        assertThat(campaigns.findById(other,campaign.id())).isEmpty();
        assertThat(campaigns.findByKey(other,campaign.campaignKey())).isEmpty();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            assertThat(campaigns.findByIdForUpdate(other,campaign.id())).isEmpty();
            assertThat(campaigns.findByKeyForUpdate(other,campaign.campaignKey())).isEmpty();
            assertThat(campaigns.findByIdForUpdate(site,campaign.id())).isPresent();
            assertThat(campaigns.findByKeyForUpdate(site,campaign.campaignKey())).isPresent();
        });
        var sameKey=DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),site,campaign.campaignKey(),"Duplicate",null,"EUR",List.of(),100,10000,true,true,INITIAL_TIME);
        assertThatThrownBy(()->campaigns.insert(sameKey)).isInstanceOf(DataIntegrityViolationException.class);
        var otherSite=DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),other,campaign.campaignKey(),"Other",null,"EUR",List.of(),100,10000,true,true,INITIAL_TIME);
        campaigns.insert(otherSite);
        assertThat(campaigns.findByKey(other,campaign.campaignKey()).orElseThrow().id()).isEqualTo(otherSite.id());
    }
    @Test void opaqueCiphertextTupleAndAbsentAndRedactedStatesRoundTripWithoutCustomerDuplication() {
        campaigns.insert(campaign);
        DonationId id=new DonationId(UUID.randomUUID());
        Donation present=Donation.create(id,site,campaign.id(),DonationStore.messageReference(id),INITIAL_TIME);
        byte[] cipher=new byte[32]; Arrays.fill(cipher,(byte)0xA7);
        EncryptedValue encrypted=new EncryptedValue(cipher,new byte[12],"test-key-v7");
        donations.save(present,encrypted);
        assertThat(donations.findById(site,id).orElseThrow()).usingRecursiveComparison().isEqualTo(present);
        EncryptedValue stored=donations.findEncryptedMessage(site,id).orElseThrow();
        assertThat(stored.ciphertext()).containsExactly(cipher); assertThat(stored.nonce()).containsExactly(new byte[12]); assertThat(stored.keyVersion()).isEqualTo("test-key-v7");
        donations.save(present);
        assertThat(donations.findEncryptedMessage(site,id).orElseThrow().ciphertext()).containsExactly(cipher);
        assertThat(donations.findById(other,id)).isEmpty(); assertThat(donations.findEncryptedMessage(other,id)).isEmpty();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='public' and table_name='donations'",String.class)).doesNotContain("customer_id","message","donor_message","encrypted_message_ref");
        Donation redacted=present.redactMessage(INITIAL_TIME.plusSeconds(1)); donations.save(redacted);
        assertThat(donations.findById(site,id).orElseThrow()).usingRecursiveComparison().isEqualTo(redacted);
        assertThat(donations.findEncryptedMessage(site,id)).isEmpty();
        assertThat(jdbc.queryForMap("select message_ciphertext,message_nonce,message_key_version from donations where id=?",id.value()).values()).containsOnlyNulls();
        assertThatThrownBy(()->donations.save(present,encrypted)).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        Donation absent=Donation.create(new DonationId(UUID.randomUUID()),site,campaign.id(),null,INITIAL_TIME); donations.save(absent);
        assertThat(donations.findById(site,absent.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(absent);
        assertThat(donations.findEncryptedMessage(site,absent.id())).isEmpty();
    }
    @Test void rejectsMissingUnknownOrMismatchedMessageMaterialAndCrossSiteCampaign() {
        campaigns.insert(campaign); DonationId id=new DonationId(UUID.randomUUID());
        var encrypted=new EncryptedValue(new byte[16],new byte[12],"key-v1");
        var present=Donation.create(id,site,campaign.id(),DonationStore.messageReference(id),INITIAL_TIME);
        assertThatThrownBy(()->donations.save(present)).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->donations.save(Donation.create(id,site,campaign.id(),"unknown-reference",INITIAL_TIME),encrypted)).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->donations.save(Donation.create(id,site,campaign.id(),null,INITIAL_TIME),encrypted)).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->donations.save(Donation.create(id,other,campaign.id(),null,INITIAL_TIME))).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void sharedResetClearsBothDonationChildrenAndCampaigns() {
        campaigns.insert(campaign); donations.save(Donation.create(new DonationId(UUID.randomUUID()),site,campaign.id(),null,INITIAL_TIME));
        truncateDatabase(jdbc);
        for(String table:List.of("donations","donation_campaign_suggested_amounts","donation_campaigns","sites"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Long.class)).isZero();
    }
    @Test void configurationCanReorderExistingSuggestedAmountsWithoutTransientUniquenessConflicts() {
        campaigns.insert(campaign);
        var reordered=campaign.updateConfiguration("Winter",null,"EUR",List.of(1000L,5000L,3000L),100,10000,true,true,INITIAL_TIME.plusSeconds(1));
        assertThatCode(()->campaigns.update(reordered,0)).doesNotThrowAnyException();
        assertThat(campaigns.findById(site,campaign.id()).orElseThrow().suggestedAmounts()).containsExactly(1000L,5000L,3000L);
    }
    @Test void lockingLookupRefreshesPreviouslyReadCampaignAndActuallyBlocksCompetingLocks() throws Exception {
        campaigns.insert(campaign); var read=new CountDownLatch(1); var changed=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{
                campaigns.findById(site,campaign.id()).orElseThrow(); read.countDown();
                try { if(!changed.await(10,TimeUnit.SECONDS)) throw new AssertionError("update timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return campaigns.findByIdForUpdate(site,campaign.id()).orElseThrow().version();
            }));
            assertThat(read.await(10,TimeUnit.SECONDS)).isTrue();
            try { campaigns.update(campaign.activate(INITIAL_TIME.plusSeconds(1)),0); }
            finally { changed.countDown(); }
            assertThat(future.get(10,TimeUnit.SECONDS)).isEqualTo(1L);
        }
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                campaigns.findByKeyForUpdate(site,campaign.campaignKey()).orElseThrow(); locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                    jdbc.execute("set local lock_timeout='200ms'"); campaigns.findByIdForUpdate(site,campaign.id());
                })).hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause().extracting("SQLState").isEqualTo("55P03");
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
    }
    @Test void cachedDonationCannotRestoreMessageRedactedInAnotherTransaction() throws Exception {
        campaigns.insert(campaign); DonationId id=new DonationId(UUID.randomUUID());
        Donation present=Donation.create(id,site,campaign.id(),DonationStore.messageReference(id),INITIAL_TIME);
        var encrypted=new EncryptedValue(new byte[16],new byte[12],"key-v1"); donations.save(present,encrypted);
        var read=new CountDownLatch(1); var redacted=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                donations.findById(site,id).orElseThrow(); read.countDown();
                try { if(!redacted.await(10,TimeUnit.SECONDS)) throw new AssertionError("redaction timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                assertThatThrownBy(()->donations.save(present,encrypted)).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
            }));
            assertThat(read.await(10,TimeUnit.SECONDS)).isTrue();
            try { donations.save(present.redactMessage(INITIAL_TIME.plusSeconds(1))); }
            finally { redacted.countDown(); }
            // The rejected persistence call marks its surrounding transaction rollback-only.
            assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).hasRootCauseInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        }
        assertThat(donations.findById(site,id).orElseThrow().messageState()).isEqualTo(DonorMessageState.REDACTED);
        assertThat(donations.findEncryptedMessage(site,id)).isEmpty();
    }
}
