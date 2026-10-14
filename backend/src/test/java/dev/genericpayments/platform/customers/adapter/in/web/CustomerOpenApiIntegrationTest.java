package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CustomerOpenApiIntegrationTest extends PostgresIntegrationTest {
    private static final String CUSTOMERS="/api/v1/admin/sites/{siteId}/customers";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    @Test void subscriptionAuthenticationSiteBindingIsOptionalSafeUuidAndNotALinkProofField() throws Exception {
        var schemas=document().path("components").path("schemas");
        var session=schemas.path("CustomerAuthenticationSessionResponse");
        assertThat(session.path("properties").propertyNames()).containsExactlyInAnyOrder("session_token","expires_at","scopes","customer","site_id");
        assertThat(session.at("/properties/site_id/type").asText()).isEqualTo("string");
        assertThat(session.at("/properties/site_id/format").asText()).isEqualTo("uuid");
        assertThat(session.path("required")).extracting(JsonNode::asText).doesNotContain("site_id");
        assertThat(schemas.path("CustomerAuthenticationLinkProofResponse").path("properties").propertyNames())
            .containsExactlyInAnyOrder("proof_token","expires_at");
    }

    @Test void customerPageHasItsOwnSchemaAndCannotOverwritePaymentPage() throws Exception {
        var doc=document();
        String customerRef=responseRef(doc,CUSTOMERS,"get","200");
        assertThat(customerRef).isEqualTo("#/components/schemas/CustomerAdminPage");
        for(String code:List.of("401","403","404","409","503"))
            assertThat(responseRef(doc,"/api/v1/admin/payments","get",code))
                .isEqualTo("#/components/schemas/Page").isNotEqualTo(customerRef);
        assertThat(resolve(doc,customerRef).path("properties").propertyNames()).containsExactly("items");
    }

    @Test void paymentPageRetainsProjectionPaginationAndSortWithoutResponseStatusChanges() throws Exception {
        var doc=document();
        var responses=doc.path("paths").path("/api/v1/admin/payments").path("get").path("responses");
        assertThat(responses.propertyNames()).containsExactlyInAnyOrder("400","401","403","404","409","503");
        var page=resolve(doc,responseRef(doc,"/api/v1/admin/payments","get","401")).path("properties");
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items","total","page","size","sort");
        assertThat(page.path("items").path("items").path("$ref").asText()).isEqualTo("#/components/schemas/Projection");
        assertThat(page.path("total").path("type").asText()).isEqualTo("integer");
        assertThat(page.path("total").path("format").asText()).isEqualTo("int64");
        for(String field:List.of("page","size")) {
            assertThat(page.path(field).path("type").asText()).isEqualTo("integer");
            assertThat(page.path(field).path("format").asText()).isEqualTo("int32");
        }
        assertThat(page.path("sort").path("enum")).extracting(JsonNode::asText)
            .containsExactly("CREATED_DESC","CREATED_ASC");
        var projection=doc.path("components").path("schemas").path("Projection").path("properties");
        assertThat(projection.propertyNames()).containsExactlyInAnyOrder("payment_id","site_id","payment_account_id",
            "source","price_id","price_key","price_version","external_order_id","amount_minor","currency",
            "tax_mode","tax_amount_minor","status","provider","refundable_amount_minor","refunded_amount_minor","created_at","updated_at",
            "customer_id","donation_id","campaign_id","campaign_key","campaign_version",
            "subscription_id","billing_record_id","subscription_billing_kind","subscription_period_start","subscription_period_end");
    }

    @Test void customerAndIdentitySchemasMatchExactSafeWireFieldsAndActualNullability() throws Exception {
        var doc=document();
        String detailRef=responseRef(doc,CUSTOMERS+"/{customerId}","get","200");
        assertThat(detailRef).isEqualTo("#/components/schemas/CustomerResponse");
        assertThat(responseRef(doc,"/api/v1/partner/customers/{customerId}","get","200")).isEqualTo(detailRef);
        String pageRef=responseRef(doc,CUSTOMERS,"get","200");
        assertThat(resolve(doc,pageRef).path("properties").path("items").path("items").path("$ref").asText()).isEqualTo(detailRef);
        var customer=resolve(doc,detailRef).path("properties");
        assertThat(customer.propertyNames()).containsExactlyInAnyOrder("customer_id","site_id","status","masked_email",
            "created_at","updated_at","anonymized_at","identities");
        for(String field:List.of("masked_email","anonymized_at")) assertNullable(customer.path(field));
        String identityRef=customer.path("identities").path("items").path("$ref").asText();
        assertThat(identityRef).isEqualTo("#/components/schemas/CustomerIdentityResponse");
        var identity=resolve(doc,identityRef).path("properties");
        assertThat(identity.propertyNames()).containsExactlyInAnyOrder("mode","issuer_key","created_at","verified_at","last_used_at");
        for(String field:List.of("issuer_key","created_at","verified_at","last_used_at"))
            assertThat(identity.path(field).toString()).doesNotContain("\"nullable\":true","\"null\"");
    }

    @Test void sharedAnonymizationResponseHasUniqueSchemaAndExactSnakeCasePayload() throws Exception {
        var doc=document();
        String ref=responseRef(doc,CUSTOMERS+"/{customerId}/anonymization","post","200");
        assertThat(ref).isEqualTo("#/components/schemas/CustomerAnonymizationResponse");
        assertThat(responseRef(doc,"/api/v1/partner/customers/{customerId}/anonymization","post","200")).isEqualTo(ref);
        var properties=resolve(doc,ref).path("properties");
        assertThat(properties.propertyNames()).containsExactlyInAnyOrder("customer_id","status");
        assertThat(properties.path("customer_id").path("format").asText()).isEqualTo("uuid");
    }

    @Test void actualCustomerSchemaUsesSnakeCaseWithoutDependingOnItsComponentName() throws Exception {
        var doc=document();
        var customer=resolve(doc,responseRef(doc,CUSTOMERS+"/{customerId}","get","200")).path("properties");
        assertThat(customer.propertyNames()).containsExactlyInAnyOrder("customer_id","site_id","status","masked_email",
            "created_at","updated_at","anonymized_at","identities");
    }

    @Test void actualIdentitySchemaUsesSnakeCaseWithoutDependingOnItsComponentName() throws Exception {
        var doc=document();
        var customer=resolve(doc,responseRef(doc,CUSTOMERS+"/{customerId}","get","200")).path("properties");
        var identity=resolve(doc,customer.path("identities").path("items").path("$ref").asText()).path("properties");
        assertThat(identity.propertyNames()).containsExactlyInAnyOrder("mode","issuer_key","created_at","verified_at","last_used_at");
    }

    @Test void actualNullableCustomerFieldsAreExplicitlyNullable() throws Exception {
        var doc=document();
        var customer=resolve(doc,responseRef(doc,CUSTOMERS+"/{customerId}","get","200")).path("properties");
        for(String field:List.of("masked_email","anonymized_at")) assertNullable(customer.path(field));
    }

    @Test void actualAnonymizationSchemaUsesSnakeCaseWithoutDependingOnItsComponentName() throws Exception {
        var doc=document();
        var response=resolve(doc,responseRef(doc,CUSTOMERS+"/{customerId}/anonymization","post","200"));
        assertThat(response.path("properties").propertyNames()).containsExactlyInAnyOrder("customer_id","status");
    }

    @Test void publicJwkSetIsAnOpenJsonObjectNotJacksonIntrospection() throws Exception {
        var doc=document();
        var jwks=doc.path("components").path("schemas").path("ProviderRequest").path("properties").path("public_jwk_set");
        assertThat(jwks.path("type").asText()).isEqualTo("object");
        assertThat(jwks.path("additionalProperties").asBoolean()).isTrue();
        assertThat(jwks.has("$ref")).isFalse();
        assertThat(jwks.has("properties")).isFalse();
        assertThat(jwks.toString()).doesNotContain("JsonNode","nodeType","valueNode","containerNode");
    }

    private JsonNode document() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        return mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }
    private static String responseRef(JsonNode doc,String path,String method,String code) {
        return doc.path("paths").path(path).path(method).path("responses").path(code)
            .path("content").path("*/*").path("schema").path("$ref").asText();
    }
    private static JsonNode resolve(JsonNode doc,String ref) {
        assertThat(ref).startsWith("#/components/schemas/");
        return doc.path("components").path("schemas").path(ref.substring("#/components/schemas/".length()));
    }
    private static void assertNullable(JsonNode property) {
        assertThat(property.path("nullable").asBoolean() || property.path("type").toString().contains("\"null\""))
            .as("nullable property: %s",property).isTrue();
    }
}
