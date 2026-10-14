package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class PaymentDatabaseFixture extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    UUID account, site, payment;
    @BeforeEach void prepare() {
        clearDatabase();
        account = UUID.randomUUID(); site = UUID.randomUUID(); payment = UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?, 'account', 'FAKE', 'SANDBOX', 'ACTIVE', 'fake', 'ref', now(), 0)", account);
        jdbc.update("insert into sites values (?, 'site', 'Site', ?, 'ACTIVE', 'AUTOMATIC', now(), now(), 0)", site, account);
        insertPayment(payment, "order-1");
    }
    void insertPayment(UUID id, String order) {
        jdbc.update("insert into payments(id,site_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DYNAMIC',?,2000,'EUR','NONE','CREATED',now(),now())", id,site,account,order);
    }
}

class PaymentConstraintsIntegrationTest extends PaymentDatabaseFixture {
    static Stream<Arguments> invalidIdempotencyStates() {
        String live="response_secret_state='LIVE',response_secret_ciphertext=decode(repeat('00',16),'hex'),response_secret_nonce=decode(repeat('00',12),'hex'),response_secret_key_version='local-v1',response_secret_expires_at=now()+interval '1 hour'";
        return Stream.of(
            Arguments.of("IN_PROGRESS","resource_type='PAYMENT'","ck_idempotency_in_progress"),
            Arguments.of("IN_PROGRESS","resource_id='00000000-0000-0000-0000-000000000001'","ck_idempotency_in_progress"),
            Arguments.of("IN_PROGRESS","resource_type='PAYMENT',resource_id='00000000-0000-0000-0000-000000000001'","ck_idempotency_in_progress"),
            Arguments.of("IN_PROGRESS","response_status=201","ck_idempotency_in_progress"),
            Arguments.of("IN_PROGRESS",live,"ck_idempotency_in_progress"),
            Arguments.of("IN_PROGRESS","response_secret_state='EXPIRED',response_secret_expires_at=now()+interval '1 hour'","ck_idempotency_in_progress"),
            Arguments.of("IN_PROGRESS","fingerprint='"+"A".repeat(64)+"'","ck_idempotency_fingerprint"),
            Arguments.of("COMPLETED","resource_type='UNSUPPORTED'","ck_idempotency_resource_type"),
            Arguments.of("COMPLETED","resource_type=null","ck_idempotency_completed"),
            Arguments.of("COMPLETED","resource_id=null","ck_idempotency_completed"),
            Arguments.of("COMPLETED","response_status=null","ck_idempotency_completed"),
            Arguments.of("COMPLETED","response_status=99","ck_idempotency_response_status"),
            Arguments.of("COMPLETED","status='INVALID'","ck_idempotency_status"),
            Arguments.of("COMPLETED","response_secret_state='LIVE'","ck_idempotency_secret"),
            Arguments.of("COMPLETED","response_secret_state='EXPIRED'","ck_idempotency_secret"),
            Arguments.of("LIVE","response_secret_ciphertext=decode('','hex')","ck_idempotency_secret"),
            Arguments.of("LIVE","response_secret_ciphertext=decode('ab','hex')","ck_idempotency_secret"),
            Arguments.of("LIVE","response_secret_ciphertext=decode(repeat('00',15),'hex')","ck_idempotency_secret"),
            Arguments.of("LIVE","response_secret_nonce=decode(repeat('00',11),'hex')","ck_idempotency_secret"),
            Arguments.of("LIVE","response_secret_nonce=decode(repeat('00',13),'hex')","ck_idempotency_secret"),
            Arguments.of("LIVE","response_secret_key_version=' '","ck_idempotency_key_version"),
            Arguments.of("LIVE","response_secret_expires_at=expires_at+interval '1 second'","ck_idempotency_secret_expiry"),
            Arguments.of("LIVE","response_secret_expires_at=created_at","ck_idempotency_secret_expiry"));
    }

    @ParameterizedTest @MethodSource("invalidIdempotencyStates")
    void databaseRejectsIdempotencyStatesThatCannotBeLoaded(String state,String change,String constraint) {
        jdbc.update("insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,expires_at,created_at,updated_at) values (?,'s','k',?,'IN_PROGRESS',now()+interval '30 days',now(),now())",UUID.randomUUID(),"a".repeat(64));
        if (!state.equals("IN_PROGRESS"))
            jdbc.update("update idempotency_records set status='COMPLETED',resource_type='PAYMENT',resource_id=?,response_status=201",payment);
        if (state.equals("LIVE"))
            jdbc.update("update idempotency_records set response_secret_state='LIVE',response_secret_ciphertext=decode(repeat('00',16),'hex'),response_secret_nonce=decode(repeat('00',12),'hex'),response_secret_key_version='local-v1',response_secret_expires_at=now()+interval '1 hour'");
        reject("idempotency_records",change,constraint);
    }

    @Test void dynamicOrderIsUniqueWithinSite() {
        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), "order-1")).isInstanceOf(DataIntegrityViolationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"amount_minor=0", "currency='eur'", "source='INVALID'", "status='INVALID'", "tax_mode='INVALID'", "version=-1", "refunded_amount_minor=-1", "refunded_amount_minor=1", "tax_amount_minor=2001", "external_order_id=null", "reconciliation_lease_owner='worker'", "site_id='00000000-0000-0000-0000-000000000000'"})
    void paymentConstraintsRejectInvalidFacts(String assignment) {
        assertThatThrownBy(() -> jdbc.update("update payments set " + assignment + " where id=?", payment)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void attemptsRequirePositiveUniqueSequenceAndOperationKey() {
        jdbc.update("insert into payment_attempts(id,payment_id,attempt_number,provider_operation_key,status,created_at,updated_at) values (?,?,1,'op','CREATED',now(),now())",UUID.randomUUID(),payment);
        for (String values : new String[]{"1,'other','CREATED'", "2,'op','CREATED'", "0,'zero','CREATED'", "2,'bad','BOGUS'"}) {
            assertThatThrownBy(() -> jdbc.update("insert into payment_attempts(id,payment_id,attempt_number,provider_operation_key,status,created_at,updated_at) values (?, ?,"+values+",now(),now())",UUID.randomUUID(),payment)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
    @Test void refundsRequirePositiveMatchingCurrencyAndUniqueOperation() {
        for (String values : new String[]{"0,'EUR','PENDING'", "1,'USD','PENDING'", "1,'EUR','BOGUS'"}) {
            assertThatThrownBy(() -> jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?, ?,"+values+",'refund-op',now(),now())",UUID.randomUUID(),payment)).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?,?,500,'EUR','PENDING','refund-op',now(),now())",UUID.randomUUID(),payment);
        assertThatThrownBy(() -> jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?,?,500,'EUR','PENDING','refund-op',now(),now())",UUID.randomUUID(),payment)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void inboxDeduplicatesByProviderAccountAndEvent() {
        String sql="insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,occurred_at,received_at,payload_hash) values (?,'FAKE',?,'evt','obj','PAYMENT_SUCCEEDED',now(),now(),?)";
        jdbc.update(sql,UUID.randomUUID(),account,"a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(sql,UUID.randomUUID(),account,"a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void sessionOwnershipAndHandoffOriginAreEnforced() {
        String sql="insert into checkout_sessions(id,session_type,payment_id,site_id,token_hash,scopes,expires_at,created_at) values (?,'HANDOFF',?,?,?,'[\"checkout:read\"]',now()+interval '10 minutes',now())";
        assertThatThrownBy(() -> jdbc.update(sql,UUID.randomUUID(),payment,site,"a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(sql.replace("'HANDOFF'","'CHECKOUT'"),UUID.randomUUID(),payment,site,"b".repeat(64));
        assertThatThrownBy(() -> jdbc.update("update checkout_sessions set site_id=?",UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void idempotencyAndSecretVersionKeysAreUnique() {
        String sql="insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,expires_at,created_at,updated_at) values (?,'scope','key',?,'IN_PROGRESS',now()+interval '30 days',now(),now())";
        jdbc.update(sql,UUID.randomUUID(),"a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(sql,UUID.randomUUID(),"a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update idempotency_records set status='COMPLETED'")).isInstanceOf(DataIntegrityViolationException.class);
        UUID secret=secret();
        assertThatThrownBy(() -> secret()).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://example.test/callback',?,true,now(),now())",site,secret);
    }
    UUID secret() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,decode('001122','hex'),decode('000000000000000000000000','hex'),'local-v1',now())",id,site);
        return id;
    }
    @Test void outboxFreezesSourcePayloadEndpointAndSecretButAllowsDeliveryProgress() {
        UUID event=UUID.randomUUID(), secret=secret();
        jdbc.update("insert into payment_events(id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,occurred_at,received_at,request_id,payload_hash) values (?,?,?,?,1,'PAYMENT_CREATED','API',now(),now(),'request',?)",event,payment,site,account,"a".repeat(64));
        String sql="insert into outbox_events(id,site_id,payment_id,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,next_attempt_at,created_at,updated_at) values (?,?,?,?,'payment.processing','{}','https://example.test/callback',?,now(),now(),now())";
        jdbc.update(sql,event,site,payment,account,secret);
        assertThatThrownBy(() -> jdbc.update(sql,event,site,payment,account,secret)).isInstanceOf(DataIntegrityViolationException.class);
        for (String change: new String[]{"payload='{\"changed\":true}'", "endpoint_url='https://other.test'", "secret_version_id='00000000-0000-0000-0000-000000000000'", "callback_type='payment.failed'"})
            assertThatThrownBy(() -> jdbc.update("update outbox_events set "+change)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update outbox_events set status='DELIVERED',attempt_count=1");
        assertThat(jdbc.queryForObject("select status from outbox_events",String.class)).isEqualTo("DELIVERED");
        String delivery="insert into outbox_deliveries(id,outbox_event_id,attempt_number,delivery_mode,status,http_status,started_at,finished_at) values (?,?,1,'AUTOMATIC','SUCCEEDED',200,now(),now())";
        jdbc.update(delivery,UUID.randomUUID(),event);
        assertThatThrownBy(() -> jdbc.update(delivery,UUID.randomUUID(),event)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void allNewTablesRejectInvalidEnumsCountersAndHashes() {
        assertThat(jdbc.queryForObject("select count(*) from fake_provider_objects",Long.class)).isZero();
        String sql="insert into fake_provider_objects(provider_reference,provider_operation_key,object_type,internal_object_id,payment_account_id,state,event_counter,created_at,updated_at) values ('fake','op','PAYMENT',?,?,'CREATED',0,now(),now())";
        jdbc.update(sql,payment,account);
        for (String change: new String[]{"object_type='BOGUS'", "state='BOGUS'", "event_counter=-1", "version=-1"})
            assertThatThrownBy(() -> jdbc.update("update fake_provider_objects set "+change)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into site_api_keys(id,site_id,prefix,key_hash,scopes,status,created_at) values (?,?,'prefix','$argon2id$v=19$test','[\"payments:create\"]','ACTIVE',now())",UUID.randomUUID(),site);
        for (String change: new String[]{"status='BOGUS'", "version=-1", "scopes='[\"admin\"]'", "key_hash='plaintext'"})
            assertThatThrownBy(() -> jdbc.update("update site_api_keys set "+change)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void inboxRejectsInvalidProviderEventStatusCounterHashAndLease() {
        jdbc.update("insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,occurred_at,received_at,payload_hash) values (?,'FAKE',?,'evt','obj','PAYMENT_SUCCEEDED',now(),now(),?)",UUID.randomUUID(),account,"a".repeat(64));
        reject("provider_inbox","provider_type='INVALID'","ck_inbox_provider");
        reject("provider_inbox","event_type='INVALID'","ck_inbox_family_event");
        reject("provider_inbox","status='INVALID'","ck_inbox_status");
        reject("provider_inbox","attempt_count=-1","ck_inbox_attempts");
        reject("provider_inbox","payload_hash='invalid'","ck_inbox_hash");
        reject("provider_inbox","status='PROCESSING'","ck_inbox_processing");
        reject("provider_inbox","lease_owner='worker'","ck_inbox_lease");
        reject("provider_inbox","safe_payload='[]'","ck_inbox_payload");
        reject("provider_inbox","payment_account_id='"+UUID.randomUUID()+"'","fk_inbox_account");
    }
    @Test void projectionChecksAndForeignKeysCoverAttemptsRefundsAndSequences() {
        jdbc.update("insert into payment_attempts(id,payment_id,attempt_number,provider_operation_key,status,created_at,updated_at) values (?,?,1,'op','CREATED',now(),now())",UUID.randomUUID(),payment);
        reject("payment_attempts","version=-1","ck_attempts_version");
        reject("payment_attempts","payment_method_family='raw unsafe text'","ck_attempts_method");
        reject("payment_attempts","result_code='raw unsafe text'","ck_attempts_result");
        reject("payment_attempts","provider_operation_key=' '","ck_attempts_operation");
        reject("payment_attempts","payment_id='"+UUID.randomUUID()+"'","fk_attempts_payment");
        jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?,?,500,'EUR','PENDING','refund-op',now(),now())",UUID.randomUUID(),payment);
        reject("refunds","version=-1","ck_refunds_version");
        reject("refunds","reason_code='raw unsafe text'","ck_refunds_reason");
        reject("refunds","provider_operation_key=' '","ck_refunds_operation");
        reject("refunds","payment_id='"+UUID.randomUUID()+"'","fk_refunds_payment_currency");
        jdbc.update("insert into payment_event_sequences values (?,1)",payment);
        reject("payment_event_sequences","next_sequence=0","ck_event_sequences_number");
        reject("payment_event_sequences","payment_id='"+UUID.randomUUID()+"'","fk_event_sequences_payment");
    }
    @Test void idempotencyEncryptionAndSessionShapeChecksHold() {
        jdbc.update("insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,resource_type,resource_id,response_status,expires_at,created_at,updated_at) values (?,'s','k',?,'COMPLETED','PAYMENT',?,201,now()+interval '30 days',now(),now())",UUID.randomUUID(),"a".repeat(64),payment);
        reject("idempotency_records","status='INVALID'","ck_idempotency_status");
        reject("idempotency_records","fingerprint='invalid'","ck_idempotency_fingerprint");
        reject("idempotency_records","version=-1","ck_idempotency_version");
        reject("idempotency_records","response_status=99","ck_idempotency_response_status");
        reject("idempotency_records","response_secret_ciphertext=decode('ab','hex')","ck_idempotency_secret");
        jdbc.update("update idempotency_records set response_secret_state='LIVE',response_secret_ciphertext=decode(repeat('00',16),'hex'),response_secret_nonce=decode('000000000000000000000000','hex'),response_secret_key_version='local-v1',response_secret_expires_at=now()+interval '1 hour'");
        reject("idempotency_records","response_secret_key_version=' '","ck_idempotency_key_version");
        reject("idempotency_records","response_secret_nonce=decode('ab','hex')","ck_idempotency_secret");
        jdbc.update("insert into checkout_sessions(id,session_type,payment_id,site_id,token_hash,scopes,expires_at,created_at) values (?,'CHECKOUT',?,?,?,'[]',now()+interval '1 hour',now())",UUID.randomUUID(),payment,site,"a".repeat(64));
        reject("checkout_sessions","session_type='INVALID'","ck_sessions_type");
        reject("checkout_sessions","token_hash='invalid'","ck_sessions_hash");
        reject("checkout_sessions","scopes='{}'","ck_sessions_scopes");
        reject("checkout_sessions","expires_at=created_at","ck_sessions_times");
        reject("checkout_sessions","payment_id='"+UUID.randomUUID()+"'","fk_sessions_payment_site");
    }
    @Test void callbackCredentialAndDeliveryConstraintsHold() {
        UUID secret=secret(),event=UUID.randomUUID();
        reject("site_callback_secret_versions","secret_version=0","ck_callback_secrets_version");
        reject("site_callback_secret_versions","master_key_version=' '","ck_callback_secrets_master_version");
        reject("site_callback_secret_versions","nonce=decode('ab','hex')","ck_callback_secrets_nonce");
        reject("site_callback_secret_versions","site_id='"+UUID.randomUUID()+"'","fk_callback_secrets_site");
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://example.test',?,true,now(),now())",site,secret);
        reject("site_callback_configs","version=-1","ck_callback_configs_version");
        reject("site_callback_configs","active_secret_version_id='"+UUID.randomUUID()+"'","fk_callback_configs_secret_site");
        jdbc.update("insert into payment_events(id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,occurred_at,received_at,request_id,payload_hash) values (?,?,?,?,1,'PAYMENT_CREATED','API',now(),now(),'r',?)",event,payment,site,account,"a".repeat(64));
        jdbc.update("insert into outbox_events(id,site_id,payment_id,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,next_attempt_at,created_at,updated_at) values (?,?,?,?,'payment.processing','{}','https://example.test',?,now(),now(),now())",event,site,payment,account,secret);
        reject("outbox_events","status='INVALID'","ck_outbox_status");
        reject("outbox_events","attempt_count=-1","ck_outbox_attempts");
        reject("outbox_events","version=-1","ck_outbox_version");
        reject("outbox_events","lease_owner='worker'","ck_outbox_lease");
        jdbc.update("insert into outbox_deliveries(id,outbox_event_id,attempt_number,delivery_mode,status,http_status,started_at,finished_at) values (?,?,1,'AUTOMATIC','SUCCEEDED',200,now(),now())",UUID.randomUUID(),event);
        reject("outbox_deliveries","attempt_number=0","ck_deliveries_attempt");
        reject("outbox_deliveries","delivery_mode='INVALID'","ck_deliveries_mode");
        reject("outbox_deliveries","status='INVALID'","ck_deliveries_status");
        reject("outbox_deliveries","status='FAILED',http_status=99","ck_deliveries_http");
        reject("outbox_deliveries","http_status=500","ck_deliveries_success");
        reject("outbox_deliveries","outbox_event_id='"+UUID.randomUUID()+"'","fk_deliveries_outbox");
    }
    @Test void journalInsertChecksCannotBeMaskedByAppendOnlyUpdateGuard() {
        String prefix="insert into payment_events(id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,occurred_at,received_at,request_id,payload_hash,amount_minor,currency) values (?,?,?,?,";
        for(String tail:new String[]{
            "0,'PAYMENT_CREATED','API',now(),now(),'r','"+"a".repeat(64)+"',null,null)",
            "1,'INVALID','API',now(),now(),'r','"+"a".repeat(64)+"',null,null)",
            "1,'PAYMENT_CREATED','INVALID',now(),now(),'r','"+"a".repeat(64)+"',null,null)",
            "1,'PAYMENT_CREATED','API',now(),now(),'r','invalid',null,null)",
            "1,'PAYMENT_CREATED','API',now(),now(),'r','"+"a".repeat(64)+"',0,'EUR')",
            "1,'PAYMENT_CREATED','API',now(),now(),'r','"+"a".repeat(64)+"',1,null)",
            "1,'PAYMENT_CREATED','API',now(),now(),'r','"+"a".repeat(64)+"',1,'eur')"}) {
            assertThatThrownBy(() -> jdbc.update(prefix+tail,UUID.randomUUID(),payment,site,account)).isInstanceOf(DataIntegrityViolationException.class);
        }
        String valid="1,'PAYMENT_CREATED','API',now(),now(),'r','"+"a".repeat(64)+"',null,null)";
        jdbc.update(prefix+valid,UUID.randomUUID(),payment,site,account);
        assertThatThrownBy(() -> jdbc.update(prefix+valid,UUID.randomUUID(),payment,site,account)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_payment_event_sequence");
        assertThatThrownBy(() -> jdbc.update(prefix+valid.replace("1,","2,"),UUID.randomUUID(),payment,UUID.randomUUID(),account)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_events_ownership");
    }
    private void reject(String table,String change,String constraint) {
        assertThatThrownBy(() -> jdbc.update("update "+table+" set "+change)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }
    @Test void fakeProviderObjectsCannotReferenceMissingOrWrongAccountObjects() {
        String sql="insert into fake_provider_objects(provider_reference,provider_operation_key,object_type,internal_object_id,payment_account_id,state,created_at,updated_at) values ('fake','op','PAYMENT',?,?,'CREATED',now(),now())";
        assertThatThrownBy(() -> jdbc.update(sql,UUID.randomUUID(),account)).isInstanceOf(DataIntegrityViolationException.class);
        UUID other=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?, 'other-account', 'FAKE', 'SANDBOX', 'ACTIVE', 'fake', 'ref', now(), 0)",other);
        assertThatThrownBy(() -> jdbc.update(sql,payment,other)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(sql,payment,account);
        assertThatThrownBy(() -> jdbc.update("update fake_provider_objects set internal_object_id=?",UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        UUID refund=UUID.randomUUID();
        jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?,?,500,'EUR','PENDING','refund-op',now(),now())",refund,payment);
        String refundSql=sql.replace("'fake','op','PAYMENT'","'fake-refund','refund-op','REFUND'");
        assertThatThrownBy(() -> jdbc.update(refundSql,refund,other)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(refundSql,refund,account);
    }
    @ParameterizedTest @ValueSource(strings={"local-v1", "vault-production/autumn-2026"})
    void encryptedStoragePreservesConfiguredKeyIdentifiersExactly(String keyVersion) {
        UUID secret=UUID.randomUUID(),record=UUID.randomUUID();
        assertThatCode(() -> jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,decode('ab','hex'),decode('000000000000000000000000','hex'),?,now())",secret,site,keyVersion)).doesNotThrowAnyException();
        jdbc.update("insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,resource_type,resource_id,response_status,response_secret_state,response_secret_ciphertext,response_secret_nonce,response_secret_key_version,response_secret_expires_at,expires_at,created_at,updated_at) values (?,'s','k',?,'COMPLETED','PAYMENT',?,201,'LIVE',decode(repeat('00',16),'hex'),decode('000000000000000000000000','hex'),?,now()+interval '1 hour',now()+interval '30 days',now(),now())",record,"a".repeat(64),payment,keyVersion);
        assertThat(jdbc.queryForObject("select master_key_version from site_callback_secret_versions where id=?",String.class,secret)).isEqualTo(keyVersion);
        assertThat(jdbc.queryForObject("select response_secret_key_version from idempotency_records where id=?",String.class,record)).isEqualTo(keyVersion);
    }
    @Test void keyIdentifierBoundsAndEncryptedQuartetAreEnforced() {
        secret();
        jdbc.update("insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,resource_type,resource_id,response_status,response_secret_state,response_secret_ciphertext,response_secret_nonce,response_secret_key_version,response_secret_expires_at,expires_at,created_at,updated_at) values (?,'s','k',?,'COMPLETED','PAYMENT',?,201,'LIVE',decode(repeat('00',16),'hex'),decode('000000000000000000000000','hex'),'local-v1',now()+interval '1 hour',now()+interval '30 days',now(),now())",UUID.randomUUID(),"a".repeat(64),payment);
        for(String invalid:new String[]{"", " \t\n", "v".repeat(129)}) {
            assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set master_key_version=?",invalid)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update idempotency_records set response_secret_key_version=?",invalid)).isInstanceOf(DataIntegrityViolationException.class);
        }
        for(String field:new String[]{"response_secret_ciphertext","response_secret_nonce","response_secret_key_version","response_secret_expires_at"})
            reject("idempotency_records",field+"=null","ck_idempotency_secret");
        reject("idempotency_records","response_secret_nonce=decode('ab','hex')","ck_idempotency_secret");
        jdbc.update("update idempotency_records set response_secret_state='NONE',response_secret_ciphertext=null,response_secret_nonce=null,response_secret_key_version=null,response_secret_expires_at=null");
        assertThat(jdbc.queryForObject("select response_secret_key_version from idempotency_records",String.class)).isNull();
    }
}
