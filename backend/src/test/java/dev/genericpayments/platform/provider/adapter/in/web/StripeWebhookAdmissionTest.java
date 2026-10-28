package dev.genericpayments.platform.provider.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@TestPropertySource(properties={"payment.stripe.sandbox.enabled=true",
    "payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"})
class StripeWebhookAdmissionTest extends ProviderTestFixture {
    static final Instant NOW=Instant.parse("2026-10-14T12:00:00Z");
    @org.springframework.test.context.bean.override.convention.TestBean(name="providerReferenceClock",methodName="realClock")
    java.time.Clock referenceClock;
    static java.time.Clock realClock() {return java.time.Clock.fixed(NOW,java.time.ZoneOffset.UTC);}
    @org.springframework.test.context.bean.override.convention.TestBean(name="stripeClientFactory",methodName="noNetwork")
    dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory clients;
    static dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory noNetwork() {
        return new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory() {
            @Override public com.stripe.StripeClient create(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials credentials) {
                throw new AssertionError("Webhook admission must never create a provider network client");
            }
        };
    }
    static final String SECRET="whsec_"+java.util.UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void signingKey(DynamicPropertyRegistry properties) {
        properties.add("payment.stripe.sandbox.webhook-secret",() -> SECRET);
    }
    static final String PATH="/api/v1/provider-webhooks/stripe/test-account";
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void web() {
        mvc=new AdminApiClient(context).mvc;
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
    }
    static byte[] body(String type) {
        return ("{\"id\":\"evt_fixture\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"created\":1791979200,\"livemode\":false,\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"livemode\":false,\"client_secret\":\"never-persist-this\"}}}").getBytes(StandardCharsets.UTF_8);
    }
    static String signature(byte[] raw,long timestamp,String key) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));
        return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal(raw));
    }
    @Test void signedExactBytesAreDurableAndDuplicateSafeWithoutFinancialEffects() throws Exception {
        byte[] raw=body("payment_intent.succeeded");String sig=signature(raw,NOW.getEpochSecond(),SECRET);
        for(int i=0;i<2;i++)mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",sig).content(raw)).andExpect(status().is2xxSuccessful());
        assertThat(count("provider_inbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select event_family from provider_inbox",String.class)).isEqualTo("PROVIDER_REFERENCE");
        assertThat(jdbc.queryForList("select row_to_json(i)::text from provider_inbox i").toString()).doesNotContain("never-persist-this");
        assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
    }
    @Test void alteredBytesWrongSecretAndMissingSignatureNeverEnterInbox() throws Exception {
        byte[] raw=body("payment_intent.succeeded");long now=NOW.getEpochSecond();
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,now,SECRET))
            .content((new String(raw,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,now,"whsec_wrong"))
            .content(raw)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType("application/json").content(raw)).andExpect(status().isBadRequest());
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void signatureBoundsDuplicateHeadersAndAccountHeadersAreRejected() throws Exception {
        byte[] raw=body("payment_intent.processing");String sig=signature(raw,NOW.getEpochSecond(),SECRET);
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",sig,sig).content(raw)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature","x".repeat(4097)).content(raw)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",sig).header("Stripe-Account","acct_wrong").content(raw)).andExpect(status().isBadRequest());
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void signedMalformedUtf8AndMalformedJsonAreRejectedSafely() throws Exception {
        for(byte[] raw:new byte[][]{new byte[]{(byte)0xc3,(byte)0x28},"{not-json-never-echo".getBytes(StandardCharsets.UTF_8)})
            mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw))
                .andExpect(status().isBadRequest()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("never-echo"));
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void oversizedStreamIsBoundedWithoutContentLength() throws Exception {
        byte[] raw=new byte[262145];java.util.Arrays.fill(raw,(byte)' ');
        String sig=signature(raw,NOW.getEpochSecond(),SECRET);
        mvc.perform(servletContext -> {
            var request=new org.springframework.mock.web.MockHttpServletRequest(servletContext,"POST",PATH) {
                @Override public int getContentLength(){return -1;}
                @Override public long getContentLengthLong(){return -1;}
            };
            request.setContentType("application/json");request.addHeader("Stripe-Signature",sig);request.setContent(raw);return request;
        }).andExpect(status().isPayloadTooLarge());
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void liveConnectApiMismatchAndWrongObjectShapeFailClosed() throws Exception {
        String valid=new String(body("payment_intent.succeeded"),StandardCharsets.UTF_8);
        for(String changed:java.util.List.of(valid.replace("\"livemode\":false","\"livemode\":true"),
            valid.replace("\"created\":","\"account\":\"acct_wrong\",\"created\":"),valid.replace("2026-09-30.endive","2025-01-01.acacia"),
            valid.replace("pi_fixture","re_wrong"),valid.replace("\"object\":\"payment_intent\"","\"object\":\"refund\""),
            valid.replace("\"id\":\"evt_fixture\"","\"id\":\"evt_fixture\",\"id\":\"evt_duplicate\""))) {
            byte[] raw=changed.getBytes(StandardCharsets.UTF_8);
            mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isBadRequest());
        }
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void onlyExactPostJsonRouteIsAdmitted() throws Exception {
        byte[] raw=body("payment_intent.processing");String sig=signature(raw,NOW.getEpochSecond(),SECRET);
        mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
        for(String media:java.util.List.of("text/plain","application/*+json","application/json;charset=ISO-8859-1"))
            mvc.perform(post(PATH).contentType(media).header("Stripe-Signature",sig).content(raw)).andExpect(status().isUnsupportedMediaType());
        for(String path:java.util.List.of(PATH+"/extra","/api/v1/provider-webhooks/anything","/api/v1/provider-webhooks/stripe/missing"))
            mvc.perform(post(path).contentType("application/json").header("Stripe-Signature",sig).content(raw)).andExpect(status().is4xxClientError());
        jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",account.id().value());
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",sig).content(raw)).andExpect(status().isNotFound());
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void signedUnsupportedTypeIsIgnoredButUnauthenticatedUnsupportedIsRejected() throws Exception {
        byte[] raw=body("customer.created");
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isNoContent());
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),"wrong")).content(raw)).andExpect(status().isBadRequest());
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void admissionReportsStorageFailureWithoutSuccessOrUnsafeDetails() throws Exception {
        jdbc.execute("create function task8_reject_inbox() returns trigger language plpgsql as $$ begin raise exception 'private-store-detail'; end; $$");
        jdbc.execute("create trigger task8_reject_inbox before insert on provider_inbox for each row execute function task8_reject_inbox()");
        try {
            byte[] raw=body("payment_intent.succeeded");
            mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw))
                .andExpect(status().is5xxServerError()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("private-store-detail","never-persist-this",SECRET));
            assertThat(count("provider_inbox")).isZero();
        } finally {jdbc.execute("drop trigger task8_reject_inbox on provider_inbox");jdbc.execute("drop function task8_reject_inbox()");}
    }
    @Test void timestampToleranceRejectsPastAndFutureBeyondInclusiveFiveMinutes() throws Exception {
        var now=Instant.parse("2026-10-14T12:00:00Z");
        for(long seconds:new long[]{-301,301})assertThat(dev.genericpayments.platform.provider.adapter.out.stripe.StripeWebhookVerifier.timestampAllowed(now.plusSeconds(seconds),now)).isFalse();
        for(long seconds:new long[]{-300,300})assertThat(dev.genericpayments.platform.provider.adapter.out.stripe.StripeWebhookVerifier.timestampAllowed(now.plusSeconds(seconds),now)).isTrue();
        byte[] raw=body("payment_intent.processing");
        for(long seconds:new long[]{-301,301})mvc.perform(post(PATH).contentType("application/json")
            .header("Stripe-Signature",signature(raw,NOW.getEpochSecond()+seconds,SECRET)).content(raw)).andExpect(status().isBadRequest());
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void everyAllowlistedEventProducesOnlyItsTypedReference() throws Exception {
        int i=0;
        for(String type:java.util.List.of("payment_intent.processing","payment_intent.requires_action","payment_intent.succeeded",
                "payment_intent.payment_failed","payment_intent.canceled","refund.created","refund.updated","refund.failed")) {
            String text=new String(body(type),StandardCharsets.UTF_8).replace("evt_fixture","evt_fixture"+(i++));
            if(type.startsWith("refund."))text=text.replace("pi_fixture","re_fixture").replace("\"object\":\"payment_intent\",\"livemode\":false","\"object\":\"refund\"");
            byte[] raw=text.getBytes(StandardCharsets.UTF_8);
            mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().is2xxSuccessful());
        }
        assertThat(count("provider_inbox")).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from provider_inbox where reference_object_type='REFUND'",Integer.class)).isEqualTo(3);
        assertThat(count("payment_events")).isZero();
    }
    @Test void refundModeMustBeAbsentOrStrictlyFalseAndEventModeMustBeFalse() throws Exception {
        String refund=new String(body("refund.created"),StandardCharsets.UTF_8).replace("pi_fixture","re_fixture").replace("\"object\":\"payment_intent\"","\"object\":\"refund\"");
        for(String mode:java.util.List.of("true","\"false\"","null","0")) {
            byte[] raw=refund.replace("\"object\":\"refund\",\"livemode\":false","\"object\":\"refund\",\"livemode\":"+mode).getBytes(StandardCharsets.UTF_8);
            mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isBadRequest());
        }
        byte[] missingEventMode=refund.replace(",\"livemode\":false,\"type\"",",\"type\"").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(missingEventMode,NOW.getEpochSecond(),SECRET)).content(missingEventMode)).andExpect(status().isBadRequest());
        assertThat(count("provider_inbox")).isZero();
        byte[] raw=refund.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isNoContent());
    }
    @Test void frozenBusinessClockDoesNotControlSignatureOrReceivedTime() throws Exception {
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(NOW.plusSeconds(10000));
        byte[] raw=body("payment_intent.processing");
        mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select received_at from provider_inbox",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select occurred_at from provider_inbox",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW);
    }
    @Test void exactByteAndHeaderLimitsAndToleranceEdgesAreAccepted() throws Exception {
        byte[] raw=java.util.Arrays.copyOf(body("payment_intent.processing"),262144);
        java.util.Arrays.fill(raw,body("payment_intent.processing").length,raw.length,(byte)' ');
        for(long offset:new long[]{-300,300}) {
            String sig=signature(raw,NOW.getEpochSecond()+offset,SECRET)+",v0=";
            sig+="0".repeat(4096-sig.length());
            mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",sig).content(raw)).andExpect(status().isNoContent());
        }
        assertThat(count("provider_inbox")).isEqualTo(1);
    }
}
