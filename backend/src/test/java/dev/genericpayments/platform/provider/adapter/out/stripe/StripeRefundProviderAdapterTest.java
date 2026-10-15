package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;

@TestPropertySource(properties={"payment.stripe.sandbox.enabled=true","payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","payment.stripe.sandbox.webhook-secret=whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCC"})
@Import(StripePaymentProviderAdapterTest.FixtureConfiguration.class)
@ActiveProfiles("test")
class StripeRefundProviderAdapterTest extends CheckoutTestFixture {
    @Autowired StripePaymentProviderAdapter provider;
    @Autowired StripePaymentProviderAdapterTest.FixtureFactory factory;
    @Autowired SaveRefundPort saveRefunds;
    @Autowired LoadRefundPort refunds;
    StripeHttpFixture http;PaymentAccount stripe;Payment p;Refund r;
    @BeforeEach void fixture() throws Exception {
        http=new StripeHttpFixture();factory.http=http;factory.readTimeout=5000;
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        stripe=PaymentAccount.create(account.id(),"test-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
        p=save.save(payment().withProviderPaymentReference("pi_paid",INITIAL_TIME).succeeded(INITIAL_TIME));
        var id=new RefundId(UUID.randomUUID());r=saveRefunds.insert(Refund.request(id,p,Money.of(700,"EUR"),ProviderOperationKeys.refund(id),"REQUESTED_BY_CUSTOMER","internal note",INITIAL_TIME)).value();
    }
    @AfterEach void close(){http.close();factory.readTimeout=5000;}
    void identity(){http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_expected\",\"object\":\"account\"}");}
    void parent(){http.enqueue("GET","/v1/payment_intents/pi_paid",200,"{\"id\":\"pi_paid\",\"object\":\"payment_intent\",\"livemode\":false,\"amount\":2000,\"amount_received\":2000,\"currency\":\"eur\",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"status\":\"succeeded\",\"latest_charge\":\"ch_paid\"}");http.enqueue("GET","/v1/charges/ch_paid",200,"{\"id\":\"ch_paid\",\"object\":\"charge\",\"livemode\":false,\"payment_intent\":\"pi_paid\",\"amount\":2000,\"amount_captured\":2000,\"currency\":\"eur\",\"paid\":true,\"captured\":true,\"status\":\"succeeded\",\"payment_method_details\":{\"type\":\"card\"}}");}
    String json(String status,long amount,String charge){return "{\"id\":\"re_owned\",\"object\":\"refund\",\"amount\":"+amount+",\"currency\":\"eur\",\"payment_intent\":\"pi_paid\",\"charge\":\""+charge+"\",\"status\":\""+status+"\",\"metadata\":{\"refund_id\":\""+r.id().value()+"\",\"payment_id\":\""+p.id().value()+"\"}}";}
    ProviderRefundRequest request(){return new ProviderRefundRequest(stripe,p,r,ProviderOperationKeys.refund(r.id()));}
    void create(){identity();parent();http.enqueue("POST","/v1/refunds",200,json("succeeded",700,"ch_paid"));}
    @Test void createsIndividualPartialRefundWithOriginalOperationAndAllowlistedReason(){
        create();assertThat(provider.requestRefund(request())).isEqualTo(new ProviderResult.Accepted("re_owned",false));
        identity();assertThat(provider.requestRefund(request())).isEqualTo(new ProviderResult.Accepted("re_owned",false));
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->{assertThat(c.path()).isEqualTo("/v1/refunds");assertThat(c.header("Idempotency-Key")).isEqualTo("refund_"+r.id().value());assertThat(c.form()).containsEntry("amount","700").containsEntry("payment_intent","pi_paid").containsEntry("reason","requested_by_customer").containsEntry("metadata[refund_id]",r.id().value().toString()).doesNotContainValue("internal note");});
        assertThat(count("payment_events")).isZero();assertThat(load.findById(site,p.id()).orElseThrow().refundedAmountMinor()).isZero();
    }
    @Test void wrongAccountIdentityStopsBeforeReceiptAndMutation(){
        http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_foreign\",\"object\":\"account\"}");
        assertThatThrownBy(()->provider.requestRefund(request())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).singleElement().satisfies(c->assertThat(c.path()).isEqualTo("/v1/account"));assertThat(count("provider_operations")).isZero();
    }
    @Test void directFakeAndInvalidAliasCannotReachNoopVerifierOrReceipts(){
        var invalid=PaymentAccount.create(account.id(),"test-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","bad-alias");
        for(var a:List.of(account,invalid))assertThatThrownBy(()->provider.requestRefund(new ProviderRefundRequest(a,p,r,r.providerOperationKey()))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).isEmpty();assertThat(count("provider_operations")).isZero();
    }
    @Test void differentRefundAmountCannotReuseExistingKey(){
        create();provider.requestRefund(request());jdbc.update("update refunds set amount_minor=701,version=version+1 where id=?",r.id().value());r=refunds.findById(site,r.id()).orElseThrow().value();identity();
        assertThatThrownBy(()->provider.requestRefund(request())).isInstanceOf(RuntimeException.class);assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"pending","requires_action","future_status"})
    void pendingOrUnknownIndividualRefundCannotBecomeSuccessful(String status){
        create();provider.requestRefund(request());r=saveRefunds.save(new LoadRefundPort.StoredRefund(r.withProviderRefundReference("re_owned",INITIAL_TIME),0)).value();
        identity();http.enqueue("GET","/v1/refunds/re_owned",200,json(status,700,"ch_paid"));parent();
        var result=provider.retrieveRefund(ProviderRefundLookup.forRefund(stripe,p.id(),r.id(),"re_owned"));
        assertThat(result.status()).isEqualTo(status.equals("future_status")?ProviderState.Status.UNKNOWN:ProviderState.Status.PROCESSING);
        assertThat(result.evidence().money()).isEqualTo(Money.of(700,"EUR"));assertThat(count("payment_events")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"amount","currency","charge","intent","metadata"})
    void individualRefundMismatchCannotBecomeSettlementEvidence(String field){
        create();provider.requestRefund(request());r=saveRefunds.save(new LoadRefundPort.StoredRefund(r.withProviderRefundReference("re_owned",INITIAL_TIME),0)).value();
        String remote=json("succeeded",700,"ch_paid");remote=switch(field){case "amount"->remote.replace("\"amount\":700","\"amount\":2000");case "currency"->remote.replace("\"eur\"","\"usd\"");case "charge"->remote.replace("\"ch_paid\"","\"ch_foreign\"");case "intent"->remote.replace("\"pi_paid\"","\"pi_foreign\"");default->remote.replace(r.id().value().toString(),UUID.randomUUID().toString());};
        identity();http.enqueue("GET","/v1/refunds/re_owned",200,remote);parent();
        assertThatThrownBy(()->provider.retrieveRefund(ProviderRefundLookup.forRefund(stripe,p.id(),r.id(),"re_owned"))).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_events")).isZero();assertThat(load.findById(site,p.id()).orElseThrow().refundedAmountMinor()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"500","disconnect","timeout","invalid-response"})
    void ambiguousPostNeverReleasesReservationOrCreatesAReplacementKey(String failure){
        identity();parent();
        switch(failure){case "500"->http.enqueue("POST","/v1/refunds",500,"{\"error\":{\"type\":\"api_error\",\"message\":\"private-refund-marker\"}}");case "disconnect"->http.disconnectNext("POST","/v1/refunds");case "timeout"->{factory.readTimeout=100;http.enqueue("POST","/v1/refunds",200,json("succeeded",700,"ch_paid"),Map.of(),300);}default->http.enqueue("POST","/v1/refunds",200,json("succeeded",701,"ch_paid"));}
        assertThat(provider.requestRefund(request())).isEqualTo(new ProviderResult.Unknown(null));
        var first=jdbc.queryForMap("select operation_key,fingerprint from provider_operations");assertThat(jdbc.queryForObject("select status from provider_operations",String.class)).isEqualTo("UNKNOWN");
        factory.readTimeout=5000;create();assertThat(provider.requestRefund(request()).reference()).isEqualTo("re_owned");
        assertThat(jdbc.queryForMap("select operation_key,fingerprint from provider_operations")).isEqualTo(first);assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(2).allSatisfy(c->assertThat(c.header("Idempotency-Key")).isEqualTo(r.providerOperationKey()));
    }
    @Test void expiredUnknownWindowCannotDispatchAgain(){
        identity();parent();http.disconnectNext("POST","/v1/refunds");provider.requestRefund(request());jdbc.update("update provider_operations set first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour'");
        identity();parent();assertThat(provider.requestRefund(request())).isEqualTo(new ProviderResult.Unknown(null));assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"OTHER","internal_reason"}) @NullSource
    void unsupportedLocalReasonIsOmittedWithoutSendingTechnicalNote(String reason){
        jdbc.update("update refunds set reason_code=? where id=?",reason,r.id().value());r=refunds.findById(site,r.id()).orElseThrow().value();
        create();assertThat(provider.requestRefund(request()).reference()).isEqualTo("re_owned");
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->assertThat(c.form()).doesNotContainKeys("reason").doesNotContainValue("internal note"));
    }
    @Test void acknowledgedRefundStillRejectsWrongCurrentServerAccount(){
        create();provider.requestRefund(request());http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_foreign\",\"object\":\"account\"}");
        assertThatThrownBy(()->provider.requestRefund(request())).isInstanceOf(RuntimeException.class);assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
    }
    @ParameterizedTest @CsvSource({"DUPLICATE,duplicate","FRAUDULENT,fraudulent"})
    void allowlistedReasonMapsToExactStripeValue(String local,String expected){
        jdbc.update("update refunds set reason_code=? where id=?",local,r.id().value());r=refunds.findById(site,r.id()).orElseThrow().value();create();provider.requestRefund(request());
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->assertThat(c.form()).containsEntry("reason",expected));
    }
    @Test void refundFingerprintMatchesIndependentPersistedIdentityVector(){
        var fixed=Payment.dynamic(new PaymentId(UUID.fromString("40000000-0000-0000-0000-000000000001")),site,account.id(),"fixed-order",Money.of(2000,"EUR"),dev.genericpayments.platform.catalog.domain.TaxMode.NONE,Map.of(),INITIAL_TIME).withProviderPaymentReference("pi_paid",INITIAL_TIME).succeeded(INITIAL_TIME);
        var id=new RefundId(UUID.fromString("50000000-0000-0000-0000-000000000001"));var refund=Refund.request(id,fixed,Money.of(700,"EUR"),ProviderOperationKeys.refund(id),"REQUESTED_BY_CUSTOMER",null,INITIAL_TIME);
        StripePaymentProviderAdapter target=org.springframework.test.util.AopTestUtils.getTargetObject(provider);
        assertThat(target.refundFingerprint(stripe,fixed,refund)).isEqualTo("407e0de2bf49a3ec078b05a8bc4569207a6e2dbbc158f53a54682b9c5a352f5f");
    }
    @Test void fullIndividualRefundUsesExactFullAmountWithoutSynchronousSettlement(){
        jdbc.update("update refunds set amount_minor=2000 where id=?",r.id().value());r=refunds.findById(site,r.id()).orElseThrow().value();identity();parent();http.enqueue("POST","/v1/refunds",200,json("succeeded",2000,"ch_paid"));
        assertThat(provider.requestRefund(request())).isEqualTo(new ProviderResult.Accepted("re_owned",false));assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->assertThat(c.form()).containsEntry("amount","2000"));assertThat(load.findById(site,p.id()).orElseThrow().refundedAmountMinor()).isZero();
    }
    @Test void directRefundRetrieveRejectsInvalidBindingsAndWrongCurrentAccountWithoutMutation(){
        create();provider.requestRefund(request());r=saveRefunds.save(new LoadRefundPort.StoredRefund(r.withProviderRefundReference("re_owned",INITIAL_TIME),0)).value();int before=http.calls().size();
        var invalid=PaymentAccount.create(account.id(),"test-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","bad-alias");
        for(var a:List.of(account,invalid))assertThatThrownBy(()->provider.retrieveRefund(ProviderRefundLookup.forRefund(a,p.id(),r.id(),"re_owned"))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).hasSize(before);http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_foreign\",\"object\":\"account\"}");
        assertThatThrownBy(()->provider.retrieveRefund(ProviderRefundLookup.forRefund(stripe,p.id(),r.id(),"re_owned"))).isInstanceOf(RuntimeException.class);assertThat(http.calls().subList(before,http.calls().size())).extracting(c->c.path()).containsExactly("/v1/account");assertThat(count("provider_operations")).isEqualTo(1);assertThat(count("payment_events")).isZero();
    }
    @Autowired dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore operations;
    @ParameterizedTest @ValueSource(booleans={true,false})
    void staleRefundNetworkCompletionCannotAcknowledgeOrUnknownNewerClaim(boolean success) throws Exception {
        identity();parent();http.enqueue("POST","/v1/refunds",success?200:500,success?json("succeeded",700,"ch_paid"):"{\"error\":{\"type\":\"api_error\",\"message\":\"private-refund-marker\"}}",Map.of(),1000);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->catchThrowable(()->provider.requestRefund(request())));long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();while(http.calls().stream().noneMatch(c->c.method().equals("POST"))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);jdbc.update("update provider_operations set lease_expires_at=clock_timestamp()-interval '1 microsecond'");var receipt=operations.find(account.id(),r.providerOperationKey()).orElseThrow();var newer=operations.claim(receipt.id(),"new-refund-owner",java.time.Instant.now(),java.time.Duration.ofSeconds(60)).orElseThrow();
            assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);assertThat(operations.find(account.id(),r.providerOperationKey())).contains(newer);assertThat(count("payment_events")).isZero();
        }
    }
}
