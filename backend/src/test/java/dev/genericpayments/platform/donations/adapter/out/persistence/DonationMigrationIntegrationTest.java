package dev.genericpayments.platform.donations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class DonationMigrationIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;
    String schema;
    SingleConnectionDataSource isolated;
    JdbcTemplate jdbc;
    UUID account=UUID.randomUUID(),site=UUID.randomUUID(),dynamic=UUID.randomUUID(),catalog=UUID.randomUUID(),key=UUID.randomUUID();
    List<Map<String,Object>> paymentsBefore;
    Map<String,Object> keyBefore;
    void migrate(String target) {
        var config=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema);
        if(target!=null) config.target(target);
        config.load().migrate();
    }
    @BeforeEach void acceptedV10WithActualRows() throws Exception {
        schema="donation_migration_"+UUID.randomUUID().toString().replace("-","");
        migrate("10");
        isolated=new SingleConnectionDataSource(source.getConnection(),true);
        jdbc=new JdbcTemplate(isolated); jdbc.execute("set search_path to "+schema);
        jdbc.update("insert into payment_accounts values (?,'legacy','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,'legacy','Legacy',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site,account);
        UUID product=UUID.randomUUID(),price=UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'legacy-product','Legacy','ACTIVE',now(),now())",product,site);
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'legacy-price',1,'ONE_TIME',2000,'EUR','NONE','PUBLISHED',now(),now())",price,site,product);
        jdbc.update("insert into payments(id,site_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DYNAMIC','legacy-order',2000,'EUR','NONE','CREATED',now(),now())",dynamic,site,account);
        jdbc.update("insert into payments(id,site_id,payment_account_id,source,price_id,snapshot_price_key,snapshot_version,snapshot_amount_minor,snapshot_currency,snapshot_tax_mode,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'CATALOG',?,'legacy-price',1,2000,'EUR','NONE',2000,'EUR','NONE','CREATED',now(),now())",catalog,site,account,price);
        jdbc.update("insert into site_api_keys(id,site_id,prefix,key_hash,scopes,status,created_at) values (?,?,'legacy-prefix','$argon2id$v=19$legacy','[\"payments:create\",\"payments:read\",\"customers:read\",\"customers:manage\"]','ACTIVE',now())",key,site);
        paymentsBefore=jdbc.queryForList("select * from payments order by id");
        keyBefore=jdbc.queryForMap("select * from site_api_keys where id=?",key);
    }
    @AfterEach void dropOwnedSchema() {
        if(jdbc!=null) { jdbc.execute("set search_path to public"); jdbc.execute("drop schema "+schema+" cascade"); isolated.destroy(); }
    }
    @Test void v11AddsDonationsWithoutChangingLegacyFinancialFactsOrKeys() {
        migrate(null);
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema=?",String.class,schema))
            .contains("donation_campaigns","donation_campaign_suggested_amounts","donations");
        var paymentsAfter=jdbc.queryForList("select * from payments order by id");
        assertThat(paymentsAfter).hasSameSizeAs(paymentsBefore);
        assertThat(paymentsAfter.stream().map(row->row.get("id")).toList())
            .containsExactlyInAnyOrderElementsOf(paymentsBefore.stream().map(row->row.get("id")).toList());
        for(var after:paymentsAfter) {
            var before=paymentsBefore.stream().filter(row->row.get("id").equals(after.get("id"))).findFirst().orElseThrow();
            assertThat(after).containsAllEntriesOf(before);
            assertThat(after).containsEntry("donation_id",null).containsEntry("donation_campaign_id",null)
                .containsEntry("snapshot_campaign_key",null).containsEntry("snapshot_campaign_version",null).containsEntry("snapshot_campaign_currency",null);
        }
        assertThat(jdbc.queryForMap("select * from site_api_keys where id=?",key)).isEqualTo(keyBefore);
        jdbc.update("update site_api_keys set scopes='[\"donations:read\",\"customers:read\"]' where id=?",key);
        for(String invalid:List.of("[]","{}","[\"unknown\"]","[\"donations:read\",null]"))
            assertThatThrownBy(()->jdbc.update("update site_api_keys set scopes=?::jsonb where id=?",invalid,key))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success and version is not null order by installed_rank",String.class))
            .containsExactly("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24");
    }

    UUID campaign,donation;
    void donationRows() {
        migrate(null); campaign=UUID.randomUUID(); donation=UUID.randomUUID();
        campaign(campaign,site,"winter-fund");
        jdbc.update("insert into donations(id,site_id,campaign_id,message_state,created_at,updated_at) values (?,?,?,'ABSENT',now(),now())",donation,site,campaign);
    }
    void campaign(UUID id,UUID owner,String campaignKey) {
        jdbc.update("insert into donation_campaigns(id,site_id,campaign_key,display_name,currency,minimum_amount,maximum_amount,custom_amount_allowed,anonymous_allowed,status,activated_once,version,created_at,updated_at) values (?,?,?,'Winter','EUR',100,10000,true,true,'DRAFT',false,0,now(),now())",id,owner,campaignKey);
    }
    UUID donationPayment() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into payments(id,site_id,payment_account_id,source,donation_id,donation_campaign_id,snapshot_campaign_key,snapshot_campaign_version,snapshot_campaign_currency,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DONATION',?,?,'winter-fund',0,'EUR',2000,'EUR','NONE','CREATED',now(),now())",id,site,account,donation,campaign);
        return id;
    }
    void reject(String table,UUID id,String assignment,String constraint) {
        assertThatThrownBy(()->jdbc.update("update "+table+" set "+assignment+" where id=?",id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .rootCause().isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,error->{
                assertThat(error.getSQLState()).isIn("23514","23503","23505");
                assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
            });
    }
    @Test void everySourceRequiresAllItsFieldsAndRejectsEveryForeignShapeField() {
        donationRows(); UUID donated=donationPayment();
        for(UUID legacy:List.of(dynamic,catalog)) for(String field:List.of(
                "donation_id='"+donation+"'","donation_campaign_id='"+campaign+"'","snapshot_campaign_key='winter-fund'",
                "snapshot_campaign_version=0","snapshot_campaign_currency='EUR'"))
            reject("payments",legacy,field,"ck_payments_shape");
        for(String field:List.of("price_id","snapshot_price_key","snapshot_version","snapshot_amount_minor","snapshot_currency","snapshot_tax_mode"))
            reject("payments",catalog,field+"=null","ck_payments_shape");
        reject("payments",catalog,"external_order_id='mixed'","ck_payments_shape");
        reject("payments",dynamic,"external_order_id=null","ck_payments_shape");
        reject("payments",dynamic,"external_order_id=' '","ck_payments_shape");
        UUID price=jdbc.queryForObject("select price_id from payments where id=?",UUID.class,catalog);
        for(UUID target:List.of(dynamic,donated)) for(String field:List.of("price_id='"+price+"'","snapshot_price_key='legacy-price'","snapshot_version=1","snapshot_amount_minor=2000","snapshot_currency='EUR'","snapshot_tax_mode='NONE'"))
            reject("payments",target,field,"ck_payments_shape");
        for(String field:List.of("donation_id","donation_campaign_id","snapshot_campaign_key","snapshot_campaign_version","snapshot_campaign_currency"))
            reject("payments",donated,field+"=null","ck_payments_shape");
        for(String assignment:List.of("external_order_id='mixed'","tax_mode='INCLUSIVE'","snapshot_campaign_currency='USD'","snapshot_campaign_version=-1","snapshot_campaign_key=' '","source='DYNAMIC'","source='CATALOG'"))
            reject("payments",donated,assignment,"ck_payments_shape");
        for(String assignment:List.of("snapshot_amount_minor=1999","snapshot_currency='USD'","snapshot_tax_mode='INCLUSIVE'"))
            reject("payments",catalog,assignment,"ck_payments_shape");
    }
    @Test void compositeOwnershipRejectsCrossSiteAndMismatchedCampaignReferences() {
        donationRows(); UUID donated=donationPayment(); UUID otherSite=UUID.randomUUID(),otherCampaign=UUID.randomUUID(),sameSiteCampaign=UUID.randomUUID();
        jdbc.update("insert into sites values (?,'other-site','Other',?,'ACTIVE','AUTOMATIC',now(),now(),0)",otherSite,account);
        campaign(otherCampaign,otherSite,"winter-fund"); campaign(sameSiteCampaign,site,"summer-fund");
        UUID unusedDonation=UUID.randomUUID();
        jdbc.update("insert into donations(id,site_id,campaign_id,message_state,created_at,updated_at) values (?,?,?,'ABSENT',now(),now())",unusedDonation,site,campaign);
        reject("donations",unusedDonation,"site_id='"+otherSite+"'","fk_donations_campaign_site");
        reject("payments",donated,"donation_campaign_id='"+sameSiteCampaign+"'","fk_payments_donation_site_campaign");
        reject("payments",donated,"site_id='"+otherSite+"'","fk_payments_donation_site_campaign");
        UUID foreignDonation=UUID.randomUUID();
        jdbc.update("insert into donations(id,site_id,campaign_id,message_state,created_at,updated_at) values (?,?,?,'ABSENT',now(),now())",foreignDonation,otherSite,otherCampaign);
        reject("payments",donated,"donation_id='"+foreignDonation+"',donation_campaign_id='"+otherCampaign+"'","fk_payments_donation_site_campaign");
        UUID customer=UUID.randomUUID(); jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",customer,otherSite);
        reject("payments",donated,"customer_id='"+customer+"'","fk_payments_customer_site");
        assertThatThrownBy(this::donationPayment).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .hasStackTraceContaining("uq_payments_donation");
    }
    @Test void encryptedTupleConstraintsRejectPartialOrInvalidPresentAndAbsentOrRedactedMaterial() {
        donationRows();
        for(String assignment:List.of("message_state='PRESENT'","message_nonce=decode(repeat('00',12),'hex')","message_key_version='key'","message_ciphertext=decode(repeat('ab',16),'hex')"))
            reject("donations",donation,assignment,"ck_donations_message");
        jdbc.update("update donations set message_state='PRESENT',message_ciphertext=decode(repeat('ab',16),'hex'),message_nonce=decode(repeat('00',12),'hex'),message_key_version='key-v7' where id=?",donation);
        for(String assignment:List.of("message_ciphertext=null","message_nonce=null","message_key_version=null","message_ciphertext=decode(repeat('ab',15),'hex')","message_nonce=decode(repeat('00',11),'hex')","message_key_version=' '","message_state='ABSENT'","message_state='REDACTED'"))
            reject("donations",donation,assignment,"ck_donations_message");
        jdbc.update("update donations set message_state='REDACTED',message_ciphertext=null,message_nonce=null,message_key_version=null where id=?",donation);
        assertThat(jdbc.queryForObject("select message_state from donations where id=?",String.class,donation)).isEqualTo("REDACTED");
    }
    @Test void suggestionOrderAndUniquenessAndCampaignKeysAreEnforcedByDatabase() {
        donationRows();
        jdbc.update("insert into donation_campaign_suggested_amounts values (?,0,5000),(?,1,1000)",campaign,campaign);
        assertThat(jdbc.queryForList("select amount_minor from donation_campaign_suggested_amounts where campaign_id=? order by position",Long.class,campaign)).containsExactly(5000L,1000L);
        assertThatThrownBy(()->jdbc.update("insert into donation_campaign_suggested_amounts values (?,2,5000)",campaign)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasStackTraceContaining("uq_donation_campaign_suggested_amount");
        assertThatThrownBy(()->jdbc.update("insert into donation_campaign_suggested_amounts values (?,1,2000)",campaign)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasStackTraceContaining("pk_donation_campaign_suggested_amounts");
        assertThatThrownBy(()->campaign(UUID.randomUUID(),site,"winter-fund")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasStackTraceContaining("uq_donation_campaigns_site_key");
    }
}
