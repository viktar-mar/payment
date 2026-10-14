package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.admin.adapter.out.persistence.AdminPersistenceAdapter;
import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.support.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

@Import(IdentityProviderAdminAuditRollbackIntegrationTest.FailingAuditConfiguration.class)
@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
class IdentityProviderAdminAuditRollbackIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    AdminApiClient api;
    UUID siteId;

    @BeforeEach void prepare() throws Exception {
        clearDatabase();
        bootstrap.initialize();
        api=new AdminApiClient(context); api.login();
        UUID account=UUID.randomUUID(); siteId=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'provider-audit','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'Provider Audit',?,'ACTIVE','AUTOMATIC',now(),now(),0)",siteId,"provider-audit",account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",siteId,"https://shop.example.test");
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN')",siteId);
    }

    @Test void auditFailureRollsBackProviderMutation() throws Exception {
        RSAKey rsa=new RSAKeyGenerator(2048).keyID("rollback-rsa").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        String keys=new JWKSet(List.of(rsa.toPublicJWK())).toString(false);
        api.postJson("/api/v1/admin/sites/"+siteId+"/identity-providers","""
            {"provider_key":"rollback-oidc","mode":"OIDC_TOKEN","issuer":"https://issuer.example.test",
             "audiences":["customer"],"allowed_algorithms":["RS256"],"jwks_source_type":"STATIC_PUBLIC",
             "public_jwk_set":%s,"email_claim":"email","verified_email_claim":"email_verified"}
            """.formatted(keys))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("internal_error"));

        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isZero();
    }

    @Test void auditFailureRollsBackEmailProviderWithoutExposingMailReferences() throws Exception {
        jdbc.update("insert into site_authentication_modes values (?,'EMAIL_OTP')",siteId);
        String response=api.postJson("/api/v1/admin/sites/"+siteId+"/identity-providers","""
            {"provider_key":"rollback-email","mode":"EMAIL_OTP","sender_ref":"test-default","template_key":"otp-default"}
            """).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("internal_error"))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("test-default","otp-default");
        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isZero();
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class FailingAuditConfiguration {
        @Bean @Primary AuditLogPort failingIdentityProviderAudit(AdminPersistenceAdapter delegate) {
            return entry -> {
                if(entry.action()==AuditAction.IDENTITY_PROVIDER_CREATED) throw new IllegalStateException("fixture audit failure");
                delegate.append(entry);
            };
        }
    }
}
