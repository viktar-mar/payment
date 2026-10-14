package dev.genericpayments.platform.customers.adapter.out.observability;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.CustomerMailpitIntegrationTest;
import dev.genericpayments.platform.customers.application.service.CustomerMailDeliveryService;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class CustomerObservabilityIntegrationTest extends CustomerMailpitIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired MeterRegistry meters;
    @Autowired CustomerMailDeliveryService delivery;
    @Autowired AnonymizeCustomerUseCase anonymization;
    private MockMvc mvc;
    private RSAKey key;
    private SiteId siteId;
    private final String origin="https://telemetry.example.test";

    @BeforeEach void fixture() throws Exception {
        clearDatabase();
        meters.getMeters().stream().filter(m -> m.getId().getType()!=io.micrometer.core.instrument.Meter.Type.GAUGE).toList().forEach(meters::remove);
        clearMailpit();
        mvc=MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter.class)).apply(springSecurity()).build();
        key=new RSAKeyGenerator(2048).keyID("local-test-only").generate();
        UUID account=UUID.randomUUID(),site=UUID.randomUUID();
        siteId=new SiteId(site);
        jdbc.update("insert into payment_accounts values (?,'telemetry-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,'telemetry-site','Telemetry',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site,account);
        jdbc.update("insert into site_allowed_origins values (?,?)",site,origin);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site);
        jdbc.update("insert into site_authentication_modes values (?,'EMAIL_OTP')",site);
        jdbc.update("insert into site_authentication_modes values (?,'OIDC_TOKEN')",site);
        providers.save(IdentityProviderConfiguration.create(new SiteId(site),"email",new IdentityProviderConfiguration.EmailOtpConfiguration("local-test","otp-default")));
        providers.save(IdentityProviderConfiguration.create(new SiteId(site),"signed",new IdentityProviderConfiguration.SiteTokenConfiguration(
            "https://telemetry-issuer.example.test",Set.of("web"),new JwksSource.StaticPublicJwkSet("test-public"),Set.of("RS256"))),
            new IdentityProviderConfigurationStore.PublicJwkSet(new JWKSet(key.toPublicJWK()).toString()));
        providers.save(IdentityProviderConfiguration.create(new SiteId(site),"oidc",new IdentityProviderConfiguration.OidcTokenConfiguration(
            "https://telemetry-issuer.example.test",Set.of("web"),new JwksSource.StaticPublicJwkSet("test-public"),Set.of("RS256"),null)),
            new IdentityProviderConfigurationStore.PublicJwkSet(new JWKSet(key.toPublicJWK()).toString()));
    }
    @Test void signedOidcAttemptEmitsBoundedAuthenticationLatencyAndCounter() throws Exception {
        var attempt=json(post("/api/v1/public/customer-auth/oidc/attempts").header("Origin",origin).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","provider_key","oidc","intent","AUTHENTICATE"))),201);
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),new JWTClaimsSet.Builder()
            .issuer("https://telemetry-issuer.example.test").audience("web").subject("oidc-sensitive-subject")
            .claim("nonce",attempt.get("nonce").asString()).issueTime(Date.from(INITIAL_TIME))
            .expirationTime(Date.from(INITIAL_TIME.plusSeconds(120))).build());jwt.sign(new RSASSASigner(key));
        json(post("/api/v1/public/customer-auth/oidc/exchanges").header("Origin",origin).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","provider_key","oidc","intent","AUTHENTICATE",
                "attempt_id",attempt.get("attempt_id").asString(),"nonce",attempt.get("nonce").asString(),"token",jwt.serialize()))),200);
        assertThat(meters.find("customer.auth.exchange").tags("mode","oidc_token","outcome","success").counter()).as("signed OIDC metric").isNotNull();
        assertThat(meters.find("customer.auth.latency").tags("mode","oidc_token","outcome","success").timer().count()).isOne();
    }
    @Test void realLinksAndAnonymizationEmitOnlySafeOutcomeMetrics() throws Exception {
        var first=exchange("owner","exchanges");
        String session=first.get("session_token").asString();
        String customer=first.get("customer").get("id").asString();
        link(session,exchange("extra","link-proofs").get("proof_token").asString(),201);
        link(session,exchange("extra","link-proofs").get("proof_token").asString(),200);
        exchange("other-owner","exchanges");
        link(session,exchange("other-owner","link-proofs").get("proof_token").asString(),409);
        assertThat(meters.find("customer.identity.link").tag("outcome","success").counter()).as("linked counter").isNotNull();
        assertThat(meters.find("customer.identity.link").tag("outcome","already_linked").counter().count()).isOne();
        assertThat(meters.find("customer.identity.link").tag("outcome","conflict").counter().count()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(2);
        anonymization.anonymize(new AnonymizeCustomerUseCase.Command(siteId,new CustomerId(UUID.fromString(customer)),UUID.fromString(customer),
            "telemetry-anonymize","ADMIN","fixture-admin","telemetry-audit","198.51.100.77"));
        assertThat(meters.find("customer.anonymization").tag("outcome","success").counter().count()).isOne();
    }
    @Test void customerAuditSerializationMustNotRetainRawSourceIp(CapturedOutput output) throws Exception {
        var first=exchange("telemetry-sensitive-subject","exchanges");
        String session=first.get("session_token").asString();
        String customer=first.get("customer").get("id").asString();
        link(session,exchange("linked-subject","link-proofs").get("proof_token").asString(),201);
        anonymization.anonymize(new AnonymizeCustomerUseCase.Command(siteId,new CustomerId(UUID.fromString(customer)),UUID.fromString(customer),
            "telemetry-audit-anonymize","ADMIN","fixture-admin","telemetry-audit","198.51.100.77"));
        String audit=JSON.writeValueAsString(jdbc.queryForList("select * from audit_log"));
        assertSafe(audit+output.getAll(),"198.51.100.77","telemetry-sensitive-subject",session);
    }
    private tools.jackson.databind.JsonNode exchange(String subject,String endpoint) throws Exception {
        return json(post("/api/v1/public/customer-auth/site-token/"+endpoint).header("Origin",origin).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","provider_key","signed","token",token(subject)))),200);
    }
    private void link(String session,String proof,int expected) throws Exception {
        json(post("/api/v1/customer/identities").header("Origin",origin).header("Authorization","Bearer "+session)
            .with(request -> {request.setRemoteAddr("198.51.100.77");return request;})
            .contentType("application/json").content(JSON.writeValueAsString(Map.of("proof_token",proof))),expected);
    }
    @Test void realSmtpOtpOutcomesAndLogoutAreBoundedAndSensitiveValuesStayAbsent(CapturedOutput output) throws Exception {
        String email="sensitive@example.test";
        String challenge=json(post("/api/v1/public/customer-auth/email-otp/challenges").header("Origin",origin)
            .with(request -> {request.setRemoteAddr("198.51.100.77");return request;})
            .contentType("application/json").content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","email",email,"intent","AUTHENTICATE"))),202).get("challenge_id").asString();
        assertThat(meters.find("customer.otp").tag("outcome","requested").counter()).as("actual OTP request metric").isNotNull();
        assertThat(meters.find("customer.mail.pending").gauge().value()).isEqualTo(1);
        assertThat(delivery.deliverReady("telemetry-test")).isOne();
        String code=mailpitCode(email);
        assertThat(meters.find("customer.mail.delivery").tag("mail_outcome","delivered").counter().count()).isOne();
        assertThat(meters.find("customer.mail.pending").gauge().value()).isZero();
        json(post("/api/v1/public/customer-auth/email-otp/challenges/"+challenge+"/verify").header("Origin",origin).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","code",code.equals("000000")?"000001":"000000"))),401);
        String session=json(post("/api/v1/public/customer-auth/email-otp/challenges/"+challenge+"/verify").header("Origin",origin).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","code",code))),200).get("session_token").asString();
        assertThat(meters.find("customer.otp").tag("outcome","verified").counter().count()).isOne();
        assertThat(meters.find("customer.otp").tag("outcome","rejected").counter().count()).isOne();
        for(int i=0;i<5;i++)mvc.perform(post("/api/v1/public/customer-auth/email-otp/challenges").header("Origin",origin).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("site_key","telemetry-site","email",email,"intent","AUTHENTICATE"))));
        assertThat(meters.find("customer.otp").tag("outcome","rate_limited").counter().count()).isOne();
        json(delete("/api/v1/customer/session").header("Origin",origin).header("Authorization","Bearer "+session),204);
        assertThat(meters.find("customer.session").tag("operation","revoke").counter().count()).isOne();
        String audit=JSON.writeValueAsString(jdbc.queryForList("select * from audit_log"));
        assertSafe(audit+output.getAll(),email,code,session,"198.51.100.77");
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private tools.jackson.databind.JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("safe HTTP status").isEqualTo(status);
        return response.getContentAsString().isBlank()?JSON.nullNode():JSON.readTree(response.getContentAsString());
    }

    @Test void realExchangeRecordsBoundedLatencyOutcomesAndSessionLifecycleWithoutLeaking(CapturedOutput output) throws Exception {
        String source=token();
        var response=mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges").header("Origin",origin)
            .contentType("application/json").content(JsonMapper.builder().build().writeValueAsString(Map.of("site_key","telemetry-site","provider_key","signed","token",source)))).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        String session=JsonMapper.builder().build().readTree(response.getResponse().getContentAsString()).get("session_token").asString();
        assertThat(meters.find("customer.auth.exchange").tags("mode","site_token","outcome","success").counter())
            .as("successful real exchange must emit its counter").isNotNull();
        assertThat(meters.find("customer.auth.latency").tags("mode","site_token","outcome","success").timer().count()).isOne();
        assertThat(meters.find("customer.session").tag("operation","issue").counter().count()).isEqualTo(1);
        mvc.perform(post("/api/v1/public/customer-auth/site-token/exchanges").header("Origin",origin).contentType("application/json")
            .content(JsonMapper.builder().build().writeValueAsString(Map.of("site_key","telemetry-site","provider_key","signed","token",source))));
        assertThat(meters.find("customer.auth.exchange").tags("mode","site_token","outcome","rejected").counter().count()).isEqualTo(1);
        TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(31)));
        assertThat(mvc.perform(get("/api/v1/customer/me").header("Origin",origin).header("Authorization","Bearer "+session)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(meters.find("customer.session").tag("operation","expire").counter().count()).isEqualTo(1);
        for(var meter:meters.getMeters()) if(meter.getId().getName().startsWith("customer.")) {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("mode","outcome","mail_outcome","operation"));
        }
        String audit=JsonMapper.builder().build().writeValueAsString(jdbc.queryForList("select * from audit_log"));
        assertSafe(audit+output.getAll(),source,session,"telemetry-sensitive-subject","sensitive@example.test","198.51.100.77");
    }
    private static void assertSafe(String captured,String... forbidden) {
        assertThat(Arrays.stream(forbidden).noneMatch(captured::contains)).as("no sensitive fixture values in audit or logs").isTrue();
    }
    private String token() throws Exception {
        return token("telemetry-sensitive-subject");
    }
    private String token(String subject) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),new JWTClaimsSet.Builder()
            .issuer("https://telemetry-issuer.example.test").audience("web").subject(subject)
            .issueTime(Date.from(INITIAL_TIME)).expirationTime(Date.from(INITIAL_TIME.plusSeconds(120))).jwtID(UUID.randomUUID().toString()).build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
}
