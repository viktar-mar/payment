package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.customers.application.model.VerifiedIdentityEvidence;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.context.WebApplicationContext;

@Import(CustomerAuthenticationIntegrationTest.VerifierConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CustomerAuthenticationIntegrationTest extends PostgresIntegrationTest {
    private static final String SITE_KEY="http-auth-site";
    private static final String ORIGIN="https://shop.example.test";
    private static final String SITE_PROVIDER="site-provider";
    private static final String OIDC_PROVIDER="oidc-provider";
    private static final String EMAIL_PROVIDER="email-provider";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired RecordingIdentityTokenVerifier verifier;
    @Autowired RecordingPublicAuthenticationRateLimiter authenticationRateLimiter;
    @Autowired RecordingPublicAuthenticationHandlerInterceptor authenticationHandlerRecorder;
    @Autowired RecordingLoadSitePort siteLoaderRecorder;
    private MockMvc mvc;
    private SiteId siteId;

    @BeforeEach
    void fixture() {
        clearDatabase();
        verifier.reset();
        authenticationRateLimiter.reset();
        authenticationHandlerRecorder.reset();
        siteLoaderRecorder.reset();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RequestIdFilter.class)).apply(springSecurity()).build();
        siteId=new SiteId(UUID.randomUUID());
        UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'http-auth-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'HTTP auth',?,'ACTIVE','AUTOMATIC',now(),now(),0)",siteId.value(),SITE_KEY,account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",siteId.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN'),(?,'OIDC_TOKEN'),(?,'EMAIL_OTP')",
            siteId.value(),siteId.value(),siteId.value());
        providers.save(IdentityProviderConfiguration.create(siteId,SITE_PROVIDER,
            new IdentityProviderConfiguration.SiteTokenConfiguration("https://site.example.test",Set.of("customer"),
                new JwksSource.Remote(URI.create("https://site.example.test/jwks")),Set.of("RS256"))));
        providers.save(IdentityProviderConfiguration.create(siteId,OIDC_PROVIDER,
            new IdentityProviderConfiguration.OidcTokenConfiguration("https://oidc.example.test",Set.of("customer"),
                new JwksSource.Remote(URI.create("https://oidc.example.test/jwks")),Set.of("RS256"),null)));
        providers.save(IdentityProviderConfiguration.create(siteId,EMAIL_PROVIDER,
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        siteLoaderRecorder.reset();
    }

    @Test
    void subscriptionAuthenticationBindsSiteForSiteTokenOidcAndOtpWithoutChangingLinkProofs() throws Exception {
        jdbc.update("insert into site_subscription_policies(site_id,enabled) values (?,true)",siteId.value());
        String site=mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:subscription-owner:subscription-jti\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSubscriptionSite(site);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var attempt=mapper.readTree(mvc.perform(post("/api/v1/public/customer-auth/oidc/attempts").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER+"\",\"intent\":\"AUTHENTICATE\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String nonce=attempt.get("nonce").asString();
        String oidc=mvc.perform(post("/api/v1/public/customer-auth/oidc/exchanges").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"attempt_id\":\""+attempt.get("attempt_id").asString()+"\",\"site_key\":\""+SITE_KEY
                    +"\",\"provider_key\":\""+OIDC_PROVIDER+"\",\"intent\":\"AUTHENTICATE\",\"nonce\":\""+nonce+"\",\"token\":\"oidc:subscription-oidc:subscription-oidc-jti:"+nonce+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSubscriptionSite(oidc);
        var challenge=mapper.readTree(mvc.perform(post("/api/v1/public/customer-auth/email-otp/challenges").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"site_key\":\""+SITE_KEY+"\",\"email\":\"subscription@example.test\",\"intent\":\"AUTHENTICATE\"}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String otp=mvc.perform(post("/api/v1/public/customer-auth/email-otp/challenges/"+challenge.get("challenge_id").asString()+"/verify")
                .header("Origin",ORIGIN).contentType("application/json").content("{\"site_key\":\""+SITE_KEY+"\",\"code\":\"123456\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSubscriptionSite(otp);
        String link=mvc.perform(post("/api/v1/public/customer-auth/site-token/link-proofs").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:subscription-link:subscription-link-jti\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fieldNames(mapper.readTree(link))).containsExactlyInAnyOrder("proof_token","expires_at");
    }

    private void assertSubscriptionSite(String response) throws Exception {
        var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder("session_token","expires_at","scopes","customer","site_id");
        assertThat(json.path("site_id").asString()).isEqualTo(siteId.value().toString());
        assertThat(json.path("scopes")).extracting(tools.jackson.databind.JsonNode::asString)
            .containsExactlyInAnyOrder("CUSTOMER_READ","CHECKOUT_CREATE","IDENTITY_LINK","SUBSCRIPTION_READ","SUBSCRIPTION_MANAGE");
    }

    @Test
    void siteTokenExchangeReturnsOneSessionOnceAndReplayHasOneSafeFailureShape(CapturedOutput output) throws Exception {
        String body="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:subject-a:jti-a\"}";
        MvcResult result=mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges")
                .header("Origin",ORIGIN).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(jsonPath("$.session_token").isString()).andExpect(jsonPath("$.expires_at").isString())
            .andExpect(jsonPath("$.scopes").isArray()).andExpect(jsonPath("$.customer.id").isString())
            .andExpect(jsonPath("$.customer.issuer").doesNotExist()).andReturn();
        String response=result.getResponse().getContentAsString();
        var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder("session_token","expires_at","scopes","customer");
        assertThat(json.path("scopes")).extracting(tools.jackson.databind.JsonNode::asString)
            .containsExactlyInAnyOrder("CUSTOMER_READ","CHECKOUT_CREATE","IDENTITY_LINK");
        assertThat(fieldNames(json.get("customer"))).containsExactlyInAnyOrder("id","status","masked_email");
        String raw=json.get("session_token").asString();
        assertThat(jdbc.queryForMap("select * from customer_sessions").toString()).doesNotContain(raw,"subject-a","jti-a");
        assertThat(output.getAll()).doesNotContain(raw);

        mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges")
                .header("Origin",ORIGIN).contentType("application/json").content(body))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.title").value("Authentication failed"));
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
    }

    @Test
    void authenticatedCustomerGetsOnlySafeOwnViewAndLogoutRevokesTheCurrentSession() throws Exception {
        String token=exchange("site:subject-b:jti-b");
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").isString()).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.authentication_modes[0]").value("SITE_TOKEN"))
            .andExpect(jsonPath("$.session_expires_at").isString()).andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.version").doesNotExist()).andExpect(jsonPath("$.email_digest").doesNotExist());

        mvc.perform(delete("/api/v1/customer/session").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(header().string("Cache-Control","no-store"));
        String expired=exchange("site:subject-expiry:jti-expiry");
        TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(31)));
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+expired))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN));
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN+"/path").header("Authorization","Bearer "+expired))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void currentCustomerRequiresCustomerReadWithoutChangingOtherSessionCapabilities() throws Exception {
        String token=exchange("site:restricted-subject:restricted-jti");
        jdbc.update("update customer_sessions set scopes='[\"CHECKOUT_CREATE\"]'");

        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.title").value("Authentication failed"));

        jdbc.update("update customer_sessions set scopes='[\"CUSTOMER_READ\"]'");
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").isString());
    }

    @Test
    void customerRoutesAuthenticateAndSupportCorsUnderANonRootContext() throws Exception {
        String token=exchange("site:context-owner:context-owner-jti");
        for(String path:List.of("/me","/payments","/donations")) {
            mvc.perform(get("/edge/api/v1/customer"+path).contextPath("/edge").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
                .andExpect(header().string("Cache-Control","no-store"));
        }
        String proof=linkProof("site:context-linked:context-link-jti");
        mvc.perform(post("/edge/api/v1/customer/identities").contextPath("/edge").header("Origin",ORIGIN)
                .header("Authorization","Bearer "+token).contentType("application/json").content("{\"proof_token\":\""+proof+"\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("LINKED"));
        mvc.perform(delete("/edge/api/v1/customer/session").contextPath("/edge").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
            .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
        for(String path:List.of("/me","/payments","/donations","/identities","/session")) {
            mvc.perform(options("/edge/api/v1/customer"+path).contextPath("/edge").header("Origin",ORIGIN)
                    .header("Access-Control-Request-Method","GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
                .andExpect(header().string("Cache-Control","no-store"));
        }
    }

    @Test
    void nonRootCustomerFailuresRemainUniformReadableAndOriginBound() throws Exception {
        String expired=exchange("site:context-expired:context-expired-jti");
        TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(31)));
        for(String token:List.of(expired,"invalid")) {
            mvc.perform(get("/edge/api/v1/customer/me").contextPath("/edge").header("Origin",ORIGIN).header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN)).andExpect(header().string("Cache-Control","no-store"));
        }
        for(String method:List.of("GET","OPTIONS")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),"/edge/api/v1/customer/me").contextPath("/edge")
                    .header("Origin","https://disallowed.example.test").header("Authorization","Bearer "+expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin")).andExpect(header().string("Cache-Control","no-store"));
        }
    }

    @Test
    void customerFilterRejectsAbsentMalformedCrossOriginAndAdminOrPartnerCredentials() throws Exception {
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Basic bad"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN)
                .cookie(new jakarta.servlet.http.Cookie("PAYMENT_ADMIN_SESSION","A".repeat(43))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer partner-credential"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        String token=exchange("site:subject-c:jti-c");
        mvc.perform(get("/api/v1/customer/me").header("Origin","https://evil.example.test").header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/customer/me").header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void oidcAuthenticateIssuesSessionWhileLinkAndDedicatedSiteLinkNeverDo() throws Exception {
        String begin=mvc.perform(post("/api/v1/public/customer-auth/oidc/attempts").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER+"\",\"intent\":\"AUTHENTICATE\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.nonce").isString()).andReturn().getResponse().getContentAsString();
        var started=tools.jackson.databind.json.JsonMapper.builder().build().readTree(begin);
        assertThat(fieldNames(started)).containsExactlyInAnyOrder("attempt_id","nonce","expires_at");
        String attempt=started.get("attempt_id").asString(),nonce=started.get("nonce").asString();
        mvc.perform(post("/api/v1/public/customer-auth/oidc/exchanges").header("Origin",ORIGIN).contentType("application/json")
                .content("{\"attempt_id\":\""+attempt+"\",\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER
                    +"\",\"intent\":\"AUTHENTICATE\",\"nonce\":\""+nonce+"\",\"token\":\"oidc:subject-d:jti-d:"+nonce+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_token").isString()).andExpect(jsonPath("$.proof_token").doesNotExist());

        MvcResult link=mvc.perform(post("/api/v1/public/customer-auth/site-token/link-proofs").header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:link-subject:link-jti\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.proof_token").isString()).andExpect(jsonPath("$.session_token").doesNotExist())
            .andReturn();
        var linked=tools.jackson.databind.json.JsonMapper.builder().build().readTree(link.getResponse().getContentAsString());
        assertThat(fieldNames(linked)).containsExactlyInAnyOrder("proof_token","expires_at");
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
    }

    @Test
    void customerSessionLinksAnIdentityThroughTheExactBoundedHttpContract(CapturedOutput output) throws Exception {
        String session=exchange("site:http-link-owner:http-link-owner-jti");
        String proof=linkProof("site:http-linked-subject:http-link-proof-jti");

        MvcResult linked=mvc.perform(post("/api/v1/customer/identities").header("Origin",ORIGIN)
                .header("Authorization","Bearer "+session).contentType("application/json")
                .content("{\"proof_token\":\""+proof+"\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(jsonPath("$.identity_id").isString()).andExpect(jsonPath("$.outcome").value("LINKED"))
            .andReturn();
        var first=tools.jackson.databind.json.JsonMapper.builder().build().readTree(linked.getResponse().getContentAsString());
        assertThat(fieldNames(first)).containsExactlyInAnyOrder("identity_id","outcome");

        String replayProof=linkProof("site:http-linked-subject:http-link-replay-jti");
        mvc.perform(post("/api/v1/customer/identities").header("Origin",ORIGIN)
                .header("Authorization","Bearer "+session).contentType("application/json")
                .content("{\"proof_token\":\""+replayProof+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.identity_id").value(first.get("identity_id").asString()))
            .andExpect(jsonPath("$.outcome").value("ALREADY_LINKED"));

        mvc.perform(post("/api/v1/customer/identities").header("Origin",ORIGIN)
                .header("Authorization","Bearer "+session).contentType("application/json")
                .content("{\"proof_token\":\""+"Q".repeat(43)+"\",\"unexpected\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/customer/identities").header("Origin",ORIGIN)
                .header("Authorization","Bearer "+session).contentType("application/json").content("x".repeat(16_385)))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("IDENTITY_LINK_REQUEST_TOO_LARGE"));

        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.CUSTOMER_IDENTITY_LINK))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs where consumed_at is not null",Integer.class)).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain(proof,replayProof,"http-linked-subject");
    }

    @Test
    void authenticationRoutesHaveExactOperationIdsCorsAndPreParsingBodyLimit() throws Exception {
        MvcResult api=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/public/customer-auth/site-token/exchanges'].post.operationId").value("exchangeCustomerSiteToken"))
            .andExpect(jsonPath("$.paths['/api/v1/public/customer-auth/oidc/attempts'].post.operationId").value("beginCustomerOidcAttempt"))
            .andExpect(jsonPath("$.paths['/api/v1/public/customer-auth/oidc/exchanges'].post.operationId").value("exchangeCustomerOidcToken"))
            .andExpect(jsonPath("$.paths['/api/v1/public/customer-auth/site-token/link-proofs'].post.operationId").value("createSiteTokenIdentityLinkProof"))
            .andExpect(jsonPath("$.paths['/api/v1/public/customer-auth/email-otp/challenges'].post.operationId").value("requestCustomerEmailOtp"))
            .andExpect(jsonPath("$.paths['/api/v1/public/customer-auth/email-otp/challenges/{challengeId}/verify'].post.operationId").value("verifyCustomerEmailOtp"))
            .andExpect(jsonPath("$.paths['/api/v1/customer/me'].get.operationId").value("getCurrentCustomer"))
            .andExpect(jsonPath("$.paths['/api/v1/customer/session'].delete.operationId").value("logoutCurrentCustomerSession"))
            .andReturn();
        var openapi=tools.jackson.databind.json.JsonMapper.builder().build().readTree(api.getResponse().getContentAsString());
        var schemas=openapi.at("/components/schemas");
        assertThat(fieldNames(schemas.get("CustomerAuthenticationSessionResponse").get("properties")))
            .containsExactlyInAnyOrder("session_token","expires_at","scopes","customer","site_id");
        assertThat(fieldNames(schemas.get("CustomerAuthenticationCustomerResponse").get("properties")))
            .containsExactlyInAnyOrder("id","status","masked_email");
        assertThat(fieldNames(schemas.get("CustomerOidcAttemptResponse").get("properties")))
            .containsExactlyInAnyOrder("attempt_id","nonce","expires_at");
        assertThat(fieldNames(schemas.get("CustomerAuthenticationLinkProofResponse").get("properties")))
            .containsExactlyInAnyOrder("proof_token","expires_at");
        assertThat(fieldNames(schemas.get("CustomerEmailOtpChallengeResponse").get("properties")))
            .containsExactlyInAnyOrder("challenge_id","expires_at");
        assertThat(schemas.get("CustomerSiteTokenRequest").at("/properties/token/writeOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerOidcExchangeRequest").at("/properties/nonce/writeOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerOidcExchangeRequest").at("/properties/token/writeOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerOidcAttemptResponse").at("/properties/nonce/readOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerAuthenticationSessionResponse").at("/properties/session_token/readOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerAuthenticationLinkProofResponse").at("/properties/proof_token/readOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerEmailOtpRequest").at("/properties/email/writeOnly").asBoolean()).isTrue();
        assertThat(schemas.get("CustomerEmailOtpVerifyRequest").at("/properties/code/writeOnly").asBoolean()).isTrue();
        var oidcResponseSchema=openapi.at("/paths/~1api~1v1~1public~1customer-auth~1oidc~1exchanges/post/responses/200/content/application~1json/schema");
        var oneOf=oidcResponseSchema.get("oneOf");
        if(oneOf==null && oidcResponseSchema.has("$ref")) {
            String schemaName=oidcResponseSchema.get("$ref").asText().replace("#/components/schemas/","");
            oneOf=schemas.get(schemaName).get("oneOf");
        }
        assertThat(oneOf.isArray()).isTrue();
        assertThat(oneOf).hasSize(2);
        assertThat(oneOf.toString()).contains("CustomerAuthenticationSessionResponse","CustomerAuthenticationLinkProofResponse");
        var otpResponseSchema=openapi.at("/paths/~1api~1v1~1public~1customer-auth~1email-otp~1challenges~1{challengeId}~1verify/post/responses/200/content/application~1json/schema");
        var otpOneOf=otpResponseSchema.get("oneOf");
        if(otpOneOf==null&&otpResponseSchema.has("$ref")) {
            String schemaName=otpResponseSchema.get("$ref").asText().replace("#/components/schemas/","");
            otpOneOf=schemas.get(schemaName).get("oneOf");
        }
        assertThat(otpOneOf).isNotNull().hasSize(2);
        assertThat(otpOneOf.toString()).contains("CustomerAuthenticationSessionResponse","CustomerAuthenticationLinkProofResponse");

        mvc.perform(options("/api/v1/public/customer-auth/site-token/exchanges").header("Origin",ORIGIN)
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN));
        mvc.perform(options("/api/v1/customer/me").header("Origin",ORIGIN)
                .header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","authorization"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Methods","GET, POST, DELETE, OPTIONS"));
        mvc.perform(options("/api/v1/customer/identities").header("Origin",ORIGIN)
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Methods","GET, POST, DELETE, OPTIONS"));
        mvc.perform(options("/api/v1/public/customer-auth/site-token/exchanges").header("Origin","https://evil.example.test")
                .header("Access-Control-Request-Method","POST"))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges").header("Origin",ORIGIN)
                .contentType("application/json").content("x".repeat(16_385)))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUEST_TOO_LARGE"));
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
    }

    @Test
    void emailOtpRequestAndUnknownVerificationUseSafeContractsWithoutProcessLocalLimiterOrSecretLogs(CapturedOutput output)
            throws Exception {
        String email="Private.Person+otp@example.test",code="987654";
        String response=mvc.perform(post("/api/v1/public/customer-auth/email-otp/challenges")
                .header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"email\":\""+email+"\",\"intent\":\"AUTHENTICATE\"}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(jsonPath("$.challenge_id").isString()).andExpect(jsonPath("$.expires_at").isString())
            .andReturn().getResponse().getContentAsString();
        var challenge=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response);
        assertThat(fieldNames(challenge)).containsExactlyInAnyOrder("challenge_id","expires_at");
        assertThat(authenticationRateLimiter.totalAcquisitions()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isOne();

        mvc.perform(post("/api/v1/public/customer-auth/email-otp/challenges/00000000-0000-0000-0000-000000000000/verify")
                .header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"code\":\""+code+"\"}"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
            .andExpect(jsonPath("$.code").value("OTP_INVALID_OR_EXPIRED"))
            .andExpect(jsonPath("$.title").value("OTP is invalid or expired"));
        assertThat(authenticationRateLimiter.totalAcquisitions()).isZero();
        assertThat(output.getAll()).doesNotContain(email,email.toLowerCase(Locale.ROOT),code);
    }

    @Test
    void invalidCredentialRequestsAreRateLimitedBeforeVerificationWithoutDisclosingKeyMaterial(CapturedOutput output) throws Exception {
        String secret="site:rate-limited-subject:rate-limited-jti";
        String invalid="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"bad-signature\"}";
        for(int index=0;index<30;index++) {
            mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges")
                    .header("Origin",ORIGIN).contentType("application/json").content(invalid))
                .andExpect(status().isUnauthorized());
        }
        MvcResult limited=mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges")
                .header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\""+secret+"\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN)).andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED")).andExpect(jsonPath("$.title").value("Rate limit exceeded"))
            .andReturn();
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
        assertThat(limited.getResponse().getContentAsString()).doesNotContain(secret,"rate-limited-subject","rate-limited-jti",
            SITE_KEY,SITE_PROVIDER,ORIGIN,"127.0.0.1");
        assertThat(output.getAll()).doesNotContain(secret,"rate-limited-subject","rate-limited-jti",
            SITE_KEY,SITE_PROVIDER,ORIGIN,"127.0.0.1");
    }

    @Test
    void everyPublicAuthenticationOperationHasItsOwnBoundedBucket() throws Exception {
        var routes=List.of(
            new RateLimitedRoute("/api/v1/public/customer-auth/oidc/attempts",20),
            new RateLimitedRoute("/api/v1/public/customer-auth/oidc/exchanges",30),
            new RateLimitedRoute("/api/v1/public/customer-auth/site-token/exchanges",30),
            new RateLimitedRoute("/api/v1/public/customer-auth/site-token/link-proofs",20));
        String body="{\"site_key\":\""+SITE_KEY+"\"}";
        for(var route:routes) {
            for(int index=0;index<route.capacity();index++) {
                MvcResult admitted=mvc.perform(post(route.path()).header("Origin",ORIGIN)
                    .contentType("application/json").content(body)).andReturn();
                assertThat(admitted.getResponse().getStatus()).isNotEqualTo(429);
            }
            mvc.perform(post(route.path()).header("Origin",ORIGIN).contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
    }

    @Test
    void percentEncodedAuthenticationRoutesAreRejectedBeforeLimiterVerificationOrIdentityWork() throws Exception {
        var routes=List.of(
            new EncodedRoute("/api/v1/public/customer-auth/oidc/attempts","/api/v1/public/customer-auth/oidc/%61ttempts"),
            new EncodedRoute("/api/v1/public/customer-auth/oidc/exchanges","/api/v1/public/customer-auth/oidc/%65xchanges"),
            new EncodedRoute("/api/v1/public/customer-auth/site-token/exchanges","/api/v1/public/customer-auth/site-token/%65xchanges"),
            new EncodedRoute("/api/v1/public/customer-auth/site-token/link-proofs","/api/v1/public/customer-auth/site-token/link-%70roofs"));
        for(var route:routes) {
            mvc.perform(options(route.canonicalPath()).header("Origin",ORIGIN)
                    .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN));
            mvc.perform(options(URI.create(route.encodedPath())).header("Origin",ORIGIN)
                    .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        }
        assertThat(authenticationRateLimiter.totalAcquisitions()).isZero();
        assertThat(verifier.totalCalls()).isZero();
        assertThat(authenticationWorkCount()).isZero();

        String beginBody="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER+"\",\"intent\":\"AUTHENTICATE\"}";
        rejectEncoded(routes.get(0).encodedPath(),beginBody);
        assertNoAuthenticationWorkOrAllowance();
        String begin=mvc.perform(post("/api/v1/public/customer-auth/oidc/attempts").header("Origin",ORIGIN)
                .contentType("application/json").content(beginBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var started=tools.jackson.databind.json.JsonMapper.builder().build().readTree(begin);
        String attempt=started.get("attempt_id").asString(),nonce=started.get("nonce").asString();
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_BEGIN)).isOne();

        String oidcBody="{\"attempt_id\":\""+attempt+"\",\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER
            +"\",\"intent\":\"AUTHENTICATE\",\"nonce\":\""+nonce+"\",\"token\":\"oidc:encoded-subject:encoded-oidc-jti:"+nonce+"\"}";
        rejectEncoded(routes.get(1).encodedPath(),oidcBody);
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_EXCHANGE)).isZero();
        assertThat(verifier.oidcCalls()).isZero();
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,UUID.fromString(attempt))).isTrue();
        mvc.perform(post("/api/v1/public/customer-auth/oidc/exchanges").header("Origin",ORIGIN)
                .contentType("application/json").content(oidcBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_token").isString());
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_EXCHANGE)).isOne();
        assertThat(verifier.oidcCalls()).isOne();

        int identityCountAfterOidc=jdbc.queryForObject("select count(*) from customer_identities",Integer.class);
        String siteBody="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:encoded-site-subject:encoded-site-jti\"}";
        rejectEncoded(routes.get(2).encodedPath(),siteBody);
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_EXCHANGE)).isZero();
        assertThat(verifier.siteTokenCalls()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isEqualTo(identityCountAfterOidc);
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
        mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges").header("Origin",ORIGIN)
                .contentType("application/json").content(siteBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_token").isString());
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_EXCHANGE)).isOne();
        assertThat(verifier.siteTokenCalls()).isOne();

        int identitiesBeforeLink=jdbc.queryForObject("select count(*) from customer_identities",Integer.class);
        int consumedJtisBeforeLink=jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class);
        String linkBody="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:encoded-link-subject:encoded-link-jti\"}";
        rejectEncoded(routes.get(3).encodedPath(),linkBody);
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_LINK_PROOF)).isZero();
        assertThat(verifier.siteTokenCalls()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isEqualTo(identitiesBeforeLink);
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isEqualTo(consumedJtisBeforeLink);
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs",Integer.class)).isZero();
        mvc.perform(post("/api/v1/public/customer-auth/site-token/link-proofs").header("Origin",ORIGIN)
                .contentType("application/json").content(linkBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.proof_token").isString());
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_LINK_PROOF)).isOne();
        assertThat(verifier.siteTokenCalls()).isEqualTo(2);
    }

    @Test
    void nonCanonicalAuthenticationRouteAliasesAreRejectedBeforeSiteLookupPolicyAllowanceOrApplicationWork() throws Exception {
        String beginBody="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER+"\",\"intent\":\"AUTHENTICATE\"}";
        String begin=mvc.perform(post("/api/v1/public/customer-auth/oidc/attempts").header("Origin",ORIGIN)
                .contentType("application/json").content(beginBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var started=tools.jackson.databind.json.JsonMapper.builder().build().readTree(begin);
        String attempt=started.get("attempt_id").asString(),nonce=started.get("nonce").asString();
        String oidcBody="{\"attempt_id\":\""+attempt+"\",\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER
            +"\",\"intent\":\"AUTHENTICATE\",\"nonce\":\""+nonce+"\",\"token\":\"oidc:fix3-subject:fix3-oidc-jti:"+nonce+"\"}";
        var routes=List.of(
            new NonCanonicalRoute("/api/v1/public/customer-auth/oidc/attempts","/api/v1/public/customer-auth/oidc/%61ttempts",beginBody),
            new NonCanonicalRoute("/api/v1/public/customer-auth/oidc/exchanges","/api/v1/public/customer-auth/oidc/%65xchanges",oidcBody),
            new NonCanonicalRoute("/api/v1/public/customer-auth/site-token/exchanges","/api/v1/public/customer-auth/site-token/%65xchanges",
                "{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:fix3-site-subject:fix3-site-jti\"}"),
            new NonCanonicalRoute("/api/v1/public/customer-auth/site-token/link-proofs","/api/v1/public/customer-auth/site-token/link-%70roofs",
                "{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:fix3-link-subject:fix3-link-jti\"}"),
            new NonCanonicalRoute("/api/v1/public/customer-auth/email-otp/challenges","/api/v1/public/customer-auth/email-otp/%63hallenges",
                "{\"site_key\":\""+SITE_KEY+"\",\"email\":\"alias@example.test\",\"intent\":\"AUTHENTICATE\"}"),
            new NonCanonicalRoute("/api/v1/public/customer-auth/email-otp/challenges/00000000-0000-0000-0000-000000000000/verify",
                "/api/v1/public/customer-auth/email-otp/challenges/00000000-0000-0000-0000-000000000000/%76erify",
                "{\"site_key\":\""+SITE_KEY+"\",\"code\":\"123456\"}"));

        authenticationRateLimiter.reset();
        verifier.reset();
        authenticationHandlerRecorder.reset();
        siteLoaderRecorder.reset();
        AuthenticationState baseline=authenticationState();
        for(var route:routes) {
            for(var target:nonCanonicalTargets(route)) rejectBeforeAuthenticationWork(target,route.body());
        }
        assertThat(authenticationRateLimiter.totalAcquisitions()).isZero();
        assertThat(verifier.totalCalls()).isZero();
        assertThat(authenticationHandlerRecorder.calls()).isZero();
        assertThat(authenticationState()).isEqualTo(baseline);
    }

    @Test
    void canonicalAuthenticationRoutesRetainCorsBodyLimitAndLimiterUnderServletContextPath() throws Exception {
        String contextPath="/edge";
        var paths=List.of(
            "/api/v1/public/customer-auth/oidc/attempts",
            "/api/v1/public/customer-auth/oidc/exchanges",
            "/api/v1/public/customer-auth/site-token/exchanges",
            "/api/v1/public/customer-auth/site-token/link-proofs",
            "/api/v1/public/customer-auth/email-otp/challenges",
            "/api/v1/public/customer-auth/email-otp/challenges/00000000-0000-0000-0000-000000000000/verify");
        for(String path:paths) {
            mvc.perform(options(contextPath+path).contextPath(contextPath).header("Origin",ORIGIN)
                    .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN));
            mvc.perform(post(contextPath+path).contextPath(contextPath).header("Origin",ORIGIN)
                    .contentType("application/json").content("x".repeat(16_385)))
                .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin",ORIGIN))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUEST_TOO_LARGE"));
        }
        assertNoAuthenticationWorkOrAllowance();

        String beginBody="{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER+"\",\"intent\":\"AUTHENTICATE\"}";
        String begin=mvc.perform(post(contextPath+paths.get(0)).contextPath(contextPath).queryParam("shadow","1").header("Origin",ORIGIN)
                .contentType("application/json").content(beginBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var started=tools.jackson.databind.json.JsonMapper.builder().build().readTree(begin);
        String attempt=started.get("attempt_id").asString(),nonce=started.get("nonce").asString();
        mvc.perform(post(contextPath+paths.get(1)).contextPath(contextPath).header("Origin",ORIGIN).contentType("application/json")
                .content("{\"attempt_id\":\""+attempt+"\",\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+OIDC_PROVIDER
                    +"\",\"intent\":\"AUTHENTICATE\",\"nonce\":\""+nonce+"\",\"token\":\"oidc:context-subject:context-jti:"+nonce+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_token").isString());
        mvc.perform(post(contextPath+paths.get(2)).contextPath(contextPath).header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:context-site-subject:context-site-jti\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session_token").isString());
        mvc.perform(post(contextPath+paths.get(3)).contextPath(contextPath).header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\"site:context-link-subject:context-link-jti\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.proof_token").isString());
        mvc.perform(post(contextPath+paths.get(4)).contextPath(contextPath).header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"email\":\"context@example.test\",\"intent\":\"AUTHENTICATE\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.challenge_id").isString());
        mvc.perform(post(contextPath+paths.get(5)).contextPath(contextPath).header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"code\":\"000000\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID_OR_EXPIRED"));
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_BEGIN)).isOne();
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_EXCHANGE)).isOne();
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_EXCHANGE)).isOne();
        assertThat(authenticationRateLimiter.acquisitions(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_LINK_PROOF)).isOne();
        assertThat(authenticationRateLimiter.totalAcquisitions()).isEqualTo(4);
    }

    private List<AuthenticationTarget> nonCanonicalTargets(NonCanonicalRoute route) {
        String prefix="/api/v1/public/customer-auth",suffix=route.canonicalPath().substring(prefix.length());
        String encodedPrefix="/api/v1/public/customer-%61uth"+suffix;
        return List.of(
            new AuthenticationTarget(encodedPrefix,"",false),
            new AuthenticationTarget(route.encodedFinalPath(),"",false),
            new AuthenticationTarget("/edge"+encodedPrefix,"/edge",false),
            new AuthenticationTarget("/api/v1/public/customer-%2561uth"+suffix,"",true),
            new AuthenticationTarget("/api/v1/public/customer-auth%2F"+suffix.substring(1),"",true),
            new AuthenticationTarget("/api/v1/public/customer-auth%252F"+suffix.substring(1),"",true),
            new AuthenticationTarget("/api/v1/public/customer-auth;shadow=1"+suffix,"",true),
            new AuthenticationTarget("/api/v1/public//customer-auth"+suffix,"",true),
            new AuthenticationTarget("/api/v1/public/./customer-auth"+suffix,"",true),
            new AuthenticationTarget("/api/v1/public/Customer-auth"+suffix,"",false),
            new AuthenticationTarget(route.canonicalPath()+"/","",false),
            new AuthenticationTarget(encodedPrefix+"?shadow=1", "",false));
    }

    private void rejectBeforeAuthenticationWork(AuthenticationTarget target,String body) throws Exception {
        var request=post(URI.create(target.requestUri())).header("Origin",ORIGIN).contentType("application/json").content(body);
        if(!target.contextPath().isEmpty()) request.contextPath(target.contextPath());
        MvcResult result=mvc.perform(request).andReturn();
        var response=result.getResponse();
        assertThat(siteLoaderRecorder.totalCalls()).as(target.requestUri()).isZero();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).as(target.requestUri()).isNull();
        if(target.containerMayReject()&&response.getStatus()==400) {
            assertThat(response.getContentAsString()).as(target.requestUri()).isEmpty();
            assertThat(response.getHeader("Set-Cookie")).as(target.requestUri()).isNull();
        } else {
            assertThat(response.getHeader("Cache-Control")).as(target.requestUri()).contains("no-store");
            assertThat(response.getStatus()).as(target.requestUri()).isEqualTo(401);
            var problem=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsString());
            assertThat(problem.get("code").asString()).as(target.requestUri()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(problem.get("title").asString()).as(target.requestUri()).isEqualTo("Authentication failed");
        }
        assertThat(authenticationRateLimiter.totalAcquisitions()).isZero();
        assertThat(verifier.totalCalls()).isZero();
        assertThat(authenticationHandlerRecorder.calls()).isZero();
    }

    private void rejectEncoded(String path,String body) throws Exception {
        mvc.perform(post(URI.create(path)).header("Origin",ORIGIN).contentType("application/json").content(body))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.title").value("Authentication failed"));
    }

    private void assertNoAuthenticationWorkOrAllowance() {
        assertThat(authenticationRateLimiter.totalAcquisitions()).isZero();
        assertThat(verifier.totalCalls()).isZero();
        assertThat(authenticationWorkCount()).isZero();
    }

    private int authenticationWorkCount() {
        return jdbc.queryForObject("select (select count(*) from oidc_authentication_attempts)"
            +"+(select count(*) from customers)+(select count(*) from customer_identities)+(select count(*) from customer_sessions)"
            +"+(select count(*) from consumed_site_token_jtis)+(select count(*) from identity_link_proofs)"
            +"+(select count(*) from email_otp_challenges)+(select count(*) from customer_mail_outbox)"
            +"+(select count(*) from customer_rate_limit_buckets)",Integer.class);
    }

    private AuthenticationState authenticationState() {
        return new AuthenticationState(
            jdbc.queryForObject("select count(*) from oidc_authentication_attempts",Integer.class),
            jdbc.queryForObject("select count(*) from customers",Integer.class),
            jdbc.queryForObject("select count(*) from customer_identities",Integer.class),
            jdbc.queryForObject("select count(*) from customer_sessions",Integer.class),
            jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class),
            jdbc.queryForObject("select count(*) from identity_link_proofs",Integer.class),
            jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class),
            jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class),
            jdbc.queryForObject("select count(*) from customer_rate_limit_buckets",Integer.class));
    }

    private String exchange(String source) throws Exception {
        String response=mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges")
                .header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\""+source+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("session_token").asString();
    }

    private String linkProof(String source) throws Exception {
        String response=mvc.perform(post("/api/v1/public/customer-auth/site-token/link-proofs")
                .header("Origin",ORIGIN).contentType("application/json")
                .content("{\"site_key\":\""+SITE_KEY+"\",\"provider_key\":\""+SITE_PROVIDER+"\",\"token\":\""+source+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("proof_token").asString();
    }

    private static Set<String> fieldNames(tools.jackson.databind.JsonNode node) {
        var names=new LinkedHashSet<String>();
        names.addAll(node.propertyNames());
        return names;
    }

    private record RateLimitedRoute(String path,int capacity) {}
    private record EncodedRoute(String canonicalPath,String encodedPath) {}
    private record NonCanonicalRoute(String canonicalPath,String encodedFinalPath,String body) {}
    private record AuthenticationTarget(String requestUri,String contextPath,boolean containerMayReject) {}
    private record AuthenticationState(int attempts,int customers,int identities,int sessions,int consumedJtis,int linkProofs,
        int emailOtpChallenges,int mailOutboxJobs,int distributedRateBuckets) {}

    @TestConfiguration(proxyBeanMethods=false)
    static class VerifierConfiguration {
        @Bean @Primary OtpCodeGeneratorPort customerHttpOtpCode() { return () -> "123456"; }
        @Bean @Primary RecordingIdentityTokenVerifier customerHttpVerifier() {
            return new RecordingIdentityTokenVerifier();
        }
        @Bean @Primary RecordingPublicAuthenticationRateLimiter customerHttpRateLimiter() {
            return new RecordingPublicAuthenticationRateLimiter();
        }
        @Bean @Primary RecordingLoadSitePort customerHttpSiteLoader(SitePersistenceAdapter delegate) {
            return new RecordingLoadSitePort(delegate);
        }
        @Bean RecordingPublicAuthenticationHandlerInterceptor customerAuthenticationHandlerRecorder() {
            return new RecordingPublicAuthenticationHandlerInterceptor();
        }
        @Bean WebMvcConfigurer customerAuthenticationHandlerRecordingConfigurer(
                RecordingPublicAuthenticationHandlerInterceptor recorder) {
            return new WebMvcConfigurer() {
                @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(recorder); }
            };
        }
    }

    static final class RecordingIdentityTokenVerifier implements IdentityTokenVerifierPort {
        private final AtomicInteger oidcCalls=new AtomicInteger();
        private final AtomicInteger siteTokenCalls=new AtomicInteger();
        @Override public VerifiedIdentityEvidence verifyOidc(IdentityProviderConfiguration configuration,String token,String nonce,Instant now) {
            oidcCalls.incrementAndGet();
            String[] parts=token.split(":",-1);
            if(parts.length!=4 || !"oidc".equals(parts[0]) || !nonce.equals(parts[3])) throw failed();
            return new VerifiedIdentityEvidence(parts[1],configuration.providerKey(),Optional.empty(),Optional.of(parts[2]),now.minusSeconds(1),now.plusSeconds(120));
        }
        @Override public VerifiedIdentityEvidence verifySiteToken(IdentityProviderConfiguration configuration,String token,Instant now) {
            siteTokenCalls.incrementAndGet();
            String[] parts=token.split(":",-1);
            if(parts.length!=3 || !"site".equals(parts[0])) throw failed();
            return new VerifiedIdentityEvidence(parts[1],configuration.providerKey(),Optional.empty(),Optional.of(parts[2]),now.minusSeconds(1),now.plusSeconds(120));
        }
        void reset() { oidcCalls.set(0);siteTokenCalls.set(0); }
        int oidcCalls() { return oidcCalls.get(); }
        int siteTokenCalls() { return siteTokenCalls.get(); }
        int totalCalls() { return oidcCalls()+siteTokenCalls(); }
        private PaymentApplicationException failed() { return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed"); }
    }

    static final class RecordingPublicAuthenticationRateLimiter implements PublicCustomerAuthenticationRateLimitPort {
        private final EnumMap<Operation,AtomicInteger> acquisitions=new EnumMap<>(Operation.class);
        private InMemoryCustomerAuthenticationRateLimitAdapter delegate;
        RecordingPublicAuthenticationRateLimiter() {
            for(var operation:Operation.values()) acquisitions.put(operation,new AtomicInteger());
            reset();
        }
        @Override public Decision acquire(Key key) {
            acquisitions.get(key.operation()).incrementAndGet();
            return delegate.acquire(key);
        }
        void reset() {
            acquisitions.values().forEach(counter -> counter.set(0));
            delegate=new InMemoryCustomerAuthenticationRateLimitAdapter(new TestClock(),20,30,30,20,10_000);
        }
        int acquisitions(Operation operation) { return acquisitions.get(operation).get(); }
        int totalAcquisitions() { return acquisitions.values().stream().mapToInt(AtomicInteger::get).sum(); }
    }

    static final class RecordingPublicAuthenticationHandlerInterceptor implements HandlerInterceptor {
        private final AtomicInteger calls=new AtomicInteger();
        @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,Object handler) {
            if(handler instanceof HandlerMethod method
                    && PublicCustomerAuthenticationController.class.isAssignableFrom(method.getBeanType())) calls.incrementAndGet();
            return true;
        }
        void reset() { calls.set(0); }
        int calls() { return calls.get(); }
    }

    static final class RecordingLoadSitePort implements LoadSitePort {
        private final LoadSitePort delegate;
        private final AtomicInteger calls=new AtomicInteger();
        RecordingLoadSitePort(LoadSitePort delegate) { this.delegate=delegate; }
        @Override public Optional<Site> findById(SiteId id) { calls.incrementAndGet();return delegate.findById(id); }
        @Override public Optional<Site> findByKey(SiteKey key) { calls.incrementAndGet();return delegate.findByKey(key); }
        @Override public boolean existsByKey(SiteKey key) { calls.incrementAndGet();return delegate.existsByKey(key); }
        @Override public List<Site> findAll() { calls.incrementAndGet();return delegate.findAll(); }
        void reset() { calls.set(0); }
        int totalCalls() { return calls.get(); }
    }
}
