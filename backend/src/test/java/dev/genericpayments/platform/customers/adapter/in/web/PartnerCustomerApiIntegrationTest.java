package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import dev.genericpayments.platform.support.AdminApiClient;

@ExtendWith(OutputCaptureExtension.class)
class PartnerCustomerApiIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context;
    @Autowired CustomerStore customers;
    @Autowired CustomerIdentityStore identities;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired KeyedDigestPort digests;
    private MockMvc mvc;
    private Customer customer;

    @BeforeEach void customerFixture() {
        mvc=new AdminApiClient(context).mvc;
        providers.save(IdentityProviderConfiguration.create(site,"site-provider",
            new IdentityProviderConfiguration.SiteTokenConfiguration("https://issuer.example.test",Set.of("customer"),
                new JwksSource.Remote(URI.create("https://issuer.example.test/jwks")),Set.of("RS256"))));
        var id=new CustomerId(UUID.randomUUID()); customer=customers.save(Customer.create(id,site,null,null,INITIAL_TIME));
    }

    @Test void customersManageScopeAndExactlyOneIdempotencyHeaderAreRequired() throws Exception {
        String path=path(customer);
        mvc.perform(post(path).header("Authorization","Bearer "+key(PartnerScope.CUSTOMERS_READ))
                .header("Idempotency-Key","scope-key").contentType("application/json").content(body("subject-a")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));
        String manage=key(PartnerScope.CUSTOMERS_MANAGE);
        mvc.perform(post(path).header("Authorization","Bearer "+manage).contentType("application/json").content(body("subject-a")))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization","Bearer "+manage).header("Idempotency-Key","one","two")
                .contentType("application/json").content(body("subject-a")))
            .andExpect(status().isBadRequest());
    }

    @Test void samePartnerOperationReplaysAndFingerprintMismatchConflictsWithoutSecretPersistence(CapturedOutput output) throws Exception {
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE),idempotency="partner-secret-idempotency";
        var first=mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key",idempotency)
                .header("X-Request-Id","partner-link-1").contentType("application/json").content(body("partner-raw-subject")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("LINKED"))
            .andExpect(jsonPath("$.identity_id").isString()).andReturn();
        String firstBody=first.getResponse().getContentAsString();
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key",idempotency)
                .contentType("application/json").content(body("partner-raw-subject")))
            .andExpect(status().isCreated()).andExpect(content().json(firstBody));
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key",idempotency)
                .contentType("application/json").content(body("different-subject")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        String stored=jdbc.queryForList("select * from customer_idempotency_records").toString()+jdbc.queryForList("select * from audit_log");
        assertThat(stored).doesNotContain(idempotency,"partner-raw-subject","different-subject");
        assertThat(output.getAll()).doesNotContain(idempotency,"partner-raw-subject","different-subject");
        assertThat(count("customer_identities")).isOne(); assertThat(count("audit_log")).isOne();
    }

    @Test void invalidProviderCrossSiteInactiveTargetAndOversizedBodyFailClosed() throws Exception {
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE);
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","wrong-provider")
                .contentType("application/json").content("{\"mode\":\"EMAIL_OTP\",\"issuer_key\":\"site-provider\",\"subject\":\"a\"}"))
            .andExpect(status().isBadRequest());
        providers.save(providers.find(site,"site-provider").orElseThrow().disable());
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","disabled-provider")
                .contentType("application/json").content(body("subject")))
            .andExpect(status().isBadRequest());
        jdbc.update("update customers set status='ANONYMIZED',anonymized_at=?,email_ciphertext=null,email_nonce=null,email_key_version=null,email_digest=null,email_digest_key_version=null where id=?",
            java.sql.Timestamp.from(INITIAL_TIME),customer.id().value());
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","inactive")
                .contentType("application/json").content(body("subject")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_AVAILABLE"));
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","oversized")
                .contentType("application/json").content("{\"mode\":\"SITE_TOKEN\",\"issuer_key\":\"site-provider\",\"subject\":\""+"x".repeat(17*1024)+"\"}"))
            .andExpect(status().isPayloadTooLarge());
    }

    @Test void crossSiteTargetAndBoundedIssuerSubjectAndHeaderInputsFailWithoutWork() throws Exception {
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE);
        var otherSite=new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID());
        jdbc.update("""
            insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version)
            values (?,'other-link-site','Other link site','ACTIVE',?,'AUTOMATIC',?,?,0)
            """,otherSite.value(),account.id().value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        Customer other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),otherSite,null,null,INITIAL_TIME));
        mvc.perform(post(path(other)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","cross-site")
                .contentType("application/json").content(body("subject")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_AVAILABLE"));
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","issuer-long")
                .contentType("application/json").content("{\"mode\":\"SITE_TOKEN\",\"issuer_key\":\""+"i".repeat(64)+"\",\"subject\":\"a\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","subject-long")
                .contentType("application/json").content(body("s".repeat(2049))))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key"," ")
                .contentType("application/json").content(body("subject")))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key","k".repeat(256))
                .contentType("application/json").content(body("subject")))
            .andExpect(status().isBadRequest());
        assertThat(count("customer_identities")).isZero();
        assertThat(count("customer_idempotency_records")).isZero();
        assertThat(count("audit_log")).isZero();
    }

    @Test void sameRawIdempotencyKeyCannotBeReusedForAnotherCustomerTarget() throws Exception {
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE),idempotency="same-key-different-target";
        Customer second=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key",idempotency)
                .contentType("application/json").content(body("first-subject")))
            .andExpect(status().isCreated());

        mvc.perform(post(path(second)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key",idempotency)
                .contentType("application/json").content(body("second-subject")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(count("customer_idempotency_records")).isOne();
        assertThat(count("customer_identities")).isOne();
        assertThat(count("audit_log")).isOne();
    }

    @Test void concurrentSameKeyClaimsReplayOneCommittedResult() throws Exception {
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE),idempotency="concurrent-partner-key";
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<org.springframework.test.web.servlet.MvcResult>> calls=new ArrayList<>();
            for(int index=0;index<2;index++) calls.add(executor.submit(() -> {
                start.await();
                return mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey)
                    .header("Idempotency-Key",idempotency).contentType("application/json").content(body("concurrent-subject"))).andReturn();
            }));
            start.countDown();
            List<org.springframework.test.web.servlet.MvcResult> results=new ArrayList<>();
            for(var call:calls) results.add(call.get(10,TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201,201);
            assertThat(results).extracting(result -> result.getResponse().getContentAsString())
                .containsOnly(results.getFirst().getResponse().getContentAsString());
        }
        assertThat(count("customer_idempotency_records")).isOne();
        assertThat(count("customer_identities")).isOne();
        assertThat(count("audit_log")).isOne();
    }

    @Test void ownershipConflictIsCompletedAndReplayedWithoutRepeatingWork() throws Exception {
        String subject="already-owned-partner-subject";
        Customer owner=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var digest=digests.digest(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject);
        String reference=digest.keyVersion()+":"+digest.value();
        identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,owner.id(),
            CustomerAuthenticationMode.SITE_TOKEN,"site-provider",reference,INITIAL_TIME),List.of(reference));
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE),idempotency="replayed-conflict-key";

        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(post(path(customer)).header("Authorization","Bearer "+apiKey).header("Idempotency-Key",idempotency)
                    .contentType("application/json").content(body(subject)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDENTITY_LINK_CONFLICT"));
        }

        assertThat(count("customer_idempotency_records")).isOne();
        assertThat(count("customer_identities")).isOne();
        assertThat(count("audit_log")).isOne();
        assertThat(jdbc.queryForObject("select response_status from customer_idempotency_records",Integer.class)).isEqualTo(409);
    }

    @Test void openApiUsesExactFieldsAndMarksSecretsWriteOnly() throws Exception {
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/customer/identities'].post.operationId").value("linkCurrentCustomerIdentity"))
            .andExpect(jsonPath("$.paths['/api/v1/partner/customers/{customerId}/identities'].post.operationId").value("linkPartnerCustomerIdentity"))
            .andReturn();
        var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        var schemas=root.at("/components/schemas");
        assertThat(schemas.get("CustomerIdentityLinkRequest").at("/properties/proof_token/writeOnly").asBoolean()).isTrue();
        assertThat(schemas.get("PartnerCustomerIdentityLinkRequest").at("/properties/subject/writeOnly").asBoolean()).isTrue();
        assertThat(fieldNames(schemas.get("CustomerIdentityLinkRequest").get("properties"))).containsExactly("proof_token");
        assertThat(fieldNames(schemas.get("PartnerCustomerIdentityLinkRequest").get("properties")))
            .containsExactlyInAnyOrder("mode","issuer_key","subject");
    }

    @Test void identityLinkPolicyDoesNotClaimSiblingPartnerCustomerRoutesButRejectsLinkAliases() throws Exception {
        String apiKey=key(PartnerScope.CUSTOMERS_MANAGE);

        mvc.perform(get("/api/v1/partner/customers/"+customer.id().value())
                .header("Authorization","Bearer "+apiKey))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));

        mvc.perform(post(path(customer)+"/").header("Authorization","Bearer "+apiKey)
                .header("Idempotency-Key","non-canonical-link-path")
                .contentType("application/json").content(body("subject")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test void readAndAnonymizationEnforceSeparateScopesAndUniformAvailability() throws Exception {
        String read=key(PartnerScope.CUSTOMERS_READ),manage=key(PartnerScope.CUSTOMERS_MANAGE);
        String target="/api/v1/partner/customers/"+customer.id().value();
        mvc.perform(get(target).header("Authorization","Bearer "+read)).andExpect(status().isOk())
            .andExpect(jsonPath("$.customer_id").value(customer.id().value().toString()))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(result -> {
                var response=tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
                assertThat(response.propertyNames()).containsExactlyInAnyOrder("customer_id","site_id","status","masked_email",
                    "created_at","updated_at","anonymized_at","identities");
                assertThat(response.path("site_id").asText()).isEqualTo(site.value().toString());
                assertThat(response.path("status").asText()).isEqualTo("ACTIVE");
                assertThat(response.path("masked_email").isNull()).isTrue();
                assertThat(response.path("anonymized_at").isNull()).isTrue();
                assertThat(response.path("identities")).isEmpty();
                for(String field:List.of("created_at","updated_at"))
                    assertThat(response.path(field).asText()).isEqualTo(INITIAL_TIME.toString());
            });
        mvc.perform(post(target+"/anonymization").header("Authorization","Bearer "+read).header("Idempotency-Key","read-cannot-erase")
            .contentType("application/json").content("{\"confirmation\":\""+customer.id().value()+"\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));
        for(String key:List.of("erase-first","erase-first","erase-new"))
            mvc.perform(post(target+"/anonymization").header("Authorization","Bearer "+manage).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"confirmation\":\""+customer.id().value()+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("ANONYMIZED"))
                .andExpect(result -> {
                    var response=tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
                    assertThat(response.propertyNames()).containsExactlyInAnyOrder("customer_id","status");
                    assertThat(response.path("customer_id").asText()).isEqualTo(customer.id().value().toString());
                });
        var otherSite=new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID());
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,'other-query-site','Other','ACTIVE',?,'AUTOMATIC',?,?,0)",
            otherSite.value(),account.id().value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        var other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),otherSite,null,null,INITIAL_TIME));
        for(UUID id:List.of(customer.id().value(),other.id().value(),UUID.randomUUID())) {
            mvc.perform(get("/api/v1/partner/customers/"+id).header("Authorization","Bearer "+read))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.title").value("Customer not available"));
        }
        for(UUID id:List.of(other.id().value(),UUID.randomUUID()))
            mvc.perform(post("/api/v1/partner/customers/"+id+"/anonymization").header("Authorization","Bearer "+manage)
                .header("Idempotency-Key","not-available-"+id).contentType("application/json").content("{\"confirmation\":\""+id+"\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_AVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='CUSTOMER_ANONYMIZED'",Integer.class)).isOne();
    }

    private String path(Customer value){return "/api/v1/partner/customers/"+value.id().value()+"/identities";}
    private String body(String subject){return "{\"mode\":\"SITE_TOKEN\",\"issuer_key\":\"site-provider\",\"subject\":\""+subject+"\"}";}
    private static Set<String> fieldNames(tools.jackson.databind.JsonNode node){return new LinkedHashSet<>(node.propertyNames());}
}
