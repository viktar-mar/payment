package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("local")
@TestPropertySource(properties={"payment.customer-local-issuer.enabled=true","payment.customer-local-issuer.private-jwk-set=../infrastructure/fixtures/customer-issuer-private.jwks.json"})
class LocalCustomerIssuerIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Test void adminOnlyLocalFixtureSignsBothApprovedAlgorithmsAndNeverPublishesPrivateKeys() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        assertThat(mvc.perform(get("/api/v1/admin/local/customer-issuer/public-keys")).andReturn().getResponse().getStatus()).isEqualTo(401);
        var admin=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("fixture-admin").roles("ADMIN");
        var response=mvc.perform(get("/api/v1/admin/local/customer-issuer/public-keys").with(admin)).andReturn().getResponse();
        assertThat(response.getStatus()).as("local public-key endpoint").isEqualTo(200);
        var publicKeys=JWKSet.parse(response.getContentAsString());
        assertThat(publicKeys.getKeys()).hasSize(2).allMatch(k -> !k.isPrivate());
        var json=JsonMapper.builder().build();
        for(String algorithm:new String[]{"RS256","ES256"}) {
            response=mvc.perform(post("/api/v1/admin/local/customer-issuer/tokens").with(admin)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content("{\"algorithm\":\""+algorithm+"\",\"subject\":\"local-fixture-subject\",\"nonce\":\"local-fixture-nonce\"}"))
                .andReturn().getResponse();
            assertThat(response.getStatus()).as("local signed fixture response").isEqualTo(200);
            var jwt=SignedJWT.parse(json.readTree(response.getContentAsString()).get("token").asString());
            var key=publicKeys.getKeyByKeyId(jwt.getHeader().getKeyID());
            boolean valid=key instanceof RSAKey rsa?jwt.verify(new RSASSAVerifier(rsa)):jwt.verify(new ECDSAVerifier((ECKey)key));
            assertThat(valid).as("approved signature verifies").isTrue();
            assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(INITIAL_TIME.plusSeconds(120));
        }
    }
}
