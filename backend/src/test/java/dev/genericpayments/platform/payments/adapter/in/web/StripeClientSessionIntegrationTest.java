package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.support.StripeHttpFixture;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripePaymentProviderAdapterTest;
import dev.genericpayments.platform.payments.application.model.CheckoutCreation;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.adapter.out.persistence.CheckoutReturnUrlPersistenceAdapter;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.net.URI;
import java.util.*;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@TestPropertySource(properties = {
    "payment.stripe.sandbox.enabled=true",
    "payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
    "payment.stripe.sandbox.webhook-secret=whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"})
@Import(StripePaymentProviderAdapterTest.FixtureConfiguration.class)
class StripeClientSessionIntegrationTest extends CheckoutTestFixture {
    @Autowired WebApplicationContext context;
    @Autowired CreateCatalogCheckoutUseCase checkouts;
    @Autowired StripePaymentProviderAdapterTest.FixtureFactory factory;
    @Autowired CheckoutReturnUrlPersistenceAdapter returnUrls;
    @Autowired ClientPaymentCapabilityPort capabilities;
    @Autowired LoadPaymentAccountPort accounts;
    @Autowired dev.genericpayments.platform.donations.application.port.in.CreateDonationCheckoutUseCase donationCheckouts;
    @Autowired dev.genericpayments.platform.donations.application.port.out.DonationCampaignStore campaigns;
    @Autowired IssueHandoffUseCase issueHandoff;
    @Autowired ExchangeHandoffUseCase exchangeHandoff;
    @Autowired dev.genericpayments.platform.customers.application.port.out.CustomerStore customers;
    @Autowired PaymentProviderRegistry providers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    MockMvc mvc;
    StripeHttpFixture http;
    final String secret = "pi_fixture_secret_" + UUID.randomUUID();
    @BeforeEach void web() throws IOException { mvc = new AdminApiClient(context).mvc; http=new StripeHttpFixture(); factory.http=http; }
    @AfterEach void closeHttp() { http.close(); }

    CheckoutCreation stripeCheckout() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        identity();
        http.enqueue("POST","/v1/payment_intents",200,intent("requires_payment_method"));
        return checkouts.create(new CreateCatalogCheckoutCommand("test-site","lifetime",URI.create("https://shop.test"),null,"create-"+UUID.randomUUID(),"request"));
    }
    void policy() { returnUrls.save(site,URI.create("https://shop.test"),URI.create("https://shop.test/payment-return")); }
    String path(CheckoutCreation c) { return "/api/v1/public/checkouts/"+c.view().checkoutId()+"/client-session"; }
    MockHttpServletRequestBuilder session(CheckoutCreation c,String key) {
        return post(path(c)).header("Origin","https://shop.test").header("Authorization","Bearer "+c.checkoutToken()).header("Idempotency-Key",key)
            .with(r -> { r.setRemoteAddr(UUID.randomUUID().toString());return r; });
    }
    void identity() { http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_expected\",\"object\":\"account\"}"); }
    String intent(String status) {
        return "{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"livemode\":false,\"status\":\""+status+"\",\"amount\":4999,\"currency\":\"eur\",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"client_secret\":\""+secret+"\"}";
    }
    void snapshot(String status) { identity();http.enqueue("GET","/v1/payment_intents/pi_fixture",200,intent(status)); }

    @Test void issuesEphemeralSecretAndReusesAttemptAcrossKeysWithoutSecretStorage() throws Exception {
        var c=stripeCheckout();policy(); snapshot("requires_payment_method");snapshot("requires_payment_method");
        var response=mvc.perform(session(c,"first")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.checkout_id").value(c.view().checkoutId().toString()))
            .andExpect(jsonPath("$.payment_id").value(c.view().paymentId().toString()))
            .andExpect(jsonPath("$.provider_type").value("STRIPE"))
            .andExpect(jsonPath("$.confirmation_mode").value("CLIENT_PROVIDER"))
            .andExpect(jsonPath("$.publishable_key").value("pk_test_"+"B".repeat(32)))
            .andExpect(jsonPath("$.client_secret").value(secret))
            .andExpect(jsonPath("$.return_url").value("https://shop.test/payment-return"))
            .andExpect(jsonPath("$.expires_at").value(c.view().expiresAt().toString()))
            .andReturn().getResponse().getContentAsString();
        String attempt=com.jayway.jsonpath.JsonPath.read(response,"$.attempt_id");
        for(var key:List.of("first","second")) {
            snapshot("requires_action");snapshot("requires_action");
            mvc.perform(session(c,key)).andExpect(status().isOk()).andExpect(jsonPath("$.attempt_id").value(attempt));
        }
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(count("payment_client_attempts")).isEqualTo(1);
        assertThat(count("provider_charge_receipts")).isZero();
        assertThat(jdbc.queryForList("select resource_type,response_secret_state from idempotency_records where scope like '%client-session%'")).hasSize(2)
            .allSatisfy(row -> {assertThat(row.get("resource_type")).isEqualTo("ATTEMPT");assertThat(row.get("response_secret_state")).isEqualTo("NONE");});
        for(var table:List.of("idempotency_records","payment_events","payment_attempts","payment_client_attempts","provider_operations"))
            assertThat(jdbc.queryForList("select row_to_json(t)::text as value from "+table+" t").toString()).doesNotContain(secret);
        assertThat(http.calls().stream().filter(call -> call.method().equals("POST"))).hasSize(1);
    }

    @Test void authorityHeadersAndEmptyBodyAreStrictBeforeProviderLookup() throws Exception {
        var c=stripeCheckout();int calls=http.calls().size();
        for(var header:List.of("Authorization","Idempotency-Key","Origin")) {
            // Construct omissions independently to avoid the request builder's additive header semantics.
            var request=post(path(c));
            if(!header.equals("Authorization"))request.header("Authorization","Bearer "+c.checkoutToken());
            if(!header.equals("Idempotency-Key"))request.header("Idempotency-Key","missing");
            if(!header.equals("Origin"))request.header("Origin","https://shop.test");
            mvc.perform(request).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.client_secret").doesNotExist());
            mvc.perform(session(c,"duplicate").header(header,header.equals("Origin")?"https://shop.test":header.equals("Authorization")?"Bearer "+c.checkoutToken():"other"))
                .andExpect(status().is4xxClientError());
        }
        mvc.perform(session(c,"body").contentType("application/json").content("{\"customer_id\":\""+secret+"\"}"))
            .andExpect(status().isBadRequest()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(secret));
        mvc.perform(post(path(c)).header("Origin","https://shop.test").header("Idempotency-Key","wrong").header("Authorization","Bearer "+"A".repeat(43)))
            .andExpect(status().isUnauthorized());
        assertThat(http.calls()).hasSize(calls);assertThat(count("payment_attempts")).isZero();
    }

    @Test void expiredDisabledAndCompletedPaymentsDoNotIssueCapabilities() throws Exception {
        var c=stripeCheckout();int calls=http.calls().size();
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(24)));
        mvc.perform(session(c,"expired")).andExpect(status().isGone());TestClock.set(INITIAL_TIME);
        jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",account.id().value());
        mvc.perform(session(c,"disabled")).andExpect(status().isUnprocessableEntity());
        jdbc.update("update payment_accounts set account_status='ACTIVE' where id=?",account.id().value());
        jdbc.update("update payments set status='SUCCEEDED' where id=?",c.view().paymentId());
        mvc.perform(session(c,"complete")).andExpect(status().isConflict());
        assertThat(count("payment_attempts")).isZero();assertThat(http.calls()).hasSize(calls);
    }

    @Test void stripeServerConfirmRefusesBeforeAttemptJournalOrProviderWork() throws Exception {
        var c=stripeCheckout();var before=jdbc.queryForList("select * from payment_events");int calls=http.calls().size();
        mvc.perform(post("/api/v1/public/checkouts/"+c.view().checkoutId()+"/confirm").header("Origin","https://shop.test")
            .header("Authorization","Bearer "+c.checkoutToken()).header("Idempotency-Key","same")
            .contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAYMENT_CAPABILITY_UNAVAILABLE"));
        assertThat(count("payment_attempts")).isZero();assertThat(jdbc.queryForList("select * from payment_events")).isEqualTo(before);assertThat(http.calls()).hasSize(calls);
    }

    @Test void clientCapabilityNeverExposesItsSecretThroughToString() {
        var capability=new ClientPaymentCapability("pk_test_"+"B".repeat(32),secret);
        assertThat(capability.toString()).isEqualTo("ClientPaymentCapability[redacted]");
        assertThat(capability.clientSecret()).isEqualTo(secret);
        var session=new ClientPaymentSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),capability,INITIAL_TIME,URI.create("https://shop.test/payment-return"));
        assertThat(session.toString()).doesNotContain(secret);
        assertThat(PublicCheckoutDtos.clientSession(session).toString()).doesNotContain(secret);
        assertThat(new PrepareClientPaymentCommand(UUID.randomUUID(),URI.create("https://shop.test"),secret,secret,secret).toString()).doesNotContain(secret);
        assertThat(new PublicCheckoutDtos.CheckoutResponse(UUID.randomUUID(),UUID.randomUUID(),1,"EUR",null,"STRIPE","CLIENT_PROVIDER",Map.of("secret",secret),"CREATED",INITIAL_TIME).toString()).doesNotContain(secret);
    }

    @Test void returnUrlPolicyIsExactAndRefusesUnsafeConfiguration() throws Exception {
        var c=stripeCheckout();int calls=http.calls().size();
        mvc.perform(session(c,"no-policy")).andExpect(status().isUnprocessableEntity());
        for(var url:List.of("https://evil.test/payment-return","https://user@shop.test/return","https://shop.test/return#fragment",
                "https://shop.test/%0aevil","https://shop.test/%250aevil","https://shop.test/%C2%85evil","http://shop.test/return")) {
            assertThatThrownBy(() -> returnUrls.save(site,URI.create("https://shop.test"),URI.create(url)))
                .isInstanceOf(PaymentApplicationException.class);
        }
        assertThat(count("checkout_return_urls")).isZero();assertThat(http.calls()).hasSize(calls);
        policy();assertThat(returnUrls.find(site,URI.create("https://shop.test"))).contains(URI.create("https://shop.test/payment-return"));
        assertThat(returnUrls.find(site,URI.create("https://other.test"))).isEmpty();
    }

    @Test void unsupportedAndProcessingSnapshotsNeverReserveOrCreateAnotherIntent() throws Exception {
        var c=stripeCheckout();policy();var receipt=jdbc.queryForList("select * from provider_operations");
        for(var status:List.of("requires_capture","new_status","processing")) {
            snapshot(status);
            mvc.perform(session(c,"unsupported-"+status)).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.client_secret").doesNotExist());
        }
        assertThat(count("payment_attempts")).isZero();assertThat(count("provider_charge_receipts")).isZero();
        assertThat(jdbc.queryForList("select * from provider_operations")).isEqualTo(receipt);
        assertThat(http.calls().stream().filter(call -> call.method().equals("POST"))).hasSize(1);
    }

    @Test void directCapabilityAdapterVerifiesAccountAndRefusesUnsupportedStatusWithoutNewOperation() {
        var c=stripeCheckout();var p=load.findById(site,new dev.genericpayments.platform.payments.domain.PaymentId(c.view().paymentId())).orElseThrow();
        var original=accounts.findById(account.id()).orElseThrow();var receipts=jdbc.queryForList("select * from provider_operations");int calls=http.calls().size();
        http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        assertThatThrownBy(() -> capabilities.retrieve(original,p)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls().subList(calls,http.calls().size())).singleElement().satisfies(call -> assertThat(call.path()).isEqualTo("/v1/account"));
        snapshot("requires_capture");
        assertThatThrownBy(() -> capabilities.retrieve(original,p)).isInstanceOf(PaymentApplicationException.class)
            .satisfies(error -> assertThat(((PaymentApplicationException)error).code()).isEqualTo("PAYMENT_CAPABILITY_UNAVAILABLE"));
        assertThat(jdbc.queryForList("select * from provider_operations")).isEqualTo(receipts);
        assertThat(http.calls().stream().filter(call -> call.method().equals("POST"))).hasSize(1);
    }

    @Test void changedBindingAndMalformedCustomerBaselineRefuseBeforeProviderWork() throws Exception {
        var c=stripeCheckout();policy();snapshot("requires_payment_method");snapshot("requires_payment_method");
        mvc.perform(session(c,"first")).andExpect(status().isOk());int calls=http.calls().size();
        jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());
        mvc.perform(session(c,"changed")).andExpect(status().isConflict());
        jdbc.update("update payment_accounts set external_account_id='acct_expected' where id=?",account.id().value());
        jdbc.execute("alter table payment_client_attempts disable trigger client_attempt_baseline_immutable");
        try { jdbc.update("update payment_client_attempts set customer_id=?",UUID.randomUUID()); }
        finally { jdbc.execute("alter table payment_client_attempts enable trigger client_attempt_baseline_immutable"); }
        mvc.perform(session(c,"bad-customer")).andExpect(status().isConflict());
        assertThat(http.calls()).hasSize(calls);assertThat(count("payment_attempts")).isEqualTo(1);
    }

    @Test void preflightAndCanonicalRoutePolicyUseConfirmAdmission() throws Exception {
        var c=stripeCheckout();
        mvc.perform(options(path(c)).header("Origin","https://shop.test").header("Access-Control-Request-Method","POST")
            .header("Access-Control-Request-Headers","authorization,idempotency-key"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://shop.test"))
            .andExpect(header().string("Access-Control-Allow-Methods","POST, OPTIONS"));
        for(var alias:List.of(path(c)+"/",path(c)+";x=y",path(c).replace("client-session","%63lient-session"),path(c).replace("/client-session","//client-session")))
            mvc.perform(post(URI.create(alias)).header("Origin","https://shop.test").header("Authorization","Bearer "+c.checkoutToken()).header("Idempotency-Key","alias"))
                .andExpect(status().is4xxClientError());
    }

    @Test void wrongPaymentTokenReadOnlyScopeAndForeignOriginNeverReachProvider() throws Exception {
        var other=checkouts.create(new CreateCatalogCheckoutCommand("test-site","lifetime",URI.create("https://shop.test"),null,"other-checkout","request"));
        var c=stripeCheckout();int calls=http.calls().size();
        mvc.perform(post(path(c)).header("Origin","https://shop.test").header("Authorization","Bearer "+other.checkoutToken()).header("Idempotency-Key","wrong-payment"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(path(c)).header("Origin","https://evil.test").header("Authorization","Bearer "+c.checkoutToken()).header("Idempotency-Key","wrong-origin"))
            .andExpect(status().isForbidden());
        jdbc.update("update checkout_sessions set scopes='[\"read\"]'::jsonb where id=?",c.view().checkoutId());
        mvc.perform(session(c,"read-only")).andExpect(status().isUnauthorized());
        assertThat(http.calls()).hasSize(calls);assertThat(count("payment_attempts")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"expiry","account","payment"})
    void committedSafeReceiptPrecedesCapabilityAndLateChangesSuppressSecret(String change) throws Exception {
        var c=stripeCheckout();policy();snapshot("requires_payment_method");identity();
        http.enqueue("GET","/v1/payment_intents/pi_fixture",200,intent("requires_payment_method"),Map.of(),1500);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            int before=http.calls().size();
            var response=executor.submit(() -> mvc.perform(session(c,"delayed")).andReturn().getResponse());
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(http.calls().size()<before+4&&System.nanoTime()<until)Thread.sleep(10);
            assertThat(http.calls()).hasSize(before+4);
            assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("payment_client_attempts")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records where scope like '%client-session%'",String.class)).isEqualTo("NONE");
            int expected;
            if(change.equals("expiry")) {TestClock.set(c.view().expiresAt());expected=410;}
            else if(change.equals("account")) {jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());expected=422;}
            else {jdbc.update("update payments set status='SUCCEEDED' where id=?",c.view().paymentId());expected=409;}
            var result=response.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.getStatus()).isEqualTo(expected);assertThat(result.getContentAsString()).doesNotContain(secret);
        }
    }

    @Test void anonymousDonationPreparesItsOriginalIntentAtClientSession() throws Exception {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        var campaign=campaigns.insert(dev.genericpayments.platform.donations.domain.DonationCampaign.draft(
            new dev.genericpayments.platform.donations.domain.DonationCampaignId(UUID.randomUUID()),site,
            new dev.genericpayments.platform.donations.domain.DonationCampaignKey("help-now"),"Help",null,"EUR",List.of(4999L),100,10000,true,true,INITIAL_TIME));
        campaigns.update(campaign.activate(INITIAL_TIME),0);
        var c=donationCheckouts.create(new dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand(
            "test-site","help-now",URI.create("https://shop.test"),null,4999,null,"donation","request")).checkout();
        var replay=donationCheckouts.create(new dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand(
            "test-site","help-now",URI.create("https://shop.test"),null,4999,null,"donation","request")).checkout();
        assertThat(replay.view().checkoutId()).isEqualTo(c.view().checkoutId());
        assertThat(replay.view().confirmationMode()).isEqualTo("CLIENT_PROVIDER");
        assertThat(http.calls()).isEmpty();policy();identity();
        http.enqueue("POST","/v1/payment_intents",200,intent("requires_payment_method"));
        snapshot("requires_payment_method");snapshot("requires_payment_method");
        mvc.perform(session(c,"donation-client")).andExpect(status().isOk()).andExpect(jsonPath("$.payment_id").value(c.view().paymentId().toString()));
        assertThat(jdbc.queryForObject("select customer_id from payment_client_attempts",UUID.class)).isNull();
        assertThat(http.calls().stream().filter(call -> call.method().equals("POST"))).singleElement().satisfies(call ->
            assertThat(call.header("Idempotency-Key")).isEqualTo("prepare_"+c.view().paymentId()));
    }

    @Test void dynamicHandoffPreparesMissingIntentAndRetainsOriginalCustomerWithoutAnotherBearer() throws Exception {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        var customer=customers.save(dev.genericpayments.platform.customers.domain.Customer.create(
            new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var p=save.save(dev.genericpayments.platform.payments.domain.Payment.dynamic(new dev.genericpayments.platform.payments.domain.PaymentId(UUID.randomUUID()),
            site,customer.id().value(),account.id(),"dynamic-order",dev.genericpayments.platform.catalog.domain.Money.of(4999,"EUR"),
            dev.genericpayments.platform.catalog.domain.TaxMode.NONE,Map.of(),INITIAL_TIME));
        var handoff=issueHandoff.issue(site,p.id(),URI.create("https://shop.test"),"handoff","request");
        var c=exchangeHandoff.exchange(handoff.handoffToken(),URI.create("https://shop.test"),UUID.randomUUID().toString());
        assertThat(http.calls()).isEmpty();policy();identity();http.enqueue("POST","/v1/payment_intents",200,intent("requires_payment_method"));
        snapshot("requires_payment_method");snapshot("requires_payment_method");
        mvc.perform(session(c,"dynamic-client")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select customer_id from payment_client_attempts",UUID.class)).isEqualTo(customer.id().value());
        assertThat(http.calls().stream().filter(call -> call.method().equals("POST"))).singleElement().satisfies(call -> {
            assertThat(call.header("Idempotency-Key")).isEqualTo("prepare_"+p.id().value());
            assertThat(call.form()).doesNotContainKeys("customer","receipt_email");
        });
    }

    @Test void safeProviderFailureLeavesPendingOriginalAttemptWithoutLeakingSecret() throws Exception {
        var c=stripeCheckout();policy();snapshot("requires_payment_method");identity();
        http.enqueue("GET","/v1/payment_intents/pi_fixture",400,"{\"error\":{\"type\":\"invalid_request_error\",\"message\":\""+secret+"\"}}");
        mvc.perform(session(c,"retry")).andExpect(status().isServiceUnavailable())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(secret));
        assertThat(count("payment_attempts")).isEqualTo(1);
        snapshot("requires_payment_method");snapshot("requires_payment_method");
        mvc.perform(session(c,"retry")).andExpect(status().isOk());
        assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test void clientSessionSharesTheExistingConfirmRateLimitBucket() throws Exception {
        var c=stripeCheckout();String ip=UUID.randomUUID().toString();
        for(int n=0;n<30;n++)mvc.perform(post("/api/v1/public/checkouts/"+c.view().checkoutId()+"/confirm")
            .header("Origin","https://shop.test").header("Authorization","Bearer "+c.checkoutToken()).header("Idempotency-Key","rate-"+n)
            .contentType("application/json").content("{\"payment_method_family\":\"CARD\"}").with(r -> {r.setRemoteAddr(ip);return r;}))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(session(c,"limited").with(r -> {r.setRemoteAddr(ip);return r;})).andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After")).andExpect(header().string("Cache-Control","no-store"));
        assertThat(count("payment_attempts")).isZero();
    }

    @Test void changedOriginalPreparationBindingCannotPoisonFirstClientBaseline() throws Exception {
        var c=stripeCheckout();policy();
        jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());
        for(int n=0;n<2;n++) {
            http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_changed\",\"object\":\"account\"}");
            http.enqueue("GET","/v1/payment_intents/pi_fixture",200,intent("requires_payment_method"));
        }
        mvc.perform(session(c,"changed-first")).andExpect(status().isConflict()).andExpect(jsonPath("$.client_secret").doesNotExist());
        assertThat(count("payment_attempts")).isZero();assertThat(count("payment_client_attempts")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where scope like '%client-session%'",Integer.class)).isZero();
    }

    @Test void directFakeAndInvalidBindingsNeverReachCapabilityProvider() {
        var c=stripeCheckout();var p=load.findById(site,new dev.genericpayments.platform.payments.domain.PaymentId(c.view().paymentId())).orElseThrow();
        int before=http.calls().size();
        assertThatThrownBy(() -> capabilities.retrieve(account,p)).isInstanceOf(RuntimeException.class);
        var invalid=dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount.create(account.id(),"test-account",
            dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE,
            dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode.SANDBOX,"acct_expected","invalid-alias");
        assertThatThrownBy(() -> capabilities.retrieve(invalid,p)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).hasSize(before);assertThat(count("payment_attempts")).isZero();
    }

    @Test void verifiedOldFailureIsOnlyAnAttemptWatermarkAndNeverAConsumedCharge() throws Exception {
        var c=stripeCheckout();policy();identity();
        String failedIntent=intent("requires_payment_method").replace("\"capture_method\"","\"last_payment_error\":{\"charge\":\"ch_old\"},\"capture_method\"");
        http.enqueue("GET","/v1/payment_intents/pi_fixture",200,failedIntent);
        http.enqueue("GET","/v1/charges/ch_old",200,"""
            {"id":"ch_old","object":"charge","livemode":false,"payment_intent":"pi_fixture","amount":4999,
             "currency":"eur","paid":false,"captured":false,"status":"failed","payment_method_details":{"type":"card"}}
            """);
        snapshot("requires_payment_method");
        mvc.perform(session(c,"old-failure")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select initial_failed_charge_reference from payment_client_attempts",String.class)).isEqualTo("ch_old");
        assertThat(jdbc.queryForObject("select status from payment_attempts",String.class)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(count("provider_charge_receipts")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_events where event_type in ('PAYMENT_FAILED','PAYMENT_SUCCEEDED')",Integer.class)).isZero();
    }

    @Test void existingCheckoutKeepsOriginalAccountAfterSiteRoutingChanges() throws Exception {
        var c=stripeCheckout();policy();var replacement=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'replacement','FAKE','SANDBOX','ACTIVE','fake','ref',?,0)",replacement,java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",replacement,site.value());
        snapshot("requires_payment_method");snapshot("requires_payment_method");
        mvc.perform(session(c,"original")).andExpect(status().isOk()).andExpect(jsonPath("$.provider_type").value("STRIPE"));
        assertThat(jdbc.queryForObject("select payment_account_id from payment_client_attempts",UUID.class)).isEqualTo(account.id().value());
    }

    @Test void pureCapabilityDescriptorCanJoinTransactionButProviderIoCannot() {
        var c=stripeCheckout();var p=load.findById(site,new dev.genericpayments.platform.payments.domain.PaymentId(c.view().paymentId())).orElseThrow();
        var original=accounts.findById(account.id()).orElseThrow();var provider=providers.select(original);
        int calls=http.calls().size();var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var descriptor=tx.execute(status -> provider.capabilities(original));
        assertThat(descriptor.confirmationMode()).isEqualTo(PaymentProviderCapabilities.ConfirmationMode.CLIENT_PROVIDER);
        assertThatThrownBy(() -> tx.execute(status -> provider.preparePayment(new ProviderPaymentRequest(original,p,ProviderOperationKeys.prepare(p.id())))))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.execute(status -> provider.retrievePayment(ProviderPaymentLookup.forPayment(original,p.id(),p.providerPaymentReference()))))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.execute(status -> capabilities.retrieve(original,p)))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(http.calls()).hasSize(calls);
    }

    @Test void overlappingHttpRequestsWithDifferentKeysReceiveOneCommittedAttempt() throws Exception {
        var c=stripeCheckout();policy();snapshot("requires_payment_method");identity();
        http.enqueue("GET","/v1/payment_intents/pi_fixture",200,intent("requires_payment_method"),Map.of(),1500);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            int before=http.calls().size();
            var first=executor.submit(() -> mvc.perform(session(c,"concurrent-a")).andReturn().getResponse());
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(http.calls().size()<before+4&&System.nanoTime()<until)Thread.sleep(10);
            assertThat(http.calls()).hasSize(before+4);
            snapshot("requires_payment_method");snapshot("requires_payment_method");
            var second=mvc.perform(session(c,"concurrent-b")).andExpect(status().isOk()).andReturn().getResponse();
            var one=first.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(one.getStatus()).isEqualTo(200);
            String firstAttempt=com.jayway.jsonpath.JsonPath.read(one.getContentAsString(),"$.attempt_id");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(second.getContentAsString(),"$.attempt_id")).isEqualTo(firstAttempt);
        }
        assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("payment_client_attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where scope like '%client-session%'",Integer.class)).isEqualTo(2);
    }

    @Test void clientSessionRejectsFakeWithoutFinancialEffects() throws Exception {
        var checkout = checkouts.create(new CreateCatalogCheckoutCommand("test-site", "lifetime",
            URI.create("https://shop.test"), null, "create", "request"));
        var eventsBefore = jdbc.queryForList("select * from payment_events");
        mvc.perform(post("/api/v1/public/checkouts/" + checkout.view().checkoutId() + "/client-session")
            .header("Origin", "https://shop.test").header("Authorization", "Bearer " + checkout.checkoutToken())
            .header("Idempotency-Key", "session-" + UUID.randomUUID()))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAYMENT_CAPABILITY_UNAVAILABLE"))
            .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("payment_attempts")).isZero();
        assertThat(jdbc.queryForList("select * from payment_events")).isEqualTo(eventsBefore);
    }
}
