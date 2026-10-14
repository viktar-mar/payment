package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.*;
import com.jayway.jsonpath.JsonPath;
import java.net.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

@Import(IdentityProviderAdminJwksDiagnosticsIntegrationTest.TlsConfiguration.class)
@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
class IdentityProviderAdminJwksDiagnosticsIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwksHttpsFixture tls;
    @Autowired SafeJwksClient client;
    @Autowired IdentityProviderConfigurationStore providers;
    AdminApiClient api;
    SiteId site;
    String base;

    @BeforeEach void prepare() throws Exception {
        clearDatabase(); bootstrap.initialize(); api=new AdminApiClient(context); api.login();
        site=new SiteId(UUID.randomUUID()); UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'jwks-admin','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'JWKS Admin',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),"jwks-admin",account);
        jdbc.update("insert into site_allowed_origins values (?,?)",site.value(),"https://shop.example.test");
        jdbc.update("insert into site_authentication_modes values (?,'OIDC_TOKEN')",site.value());
        base="/api/v1/admin/sites/"+site.value()+"/identity-providers";
    }

    @Test void adminReadinessRequiresCompatibleValidatedCacheEvidenceAndTracksCurrentSourceReplacement() throws Exception {
        String uri=tls.uri()+"/"+UUID.randomUUID();
        int initial=tls.requests.get();
        String created=api.postJson(base,body("diagnostic-oidc",uri,"RS256",null)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.readiness").value("CONFIGURED_UNVERIFIED"))
            .andExpect(jsonPath("$.jwks_refresh_state").value("NEVER_ATTEMPTED")).andReturn().getResponse().getContentAsString();
        assertThat(tls.requests.get()).isEqualTo(initial);
        String id=JsonPath.read(created,"$.id");
        client.resolve(providers.find(site,"diagnostic-oidc").orElseThrow(),"rsa-key");
        String fetched=api.getJson(base+"/"+id).andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"))
            .andExpect(jsonPath("$.jwks_refresh_state").value("CURRENT"))
            .andExpect(jsonPath("$.last_successful_refresh_at").value(INITIAL_TIME.toString()))
            .andReturn().getResponse().getContentAsString();
        assertThat(fetched).doesNotContain(uri,"jwks_uri","public_jwk_set","email_claim","verified_email_claim");
        api.postJson(base,body("incompatible-oidc",uri,"ES256",null)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.readiness").value("CONFIGURED_UNVERIFIED"))
            .andExpect(jsonPath("$.jwks_refresh_state").value("CURRENT"));
        api.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base+"/"+id)
            .cookie(api.session,api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue()).contentType("application/json")
            .content(body("diagnostic-oidc",uri+"/replacement","RS256",0L)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.jwks_refresh_state").value("NEVER_ATTEMPTED"))
            .andExpect(jsonPath("$.last_successful_refresh_at").isEmpty())
            .andExpect(jsonPath("$.readiness").value("CONFIGURED_UNVERIFIED"));
        assertThat(tls.requests.get()).isEqualTo(initial+1);
    }

    private static String body(String key,String uri,String algorithm,Long version) {
        return """
            {"provider_key":"%s","mode":"OIDC_TOKEN","issuer":"https://issuer.example.test","audiences":["customer"],
             "allowed_algorithms":["%s"],"jwks_source_type":"REMOTE","jwks_uri":"%s"%s}
            """.formatted(key,algorithm,uri,version==null?"":",\"expected_version\":"+version);
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class TlsConfiguration {
        @Bean(destroyMethod="close") JwksHttpsFixture jwksTestTls() throws Exception { return new JwksHttpsFixture(); }
        @Bean @Primary SafeJwksClient diagnosticTestClient(IdentityProviderConfigurationStore store,Clock clock,JwksHttpsFixture tls) {
            return new SafeJwksClient(store,new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getLoopbackAddress()},
                Set.of("test"),Set.of("keys.test")),clock,tls.clientContext);
        }
    }
}
