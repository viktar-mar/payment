package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.support.AdminApiClient;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CustomerAdminApiIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context;
    @Autowired CustomerStore customers;
    @Autowired CustomerIdentityStore identities;
    @Autowired EncryptedSecretPort encryption;
    @Autowired KeyedDigestPort digests;
    @Autowired dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap bootstrap;
    AdminApiClient api;
    Customer customer;
    @BeforeEach void fixture() throws Exception {
        bootstrap.initialize(); api=new AdminApiClient(context); api.login();
        var id=new CustomerId(UUID.randomUUID());
        var digest=digests.digest(KeyedDigestPort.Purpose.EMAIL_LOOKUP,"private@example.test");
        customer=customers.save(Customer.create(id,site,CustomerStore.emailReference(id),digest.keyVersion()+":"+digest.value(),INITIAL_TIME),encryption.encrypt("private@example.test"));
        identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,id,
            CustomerAuthenticationMode.SITE_TOKEN,"safe-issuer","v1:privateSubjectDigest",INITIAL_TIME),List.of("v1:privateSubjectDigest"));
    }
    String base(){return "/api/v1/admin/sites/"+site.value()+"/customers";}
    MockHttpServletRequestBuilder anonymize(String body,String... keys){
        var request=post(base()+"/"+customer.id().value()+"/anonymization").cookie(api.session,api.xsrf)
            .header("X-XSRF-TOKEN",api.xsrf.getValue()).contentType("application/json").content(body);
        if(keys.length>0)request.header("Idempotency-Key",(Object[])keys);
        return request;
    }
    String confirmation(){return "{\"confirmation\":\""+customer.id().value()+"\"}";}
    @Test void adminQueriesAreSiteBoundedFilteredAndExposeOnlySafeMetadata() throws Exception {
        var second=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var result=api.getJson(base()+"/"+customer.id().value()).andExpect(status().isOk())
            .andExpect(jsonPath("$.customer_id").value(customer.id().value().toString()))
            .andExpect(jsonPath("$.masked_email").value("***@***"))
            .andExpect(jsonPath("$.identities[0].issuer_key").value("safe-issuer")).andReturn();
        var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("customer_id","site_id","status","masked_email","created_at","updated_at","anonymized_at","identities");
        assertThat(json.get("identities").get(0).propertyNames()).containsExactlyInAnyOrder("mode","issuer_key","created_at","verified_at","last_used_at");
        assertThat(json.path("site_id").asText()).isEqualTo(site.value().toString());
        assertThat(json.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.path("anonymized_at").isNull()).isTrue();
        for(String field:List.of("created_at","updated_at"))
            assertThat(json.path(field).asText()).isEqualTo(INITIAL_TIME.toString());
        var identity=json.path("identities").get(0);
        assertThat(identity.path("mode").asText()).isEqualTo("SITE_TOKEN");
        for(String field:List.of("created_at","verified_at","last_used_at"))
            assertThat(identity.path(field).asText()).isEqualTo(INITIAL_TIME.toString());
        assertThat(json.toString()).doesNotContain("private@example.test","privateSubjectDigest","ciphertext","token","subject","digest");
        api.mvc.perform(get(base()).cookie(api.session).param("masked_email","***@***").param("status","ACTIVE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(listResult -> assertThat(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(listResult.getResponse().getContentAsString()).path("items").get(0)).isEqualTo(json));
        api.mvc.perform(get(base()).cookie(api.session).param("customer_id",second.id().value().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].customer_id").value(second.id().value().toString()));
        api.mvc.perform(get(base()).cookie(api.session).param("masked_email","private@example.test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.mvc.perform(get(base()).cookie(api.session).param("limit","51")).andExpect(status().isBadRequest());
        api.getJson(base().replace(site.value().toString(),UUID.randomUUID().toString())+"/"+customer.id().value())
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_AVAILABLE"));
    }
    @Test void adminAnonymizationReplaysAndRemainsVisibleWithSafeTimestamps() throws Exception {
        String first=api.mvc.perform(anonymize(confirmation(),"admin-erase"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.status").value("ANONYMIZED")).andReturn().getResponse().getContentAsString();
        var response=tools.jackson.databind.json.JsonMapper.builder().build().readTree(first);
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("customer_id","status");
        assertThat(response.path("customer_id").asText()).isEqualTo(customer.id().value().toString());
        api.mvc.perform(anonymize(confirmation(),"admin-erase")).andExpect(status().isOk()).andExpect(content().json(first));
        api.getJson(base()+"/"+customer.id().value()).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ANONYMIZED")).andExpect(jsonPath("$.masked_email").isEmpty())
            .andExpect(jsonPath("$.anonymized_at").isString()).andExpect(jsonPath("$.identities").isEmpty());
        api.mvc.perform(get(base()).cookie(api.session).param("status","ANONYMIZED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void exactConfirmationStrictBodyAndSingleBoundedKeyAreRequiredBeforeMutation() throws Exception {
        for(String body:List.of("{}","{\"confirmation\":\""+UUID.randomUUID()+"\"}",
                confirmation().replace("}",",\"extra\":1}"),"{\"confirmation\":7}","{",confirmation().replace("}",",\"confirmation\":\""+customer.id().value()+"\"}")))
            api.mvc.perform(anonymize(body,"strict-body")).andExpect(status().isBadRequest());
        for(String key:List.of(" ","k".repeat(256),"bad\tkey","bad\uD800key"))
            api.mvc.perform(anonymize(confirmation(),key)).andExpect(status().isBadRequest());
        api.mvc.perform(anonymize(confirmation())).andExpect(status().isBadRequest());
        api.mvc.perform(anonymize(confirmation(),"one","two")).andExpect(status().isBadRequest());
        assertThat(customers.findById(site,customer.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(count("customer_idempotency_records")).isZero();
    }
    @Test void canonicalBodyLimitAndEncodedAliasesFailBeforeMvc() throws Exception {
        api.mvc.perform(anonymize(" ".repeat(2048),"oversized")).andExpect(status().isPayloadTooLarge());
        for(String suffix:List.of("/anonymization/","/%61nonymization","/%2561nonymization","/anonymization;x=1")) {
            var uri=java.net.URI.create(base()+"/"+customer.id().value()+suffix);
            api.mvc.perform(post(uri).cookie(api.session,api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue())
                .header("Idempotency-Key","alias").contentType("application/json").content(confirmation()))
                .andExpect(status().isBadRequest());
        }
        assertThat(count("customer_idempotency_records")).isZero();
    }
    @Test void openApiHasCanonicalCustomerOperations() throws Exception {
        api.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/admin/sites/{siteId}/customers'].get.operationId").value("listAdminCustomers"))
            .andExpect(jsonPath("$.paths['/api/v1/admin/sites/{siteId}/customers/{customerId}'].get.operationId").value("getAdminCustomer"))
            .andExpect(jsonPath("$.paths['/api/v1/admin/sites/{siteId}/customers/{customerId}/anonymization'].post.operationId").value("anonymizeAdminCustomer"))
            .andExpect(jsonPath("$.paths['/api/v1/partner/customers/{customerId}'].get.operationId").value("getPartnerCustomer"))
            .andExpect(jsonPath("$.paths['/api/v1/partner/customers/{customerId}/anonymization'].post.operationId").value("anonymizePartnerCustomer"));
    }
}
