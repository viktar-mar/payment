package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
class IdentityProviderAdminApiIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceAdapter providers;
    AdminApiClient api;
    UUID siteId;
    RSAKey rsa;
    String base;

    @Test void emailProviderListLoadsItsPopulationOnceAndReusesTheSelection() throws Exception {
        enableEmail();
        api.postJson(base,emailCreateBody("a-mail","test-default","missing-template")).andExpect(status().isCreated());
        api.postJson(base,emailCreateBody("b-mail","test-default","otp-default")).andExpect(status().isCreated());
        String disabled=JsonPath.read(api.postJson(base,emailCreateBody("c-mail","test-default","otp-default"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");
        post(base+"/"+disabled+"/status","{\"status\":\"DISABLED\",\"expected_version\":0}").andExpect(status().isOk());
        org.mockito.Mockito.clearInvocations(providers);
        api.getJson(base).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.provider_key == 'a-mail')].readiness").value(org.hamcrest.Matchers.contains("CONFIGURATION_UNAVAILABLE")))
            .andExpect(jsonPath("$[?(@.provider_key == 'b-mail')].readiness").value(org.hamcrest.Matchers.contains("NOT_SELECTED")))
            .andExpect(jsonPath("$[?(@.provider_key == 'c-mail')].readiness").value(org.hamcrest.Matchers.contains("DISABLED")));
        var site=new dev.genericpayments.platform.sites.domain.SiteId(siteId);
        org.mockito.Mockito.verify(providers).listStored(site);
        org.mockito.Mockito.verify(providers,org.mockito.Mockito.never()).list(site);
    }

    @BeforeEach void prepare() throws Exception {
        clearDatabase();
        bootstrap.initialize();
        api=new AdminApiClient(context); api.login();
        UUID account=UUID.randomUUID(); siteId=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'provider-admin','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'Provider Admin',?,'ACTIVE','AUTOMATIC',now(),now(),0)",siteId,"provider-admin",account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",siteId,"https://shop.example.test");
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN'),(?,'SITE_TOKEN')",siteId,siteId);
        rsa=new RSAKeyGenerator(2048).keyID("admin-rsa").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        base="/api/v1/admin/sites/"+siteId+"/identity-providers";
    }

    @Test void staticProviderLifecycleReturnsOnlySafeMetadataUsesOptimisticVersionAndAuditsSafely() throws Exception {
        String keys=new JWKSet(List.of(rsa.toPublicJWK())).toString(false);
        String created=api.postJson(base,oidcBody("issuer.example.test",0,keys))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.provider_key").value("customer-oidc"))
            .andExpect(jsonPath("$.readiness").value("READY")).andExpect(jsonPath("$.keys[0].kid").value("admin-rsa"))
            .andExpect(jsonPath("$.keys[0].kty").value("RSA")).andExpect(jsonPath("$.keys[0].algorithm").value("RS256"))
            .andExpect(jsonPath("$.keys[0].fingerprint").value(fingerprint(rsa.toPublicJWK())))
            .andExpect(jsonPath("$.public_jwk_set").doesNotExist()).andReturn().getResponse().getContentAsString();
        String providerId=JsonPath.read(created,"$.id");
        assertThat(created).doesNotContain(rsa.getModulus().toString(),keys);

        api.getJson(base).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(providerId));
        api.getJson(base+"/"+providerId).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        put(base+"/"+providerId,oidcBody("issuer-v2.example.test",0,keys)).andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.issuer").value("https://issuer-v2.example.test"));
        put(base+"/"+providerId,oidcBody("stale.example.test",0,keys)).andExpect(status().isConflict());
        post(base+"/"+providerId+"/status","{\"status\":\"DISABLED\",\"expected_version\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.version").value(2));

        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("select details from audit_log where action like 'IDENTITY_PROVIDER_%'").toString())
            .doesNotContain(keys,rsa.getModulus().toString(),"issuer-v2.example.test");
    }

    @Test void remoteProviderIsConfiguredUnverifiedWithoutAReachabilityCallAndWrongSiteModeIsRejected() throws Exception {
        api.postJson(base,"""
            {"provider_key":"site-issued","mode":"SITE_TOKEN","issuer":"https://site.example.test",
             "audiences":["customer"],"allowed_algorithms":["RS256"],"jwks_source_type":"REMOTE",
             "jwks_uri":"https://unresolvable.invalid/jwks"}
            """).andExpect(status().isCreated()).andExpect(jsonPath("$.readiness").value("CONFIGURED_UNVERIFIED"))
            .andExpect(jsonPath("$.jwks_uri").doesNotExist());
        UUID oidcOnly=UUID.randomUUID();
        jdbc.update("insert into sites values (?,?, 'OIDC only',?,'ACTIVE','AUTOMATIC',now(),now(),0)",oidcOnly,"oidc-only",jdbc.queryForObject("select id from payment_accounts",UUID.class));
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",oidcOnly,"https://oidc.example.test");
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN')",oidcOnly);
        api.postJson("/api/v1/admin/sites/"+oidcOnly+"/identity-providers","""
            {"provider_key":"site-issued","mode":"SITE_TOKEN","issuer":"https://site.example.test",
             "audiences":["customer"],"allowed_algorithms":["RS256"],"jwks_source_type":"REMOTE",
             "jwks_uri":"https://keys.example.test/jwks"}
            """).andExpect(status().isBadRequest());
    }

    @Test void openApiPublishesTheFiveExactOperationIds() throws Exception {
        var body=api.mvc.perform(get("/v3/api-docs"))
            .andDo(result -> assertThat(result.getResolvedException()).as("OpenAPI generation exception").isNull())
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"operationId\":\"listIdentityProviders\"","\"operationId\":\"createIdentityProvider\"",
            "\"operationId\":\"getIdentityProvider\"","\"operationId\":\"updateIdentityProvider\"",
            "\"operationId\":\"changeIdentityProviderStatus\"");
    }

    @Test void emailOtpLifecycleReturnsMailReferencesAndPreservesSafeGetAllowlists() throws Exception {
        enableEmail();
        String created=api.postJson(base,emailCreateBody("Mail-Login","test-default","otp-default"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.provider_key").value("mail-login"))
            .andExpect(jsonPath("$.sender_ref").value("test-default"))
            .andExpect(jsonPath("$.template_key").value("otp-default"))
            .andExpect(jsonPath("$.issuer").isEmpty()).andExpect(jsonPath("$.jwks_source_type").isEmpty())
            .andExpect(jsonPath("$.audiences").isEmpty()).andExpect(jsonPath("$.allowed_algorithms").isEmpty())
            .andExpect(jsonPath("$.keys").isEmpty()).andExpect(jsonPath("$.readiness").value("READY"))
            .andExpect(jsonPath("$.jwks_refresh_state").value("NOT_APPLICABLE"))
            .andExpect(jsonPath("$.last_successful_refresh_at").isEmpty())
            .andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(created,"$.id");
        String fetched=api.getJson(base+"/"+id).andExpect(status().isOk())
            .andExpect(jsonPath("$.sender_ref").value("test-default")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Map<String,Object>>read(fetched,"$").keySet()).containsExactlyInAnyOrder(
            "id","provider_key","mode","status","version","issuer","audiences","allowed_algorithms","jwks_source_type",
            "readiness","keys","sender_ref","template_key","last_successful_refresh_at","jwks_refresh_state");
        put(base+"/"+id,emailBody("mail-login","local-default","otp-default",0))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.readiness").value("CONFIGURATION_UNAVAILABLE"));
        put(base+"/"+id,emailBody("mail-login","test-default","otp-default",0)).andExpect(status().isConflict());
        post(base+"/"+id+"/status","{\"status\":\"DISABLED\",\"expected_version\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("DISABLED"));
        post(base+"/"+id+"/status","{\"status\":\"ACTIVE\",\"expected_version\":2}").andExpect(status().isBadRequest());
        post(base+"/"+id+"/status","{\"status\":\"DISABLED\",\"expected_version\":2}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("select details from audit_log where action like 'IDENTITY_PROVIDER_%'").toString())
            .doesNotContain("test-default","local-default","otp-default");
    }

    @Test void mixedListsUseTheDeterministicEmailSelectorAndExposeUnavailableConfiguration() throws Exception {
        enableEmail();
        String later=api.postJson(base,emailCreateBody("z-mail","test-default","otp-default")).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String earlier=api.postJson(base,emailCreateBody("a-mail","test-default","missing-template")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.readiness").value("CONFIGURATION_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        String laterId=JsonPath.read(later,"$.id"); String earlierId=JsonPath.read(earlier,"$.id");
        api.getJson(base+"/"+laterId).andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("NOT_SELECTED"));
        api.postJson(base,oidcBody("mixed.example.test",0,new JWKSet(List.of(rsa.toPublicJWK())).toString(false)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sender_ref").isEmpty()).andExpect(jsonPath("$.template_key").isEmpty());
        api.getJson(base).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].readiness").value("CONFIGURATION_UNAVAILABLE"))
            .andExpect(jsonPath("$[2].readiness").value("NOT_SELECTED"));
        post(base+"/"+earlierId+"/status","{\"status\":\"DISABLED\",\"expected_version\":0}").andExpect(status().isOk());
        api.getJson(base+"/"+laterId).andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"));
    }

    @Test void emailProviderOwnershipModeAndImmutableFactsAreEnforced() throws Exception {
        api.postJson(base,emailCreateBody("email-login","test-default","otp-default")).andExpect(status().isBadRequest());
        enableEmail();
        String created=api.postJson(base,emailCreateBody("Email-Login","test-default","otp-default"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(created,"$.id");
        api.postJson(base,emailCreateBody("EMAIL-LOGIN","test-default","otp-default")).andExpect(status().isBadRequest());
        put(base+"/"+id,emailBody("renamed-login","test-default","otp-default",0)).andExpect(status().isConflict());
        put(base+"/"+id,remoteOidcBody("mode.example.test","https://keys.example.test/jwks",0).replace("customer-oidc","email-login"))
            .andExpect(status().isBadRequest());
        UUID other=site("other-email-site","Other email site");
        String wrong="/api/v1/admin/sites/"+other+"/identity-providers/"+id;
        api.getJson(wrong).andExpect(status().isBadRequest());
        put(wrong,emailBody("email-login","test-default","otp-default",0)).andExpect(status().isBadRequest());
        post(wrong+"/status","{\"status\":\"DISABLED\",\"expected_version\":0}").andExpect(status().isBadRequest());
        jdbc.update("update sites set status='DISABLED' where id=?",siteId);
        api.getJson(base).andExpect(status().isBadRequest());
        api.postJson(base,emailCreateBody("new-email","test-default","otp-default")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isOne();
    }

    @Test void invalidAndMixedEmailRequestsFailUniformlyWithoutLeakingReferencesOrCredentials() throws Exception {
        enableEmail();
        List<String> fields=List.of("\"issuer\":\"https://issuer.test\"","\"audiences\":[]","\"allowed_algorithms\":[]",
            "\"jwks_source_type\":\"REMOTE\"","\"jwks_uri\":\"https://keys.test/keys\"","\"public_jwk_set\":{}",
            "\"email_claim\":\"email\"","\"verified_email_claim\":\"verified\"","\"smtp_password\":\"credential-secret\"",
            "\"expected_version\":0");
        for(String field:fields) {
            String body=emailCreateBody("email-login","test-default","otp-default").replace("}",","+field+"}");
            api.postJson(base,body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        }
        for(String sender:List.of("smtp://host","name@example.test","secret password","x".repeat(64),"bad_ref")) {
            String response=api.postJson(base,emailCreateBody("email-login",sender,"otp-default"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(sender);
        }
        for(String mailField:List.of("\"sender_ref\":\"test-default\"","\"template_key\":\"otp-default\""))
            api.postJson(base,remoteOidcBody("mix.example.test","https://keys.test/keys",0).replace("}",","+mailField+"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isZero();
    }

    @Test void safeDiagnosticsAndMailFieldsArePublishedInOpenApiWithoutChangingOperationIds() throws Exception {
        String document=api.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> view=JsonPath.read(document,"$.components.schemas.ProviderResponse.properties");
        assertThat(view).containsKeys("sender_ref","template_key","last_successful_refresh_at","jwks_refresh_state");
        Map<String,Object> request=JsonPath.read(document,"$.components.schemas.ProviderRequest.properties");
        assertThat(request).containsKeys("provider_key","allowed_algorithms","jwks_source_type","jwks_uri","public_jwk_set",
            "email_claim","verified_email_claim","expected_version","sender_ref","template_key");
        assertThat(view).doesNotContainKeys("jwks_uri","public_jwk_set","email_claim","verified_email_claim");
        for(String field:List.of("issuer","sender_ref","template_key","last_successful_refresh_at","jwks_source_type")) {
            Map<String,Object> property=JsonPath.read(document,"$.components.schemas.ProviderResponse.properties."+field);
            assertThat(Boolean.TRUE.equals(property.get("nullable")) ||
                property.get("type") instanceof Collection<?> types && types.contains("null"))
                .as("nullable response property %s",field).isTrue();
        }
    }

    @Test void remoteAndStaticDiagnosticsArePassiveAndDisabledProvidersAreNotReady() throws Exception {
        String created=api.postJson(base,remoteOidcBody("diagnostics.example.test","https://unresolvable.invalid/jwks",0))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.jwks_refresh_state").value("NEVER_ATTEMPTED"))
            .andExpect(jsonPath("$.last_successful_refresh_at").isEmpty()).andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(created,"$.id");
        api.getJson(base+"/"+id).andExpect(status().isOk()).andExpect(jsonPath("$.jwks_refresh_state").value("NEVER_ATTEMPTED"));
        post(base+"/"+id+"/status","{\"status\":\"DISABLED\",\"expected_version\":0}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("DISABLED"))
            .andExpect(jsonPath("$.jwks_refresh_state").value("NOT_APPLICABLE"));
    }

    private void enableEmail() { jdbc.update("insert into site_authentication_modes values (?,'EMAIL_OTP')",siteId); }
    private String emailCreateBody(String key,String sender,String template) {
        return emailBody(key,sender,template,0).replace(",\"expected_version\":0","");
    }
    private String emailBody(String key,String sender,String template,long version) {
        return """
            {"provider_key":"%s","mode":"EMAIL_OTP","sender_ref":"%s","template_key":"%s","expected_version":%d}
            """.formatted(key,sender,template,version);
    }

    @Test void missingAndEmptyAlgorithmAllowlistsAreSafeValidationFailures() throws Exception {
        String keys=new JWKSet(List.of(rsa.toPublicJWK())).toString(false);
        for(String algorithms:List.of("null","[]")) {
            String response=api.postJson(base,oidcBody("algorithm-validation.example.test",0,keys)
                    .replace("[\"RS256\"]",algorithms))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(keys,rsa.getModulus().toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isZero();
    }

    @Test void providerUuidIsSiteScopedAndDuplicateProviderKeyIsRejectedWithoutASecondAudit() throws Exception {
        String keys=new JWKSet(List.of(rsa.toPublicJWK())).toString(false);
        String created=api.postJson(base,oidcBody("ownership.example.test",0,keys)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String providerId=JsonPath.read(created,"$.id");

        api.postJson(base,oidcBody("duplicate.example.test",0,keys))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));

        UUID otherSite=site("provider-admin-other","Provider Admin Other");
        String wrongBase="/api/v1/admin/sites/"+otherSite+"/identity-providers/"+providerId;
        api.getJson(wrongBase).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        put(wrongBase,oidcBody("wrong-site.example.test",0,keys))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));

        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select issuer from identity_provider_configurations",String.class)).isEqualTo("https://ownership.example.test");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isOne();
    }

    @Test void privateSymmetricAndAlgorithmMismatchedStaticKeysAreRejected() throws Exception {
        String privateKeys=new JWKSet(List.of(rsa)).toString(false);
        var symmetric=new OctetSequenceKeyGenerator(256).keyID("symmetric-secret").algorithm(JWSAlgorithm.HS256).generate();
        String symmetricKeys=new JWKSet(List.of(symmetric)).toString(false);
        var ec=new ECKeyGenerator(Curve.P_256).keyID("admin-ec").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate();
        String mismatchedKeys=new JWKSet(List.of(ec.toPublicJWK())).toString(false);

        for(String keys:List.of(privateKeys,symmetricKeys,mismatchedKeys)) {
            String response=api.postJson(base,oidcBody("unsafe-key.example.test",0,keys))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(keys,"symmetric-secret",rsa.getPrivateExponent().toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from identity_provider_configurations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'IDENTITY_PROVIDER_%'",Integer.class)).isZero();
    }

    @Test void staticToRemoteSourceTransitionClearsStoredKeyMaterial() throws Exception {
        String keys=new JWKSet(List.of(rsa.toPublicJWK())).toString(false);
        String created=api.postJson(base,oidcBody("static-source.example.test",0,keys)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String providerId=JsonPath.read(created,"$.id");
        assertThat(jdbc.queryForObject("select public_jwk_set is not null from identity_provider_configurations",Boolean.class)).isTrue();

        put(base+"/"+providerId,remoteOidcBody("remote-source.example.test","https://keys.example.test/provider.jwks",0))
            .andExpect(status().isOk()).andExpect(jsonPath("$.jwks_source_type").value("REMOTE"))
            .andExpect(jsonPath("$.readiness").value("CONFIGURED_UNVERIFIED"))
            .andExpect(jsonPath("$.keys").isEmpty());

        assertThat(jdbc.queryForObject("select public_jwk_set is null and public_jwk_set_ref is null from identity_provider_configurations",Boolean.class)).isTrue();
    }

    @Test void createUpdateAndErrorResponsesAuditsAndLogsNeverExposeJwkOrRemoteLocation() throws Exception {
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured=new ListAppender<ILoggingEvent>(); captured.start(); root.addAppender(captured);
        String publicKeys=new JWKSet(List.of(rsa.toPublicJWK())).toString(false);
        String privateKeys=new JWKSet(List.of(rsa)).toString(false);
        String remoteLocation="https://keys.example.test/credential-material-do-not-log.jwks";
        try {
            String created=api.postJson(base,oidcBody("safe-response.example.test",0,publicKeys)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
            String providerId=JsonPath.read(created,"$.id");
            String updated=put(base+"/"+providerId,remoteOidcBody("safe-response.example.test",remoteLocation,0))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String error=api.postJson(base,oidcBody("unsafe-response.example.test",0,privateKeys))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

            String audit=jdbc.queryForList("select details from audit_log where action like 'IDENTITY_PROVIDER_%'").toString();
            String logs=captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(left,right)->left+'\n'+right);
            for(String exposed:List.of(publicKeys,privateKeys,remoteLocation,rsa.getModulus().toString(),rsa.getPrivateExponent().toString())) {
                assertThat(created).doesNotContain(exposed);
                assertThat(updated).doesNotContain(exposed);
                assertThat(error).doesNotContain(exposed);
                assertThat(audit).doesNotContain(exposed);
                assertThat(logs).doesNotContain(exposed);
            }
        } finally { root.detachAppender(captured); captured.stop(); }
    }

    private String oidcBody(String host,long expectedVersion,String keys) {
        return """
            {"provider_key":"customer-oidc","mode":"OIDC_TOKEN","issuer":"https://%s",
             "audiences":["customer"],"allowed_algorithms":["RS256"],"jwks_source_type":"STATIC_PUBLIC",
             "public_jwk_set":%s,"email_claim":"email","verified_email_claim":"email_verified","expected_version":%d}
            """.formatted(host,keys,expectedVersion);
    }
    private String remoteOidcBody(String host,String uri,long expectedVersion) {
        return """
            {"provider_key":"customer-oidc","mode":"OIDC_TOKEN","issuer":"https://%s",
             "audiences":["customer"],"allowed_algorithms":["RS256"],"jwks_source_type":"REMOTE",
             "jwks_uri":"%s","email_claim":"email","verified_email_claim":"email_verified","expected_version":%d}
            """.formatted(host,uri,expectedVersion);
    }
    private UUID site(String key,String name) {
        UUID value=UUID.randomUUID();
        UUID account=jdbc.queryForObject("select id from payment_accounts",UUID.class);
        jdbc.update("insert into sites values (?,?, ?,?,'ACTIVE','AUTOMATIC',now(),now(),0)",value,key,name,account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",value,"https://shop.example.test");
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN')",value);
        return value;
    }
    private org.springframework.test.web.servlet.ResultActions put(String path,String json) throws Exception {
        return api.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).cookie(api.session,api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue())
            .header("X-Request-Id","provider-admin-test").contentType("application/json").content(json));
    }
    private org.springframework.test.web.servlet.ResultActions post(String path,String json) throws Exception {
        return api.postJson(path,json);
    }
    private static String fingerprint(JWK key) throws Exception {
        return HexFormat.of().formatHex(key.computeThumbprint("SHA-256").decode());
    }
}
