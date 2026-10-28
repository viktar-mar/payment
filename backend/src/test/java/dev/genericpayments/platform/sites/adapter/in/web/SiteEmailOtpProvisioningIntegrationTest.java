package dev.genericpayments.platform.sites.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.admin.adapter.out.persistence.AdminPersistenceAdapter;
import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.customers.application.service.IdentityProviderConfigurationService;
import dev.genericpayments.platform.customers.application.port.in.ManageIdentityProviderConfigurationUseCase.*;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.support.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

@Import(SiteEmailOtpProvisioningIntegrationTest.AuditConfiguration.class)
@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
class SiteEmailOtpProvisioningIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AuditProbe auditProbe;
    @Autowired @Qualifier("customerEmailOtpReadiness") HealthIndicator readiness;
    @MockitoSpyBean IdentityProviderConfigurationService providers;
    AdminApiClient api;
    String account;
    static final String INLINE="\"email_otp_provider\":{\"provider_key\":\"mail-login\",\"sender_ref\":\"test-default\",\"template_key\":\"otp-default\"}";

    @BeforeEach void prepare() throws Exception {
        auditProbe.failure=null; auditProbe.observe=()->{};
        clearDatabase(); bootstrap.initialize(); api=new AdminApiClient(context); api.login();
        account=api.account("atomic-account");
    }

    // A split Site/provider commit makes this fail at HTTP201 or immediate readiness.
    @Test void inlineProviderCommitsWithSiteAndBothCorrelatedAuditsAndImmediateReadiness() throws Exception {
        var observations=new ArrayList<Integer>();
        auditProbe.observe=()-> {
            try(var connection=dataSource.getConnection(); var statement=connection.createStatement();
                var result=statement.executeQuery("select (select count(*) from sites)+(select count(*) from identity_provider_configurations)+(select count(*) from audit_log where action in ('SITE_CREATED','IDENTITY_PROVIDER_CREATED'))")) {
                result.next(); observations.add(result.getInt(1));
            } catch(Exception error) { throw new IllegalStateException(error); }
        };
        String response=api.postJson("/api/admin/v1/sites",body("EMAIL_OTP",INLINE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.email_otp_provider").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(response,"$.id");
        assertThat(observations).containsExactly(0,0);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
        assertThat(jdbc.queryForObject("select count(*) from sites",Integer.class)).isOne();
        api.getJson("/api/v1/admin/sites/"+id+"/identity-providers").andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].provider_key").value("mail-login"))
            .andExpect(jsonPath("$[0].readiness").value("READY")).andExpect(jsonPath("$[0].mode").value("EMAIL_OTP"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action in ('SITE_CREATED','IDENTITY_PROVIDER_CREATED') and request_id='catalog-test' and actor_type='ADMIN' and actor_id=(select id::text from admin_users)",Integer.class)).isEqualTo(2);
        Map<String,Object> providerAudit=jdbc.queryForMap("select resource_type,resource_id,details::text from audit_log where action='IDENTITY_PROVIDER_CREATED'");
        assertThat(providerAudit.get("resource_type")).isEqualTo("IDENTITY_PROVIDER");
        Map<String,String> facts=JsonPath.read((String)providerAudit.get("details"),"$");
        assertThat(facts).containsExactlyInAnyOrderEntriesOf(Map.of("site_id",id,"provider_id",providerAudit.get("resource_id").toString(),"provider_key","mail-login","mode","EMAIL_OTP","status","ACTIVE","version","0","key_count","0"));
        assertThat(providerAudit.toString()).doesNotContain("test-default","otp-default");
    }

    // Optional input must preserve the existing response, single audit and fail-closed legacy readiness.
    @ParameterizedTest @ValueSource(strings={"", "\"email_otp_provider\":null"})
    void legacyEmailSiteStillHasOneAuditAndFailsReadiness(String optional) throws Exception {
        String response=api.postJson("/api/admin/v1/sites",body("EMAIL_OTP",optional)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Map<String,Object>>read(response,"$").keySet()).containsExactlyInAnyOrder(
            "id","key","display_name","payment_account_id","status","allowed_origins","authentication_modes","payment_method_policy");
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SITE_CREATED'",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='IDENTITY_PROVIDER_CREATED'",Integer.class)).isZero();
    }

    // Missing nested validation, allowing token settings, or ignoring the Site mode writes unusable state.
    @Test void malformedInlineInputAndWrongSiteModeWriteNothing() throws Exception {
        List<String> invalid=new ArrayList<>(List.of("{}","{\"provider_key\":\"mail-login\"}","[]","true"));
        String valid=INLINE.substring(INLINE.indexOf(':')+1);
        for(String field:List.of("mode","issuer","audiences","allowed_algorithms","jwks_source_type","jwks_uri","public_jwk_set","email_claim","verified_email_claim","expected_version","smtp_password"))
            invalid.add(valid.replace("}",",\""+field+"\":null}"));
        for(String field:List.of("provider_key","sender_ref","template_key")) for(String value:List.of("","ab","x".repeat(64),"bad_ref","sensitive@example.test"))
            invalid.add(valid.replace("\""+(field.equals("provider_key")?"mail-login":field.equals("sender_ref")?"test-default":"otp-default")+"\"","\""+value+"\""));
        for(String nested:invalid) {
            String response=api.postJson("/api/admin/v1/sites",body("EMAIL_OTP","\"email_otp_provider\":"+nested))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("sensitive@example.test"); assertNoNewState();
        }
        api.postJson("/api/admin/v1/sites",body("SITE_TOKEN",INLINE)).andExpect(status().isBadRequest()); assertNoNewState();
    }

    // Configuration-only readiness must reject valid but unavailable mail references and roll back saved state.
    @ParameterizedTest @ValueSource(strings={"test-default","otp-default"})
    void unavailableMailConfigurationRollsBackSiteProviderAndAudits(String reference) throws Exception {
        api.postJson("/api/admin/v1/sites",body("EMAIL_OTP",INLINE.replace(reference,"unavailable-ref")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        assertNoNewState(); assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    // Application failures after the provider write cannot leave a partially committed Site.
    @Test void providerFailureRollsBackAllNewState() throws Exception {
        org.mockito.Mockito.doAnswer(call->{call.callRealMethod(); throw new DomainValidationException("fixture provider failure");})
            .when(providers).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        api.postJson("/api/admin/v1/sites",body("EMAIL_OTP",INLINE)).andExpect(status().isBadRequest()); assertNoNewState();
    }

    // A returned non-ready view must be refused even when mail references themselves are available.
    @Test void nonReadyReturnedProviderRollsBackAllNewState() throws Exception {
        org.mockito.Mockito.doAnswer(call->{
            View v=(View)call.callRealMethod();
            return new View(v.id(),v.providerKey(),v.mode(),v.status(),v.version(),v.issuer(),v.audiences(),v.allowedAlgorithms(),v.jwksSourceType(),Readiness.NOT_SELECTED,v.keys(),v.senderRef(),v.templateKey(),v.lastSuccessfulRefreshAt(),v.jwksRefreshState());
        }).when(providers).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        api.postJson("/api/admin/v1/sites",body("EMAIL_OTP",INLINE)).andExpect(status().isBadRequest()); assertNoNewState();
    }

    // Neither audit may fail after any part of this compound mutation has committed.
    @ParameterizedTest @ValueSource(strings={"SITE_CREATED","IDENTITY_PROVIDER_CREATED"})
    void eitherAuditFailureRollsBackSiteProviderAndBothAudits(String action) throws Exception {
        auditProbe.failure=AuditAction.valueOf(action);
        api.postJson("/api/admin/v1/sites",body("EMAIL_OTP",INLINE))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("internal_error")); assertNoNewState();
    }

    // Adding the inline path must not bypass the existing session, role or CSRF gates.
    @Test void unauthorizedWrongRoleAndMissingCsrfWriteNothing() throws Exception {
        String json=body("EMAIL_OTP",INLINE);
        api.mvc.perform(post("/api/admin/v1/sites").contentType("application/json").content(json)).andExpect(status().isUnauthorized());
        api.mvc.perform(post("/api/admin/v1/sites").cookie(api.session).contentType("application/json").content(json)).andExpect(status().isForbidden());
        try {
            api.mvc.perform(post("/api/admin/v1/sites").with(user("partner").roles("PARTNER"))
                .cookie(api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue()).contentType("application/json").content(json))
                .andExpect(status().isForbidden());
        } finally {
            org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        }
        assertNoNewState();
    }

    // Publishing the new input as required would break every existing Site client.
    @Test void openApiKeepsInlineProviderOptionalAndExposesOnlyTheThreeMailFields() throws Exception {
        String document=api.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> required=JsonPath.read(document,"$.components.schemas.CreateSiteRequest.required");
        assertThat(required).doesNotContain("email_otp_provider");
        Map<String,Object> fields=JsonPath.read(document,"$.components.schemas.EmailOtpProvider.properties");
        assertThat(fields.keySet()).containsExactlyInAnyOrder("provider_key","sender_ref","template_key");
    }

    String body(String mode,String optional) {
        return """
            {"key":"atomic-site","display_name":"Atomic Site","payment_account_id":"%s","allowed_origins":["https://atomic.example.test"],
             "authentication_modes":["%s"],"payment_method_policy":{"mode":"AUTOMATIC","methods":[]}%s}
            """.formatted(account,mode,optional.isEmpty()?"":","+optional);
    }
    void assertNoNewState() {
        for(String table:List.of("sites","site_allowed_origins","site_authentication_modes","identity_provider_configurations"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action in ('SITE_CREATED','IDENTITY_PROVIDER_CREATED')",Integer.class)).isZero();
    }
    static class AuditProbe { AuditAction failure; Runnable observe=()->{}; }
    @TestConfiguration(proxyBeanMethods=false)
    static class AuditConfiguration {
        @Bean AuditProbe auditProbe() { return new AuditProbe(); }
        @Bean @Primary AuditLogPort atomicAudit(AdminPersistenceAdapter delegate,AuditProbe probe) {
            return entry->{
                delegate.append(entry);
                if(entry.action()==AuditAction.SITE_CREATED || entry.action()==AuditAction.IDENTITY_PROVIDER_CREATED) {
                    probe.observe.run();
                    if(entry.action()==probe.failure) throw new IllegalStateException("fixture audit failure");
                }
            };
        }
    }
}
