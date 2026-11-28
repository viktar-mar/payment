package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.junit.jupiter.api.Assertions.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingCustomerAdapter;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.lang.reflect.InvocationTargetException;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Standalone pure tests: no extensions, parent, sockets, Spring context or static resource setup. */
class StripeBillingClockSdkTest {
    @Test void ordinaryCustomerRejectsUnexpectedClock() throws Exception {
        var account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"billing",PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
        var site=new SiteId(UUID.randomUUID());var customer=new CustomerId(UUID.randomUUID());
        var remote=new com.stripe.model.Customer();remote.setId("cus_owned");remote.setObject("customer");remote.setLivemode(false);
        remote.setMetadata(Map.of("account_id",account.id().value().toString(),"site_id",site.value().toString(),"customer_id",customer.value().toString()));
        remote.setTestClock("clock_unexpected");
        var check=StripeBillingCustomerAdapter.class.getDeclaredMethod("requireCustomer",com.stripe.model.Customer.class,PaymentAccount.class,SiteId.class,CustomerId.class);
        check.setAccessible(true);
        assertThrows(InvocationTargetException.class,()->check.invoke(null,remote,account,site,customer));
    }    @Test void originalClockPostSingleReadyGetAndOwnedCustomerUseOfficialSdk(){
        var f=new StripeBillingInertSdkFixture();var ready=f.createReady();
        assertEquals(2,f.http.calls.size());
        var post=f.http.calls.getFirst();var get=f.http.calls.getLast();
        assertEquals("POST",post.method().name());assertEquals("https://api.stripe.com/v1/test_helpers/test_clocks",post.url().toString());
        assertEquals(Map.of("frozen_time",1700000000L,"name",f.owner.name()),post.params());
        assertTrue(post.content().stringContent().contains("frozen_time=1700000000"));
        assertEquals(List.of(f.owner.createKey()),post.headers().map().get("Idempotency-Key"));
        assertEquals("GET",get.method().name());assertEquals("https://api.stripe.com/v1/test_helpers/test_clocks/clock_owned",get.url().toString());
        assertEquals(1800000000L,ready.providerCreated());assertEquals(1700000000L,ready.frozenTime());
        assertEquals(ready,f.clockAdapter().createFresh(f.account,f.owner));assertEquals(2,f.http.calls.size());
        f.http.reply(f.customerJson("clock_owned"));
        var binding=f.customerAdapter().ensureOwnedCustomer(f.account,f.owner);
        assertEquals("cus_owned",binding.providerReference());
        var customer=f.http.calls.getLast();assertEquals("https://api.stripe.com/v1/customers",customer.url().toString());
        assertEquals("clock_owned",customer.params().get("test_clock"));
        assertTrue(customer.content().stringContent().contains("test_clock=clock_owned"));
        assertEquals(Set.of("metadata","test_clock"),customer.params().keySet());
        assertEquals(List.of(f.owner.customerKey()),customer.headers().map().get("Idempotency-Key"));
        for(var request:f.http.calls){assertEquals(0,request.options().getMaxNetworkRetries());assertEquals(List.of("2026-09-30.endive"),request.headers().map().get("Stripe-Version"));assertNull(request.options().getStripeAccount());}
        assertEquals(binding,f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));assertEquals(3,f.http.calls.size());
    }
    @Test void ordinarySdkRequestAndOriginalFingerprintStayUnchanged(){
        var f=new StripeBillingInertSdkFixture();f.http.reply(f.customerJson(null));
        assertEquals("cus_owned",f.customerAdapter().ensureCustomer(f.account,f.site,f.customer).providerReference());
        assertEquals(Set.of("metadata"),f.http.calls.getFirst().params().keySet());
        var operation=f.rows.values().iterator().next();
        assertEquals(StripeRequestFingerprint.of(f.digest,f.account,"CUSTOMER_CREATE",f.site.value().toString(),f.customer.value().toString()),operation.fingerprint());
    }
    @Test void ordinaryResponseUnexpectedClockBecomesUnknownWithoutBinding(){
        var f=new StripeBillingInertSdkFixture();f.http.reply(f.customerJson("clock_foreign"));
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureCustomer(f.account,f.site,f.customer));
        assertEquals(ProviderOperation.Status.UNKNOWN,f.rows.values().iterator().next().status());assertEquals(0,f.binds);
    }
    @Test void ordinaryAndEventRecoveryRefuseIntentBeforeVerificationAndHttp(){
        var f=new StripeBillingInertSdkFixture();f.admit();
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureCustomer(f.account,f.site,f.customer));
        assertThrows(RuntimeException.class,()->f.customerAdapter().recoverCustomer(f.account,f.site,f.customer,UUID.randomUUID(),"evt_owned"));
        assertEquals(0,f.verifications);assertTrue(f.rows.isEmpty());assertTrue(f.http.calls.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","500","malformed","live","deleted","name","frozen","advancing","internal_failure","unknown","target","id","object","missing_created"})
    void invalidClockPostStopsAndNeverRedispatches(String bad){
        var f=new StripeBillingInertSdkFixture();f.admit();replyBadClock(f,bad);
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));
        assertEquals(StripeBillingClockStore.StopReason.CLOCK_CREATE_FAILED,f.stopped);assertNull(f.ready);
        assertEquals(ProviderOperation.Status.UNKNOWN,f.rows.values().iterator().next().status());
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));assertEquals(1,f.http.calls.size());
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","500","malformed","live","deleted","name","frozen","advancing","internal_failure","unknown","target","id","object","missing_created","created_drift","expiry_drift","foreign"})
    void invalidSingleReadyReadStopsWithoutPolling(String bad){
        var f=new StripeBillingInertSdkFixture();f.admit();f.http.reply(f.clockJson());replyBadClock(f,bad);
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));
        assertEquals(StripeBillingClockStore.StopReason.CLOCK_READY_FAILED,f.stopped);assertNull(f.ready);
        assertEquals(ProviderOperation.Status.ACKNOWLEDGED,f.rows.values().iterator().next().status());
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));assertEquals(2,f.http.calls.size());
    }
    @Test void restartWithClockAckAndNoReadyHasNoGetOrPost(){
        var f=new StripeBillingInertSdkFixture();f.admit();
        var reserved=f.operations.reserve(f.account,ProviderOperation.Kind.TEST_CLOCK_CREATE,f.owner.intent(),f.owner.createKey(),f.owner.createFingerprint());
        var claimed=f.operations.claim(reserved.id(),"original",java.time.Instant.now(),java.time.Duration.ofSeconds(60)).orElseThrow();
        f.operations.acknowledge(claimed.id(),"original",claimed.version(),"clock_owned");
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));assertTrue(f.http.calls.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","500","malformed","wrong_clock","missing_clock","live","deleted"})
    void ownedCustomerUnknownNeverRedispatches(String bad){
        var f=new StripeBillingInertSdkFixture();f.createReady();
        switch(bad){case "disconnect"->f.http.disconnect();case "500"->f.http.error(500);case "malformed"->f.http.reply("{");
        case "wrong_clock"->f.http.reply(f.customerJson("clock_foreign"));case "missing_clock"->f.http.reply(f.customerJson(null));
        case "live"->f.http.reply(f.customerJson("clock_owned").replace("\"livemode\":false","\"livemode\":true"));
        case "deleted"->f.http.reply(f.customerJson("clock_owned").replace("\"livemode\":false","\"livemode\":false,\"deleted\":true"));}
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));
        assertEquals(StripeBillingClockStore.StopReason.CUSTOMER_CREATE_FAILED,f.stopped);assertEquals(0,f.binds);
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));assertEquals(3,f.http.calls.size());
    }
    @Test void unknownOwnedReceiptDoesNotEvenAskStoreToClaimAgain(){
        var f=new StripeBillingInertSdkFixture();f.createReady();var reserved=f.operations.reserveOwnedBillingCustomer(f.account,f.ready);
        var original=f.operations.claim(reserved.id(),"original",java.time.Instant.now(),java.time.Duration.ofSeconds(60)).orElseThrow();
        f.operations.unknown(original.id(),"original",original.version());int claims=f.claims;
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));
        assertEquals(claims,f.claims);assertEquals(2,f.http.calls.size());
    }
    @Test void acknowledgedCustomerBindingFailureRepairsLocallyWithoutActiveAuthorityOrVerification(){
        var f=new StripeBillingInertSdkFixture();f.createReady();f.http.reply(f.customerJson("clock_owned"));f.bindingFailure=true;
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));
        assertEquals(StripeBillingClockStore.StopReason.BINDING_FAILED,f.stopped);int verified=f.verifications,reserves=f.ownedReserves;
        assertEquals("cus_owned",f.customerAdapter().ensureOwnedCustomer(f.account,f.owner).providerReference());
        assertEquals(verified,f.verifications);assertEquals(reserves,f.ownedReserves);assertEquals(3,f.http.calls.size());
    }
    @Test void failedClockAckCannotProceedToReadyRead(){
        var f=new StripeBillingInertSdkFixture();f.admit();f.http.reply(f.clockJson());f.ackFailure=true;
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));
        assertEquals(1,f.http.calls.size());assertNull(f.ready);assertNotNull(f.stopped);
    }
    @Test void fixedFingerprintVectorsRetainOrdinaryBytesAndVersionBothOwnedTags(){
        var f=new StripeBillingInertSdkFixture();f.admit();
        assertEquals("3e1587f67945cadde96c363a190abb1744fa50db12c4da67ecfb626f714b13ba",StripeRequestFingerprint.of(f.digest,f.account,"CUSTOMER_CREATE",f.site.value().toString(),f.customer.value().toString()));
        assertEquals("7fce9a3c95483dd180cb2dfd472e7502073a6f5ab08ef66152e064781a12bb15",f.owner.createFingerprint());
        assertEquals("86c7306a615599b7bce9591263ec8cc10f860f5d81bc65a697b93ec191e053f9",StripeBillingClockIdentity.customerFingerprint(f.digest,f.owner,UUID.fromString("70000000-0000-0000-0000-000000000007"),"clock_owned"));
    }
    @Test void concurrentClockCallsAndCustomerCallsEachDispatchOnlyOneOriginalPost() throws Exception {
        var f=new StripeBillingInertSdkFixture();f.admit();f.http.reply(f.clockJson());f.http.reply(f.clockJson());
        race(()->f.clockAdapter().createFresh(f.account,f.owner));assertEquals(2,f.http.calls.size());assertNull(f.stopped);
        f.http.reply(f.customerJson("clock_owned"));race(()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));
        assertEquals(3,f.http.calls.size());assertNull(f.stopped);
        assertEquals(1,f.http.calls.stream().filter(r->r.method().name().equals("POST")&&r.url().getPath().equals("/v1/customers")).count());
    }
    @Test void activeAuthorityRevokedAfterClockPostCannotAckOrReadReady(){
        var f=new StripeBillingInertSdkFixture();f.admit();f.http.reply(f.clockJson());f.http.afterResponse=()->f.active=false;
        assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));assertEquals(1,f.http.calls.size());assertNull(f.ready);
        assertNotEquals(ProviderOperation.Status.ACKNOWLEDGED,f.rows.values().iterator().next().status());assertNotNull(f.stopped);
    }
    @Test void activeAuthorityRevokedAfterCustomerPostCannotAckOrBind(){
        var f=new StripeBillingInertSdkFixture();f.createReady();f.http.reply(f.customerJson("clock_owned"));f.http.afterResponse=()->f.active=false;
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));assertEquals(0,f.binds);assertEquals(3,f.http.calls.size());assertNotNull(f.stopped);
    }
    @Test void failedCustomerAckCannotBindOrRedispatch(){
        var f=new StripeBillingInertSdkFixture();f.createReady();f.http.reply(f.customerJson("clock_owned"));f.ackFailure=true;
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));assertEquals(0,f.binds);
        assertThrows(RuntimeException.class,()->f.customerAdapter().ensureOwnedCustomer(f.account,f.owner));assertEquals(3,f.http.calls.size());
    }
    private static void race(Runnable call)throws Exception{
        var start=new java.util.concurrent.CyclicBarrier(2);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Boolean> attempt=()->{start.await(5,java.util.concurrent.TimeUnit.SECONDS);try{call.run();return true;}catch(RuntimeException conflict){return false;}};
            var first=threads.submit(attempt);var second=threads.submit(attempt);
            boolean a=first.get(5,java.util.concurrent.TimeUnit.SECONDS),b=second.get(5,java.util.concurrent.TimeUnit.SECONDS);assertTrue(a||b);
        }
    }
    @Test void duplicateInAckToReadyGapCannotGetOrPostAndDoesNotStopOriginalWinner()throws Exception{
        var f=new StripeBillingInertSdkFixture();f.admit();f.http.reply(f.clockJson());f.http.reply(f.clockJson());
        var enteredGet=new java.util.concurrent.CountDownLatch(1);var releaseGet=new java.util.concurrent.CountDownLatch(1);
        // POST returns, ledger ACK commits, then the original exact GET blocks in the inert transport.
        f.http.afterResponse=()->f.http.afterResponse=()->{enteredGet.countDown();try{assertTrue(releaseGet.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}};
        try(var thread=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var original=thread.submit(()->f.clockAdapter().createFresh(f.account,f.owner));
            try{
                assertTrue(enteredGet.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNull(f.ready);
                assertEquals(ProviderOperation.Status.ACKNOWLEDGED,f.rows.values().iterator().next().status());
                assertThrows(RuntimeException.class,()->f.clockAdapter().createFresh(f.account,f.owner));
                assertEquals(2,f.http.calls.size());assertNull(f.stopped);
            }finally{releaseGet.countDown();}
            assertEquals("clock_owned",original.get(5,java.util.concurrent.TimeUnit.SECONDS).reference());
        }
        assertEquals(2,f.http.calls.size());assertNull(f.stopped);assertNotNull(f.ready);
    }
    private static void replyBadClock(StripeBillingInertSdkFixture f,String bad){
        if(bad.equals("disconnect")){f.http.disconnect();return;}if(bad.equals("500")){f.http.error(500);return;}
        String json=f.clockJson();json=switch(bad){
            case "malformed"->"{";case "live"->json.replace("\"livemode\":false","\"livemode\":true");
            case "deleted"->json.replace("\"livemode\":false","\"livemode\":false,\"deleted\":true");
            case "name"->json.replace(f.owner.name(),"foreign");case "frozen"->json.replace("1700000000","1700000001");
            case "advancing","internal_failure","unknown"->json.replace("\"status\":\"ready\"","\"status\":\""+bad+"\"");
            case "target"->json.replace("\"status_details\":{}","\"status_details\":{\"advancing\":{\"target_frozen_time\":1700000100}}");
            case "foreign"->json.replace("clock_owned","clock_foreign");case "id"->json.replace("clock_owned","bad_clock");case "object"->json.replace("test_helpers.test_clock","customer");
            case "missing_created"->json.replace("\"created\":1800000000,","");case "created_drift"->json.replace("1800000000","1800000001");
            case "expiry_drift"->json.replace("1801000000","1801000001");default->throw new AssertionError(bad);};f.http.reply(json);
    }
}
