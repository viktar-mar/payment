package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DonationOpenApiIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    @Test void checkoutDescribesActualSnakeCaseCreationAndRequiredNestedContext() throws Exception {
        var doc=document();
        var operation=doc.path("paths").path("/api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}/checkouts").path("post");
        assertThat(operation.path("operationId").asText()).isEqualTo("createDonationCheckout");
        assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
        var request=resolve(doc,operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText());
        assertThat(request.path("properties").propertyNames()).containsExactlyInAnyOrder("amount_minor","message");
        assertThat(request.path("required")).extracting(JsonNode::asText).containsExactly("amount_minor");
        assertThat(operation.path("responses").has("200")).isFalse();
        var response=resolve(doc,operation.path("responses").path("201").path("content").path("*/*").path("schema").path("$ref").asText());
        String[] fields={"checkout_id","checkout_token","payment_id","amount_minor","currency","price","provider_type","confirmation_mode","provider_client_payload","status","expires_at","donation"};
        assertThat(response.path("properties").propertyNames()).containsExactlyInAnyOrder(fields);
        assertThat(response.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder(fields);
        assertThat(doc.path("openapi").asText()).startsWith("3.1.");
        assertThat(response.path("properties").path("price").path("type").asText()).as("price schema: %s",response.path("properties").path("price")).isEqualTo("null");
        var donation=resolve(doc,response.path("properties").path("donation").path("$ref").asText());
        assertThat(donation.path("properties").propertyNames()).containsExactlyInAnyOrder("donation_id","campaign_key","campaign_version");
        assertThat(donation.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder("donation_id","campaign_key","campaign_version");
    }

    @Test void redactionRequiresDocumentedBoundedIdempotencyHeader() throws Exception {
        var operation=document().path("paths").path("/api/v1/admin/donations/{donationId}/message-redactions").path("post");
        assertThat(operation.path("operationId").asText()).isEqualTo("redactDonationMessage");
        var headers=new java.util.ArrayList<JsonNode>();
        operation.path("parameters").forEach(p->{if(p.path("name").asText().equals("Idempotency-Key"))headers.add(p);});
        assertThat(headers).hasSize(1);
        assertThat(headers.getFirst().path("in").asText()).isEqualTo("header");
        assertThat(headers.getFirst().path("required").asBoolean()).isTrue();
        assertThat(headers.getFirst().path("schema").path("type").asText()).isEqualTo("string");
        assertThat(headers.getFirst().path("schema").path("maxLength").asInt()).isEqualTo(255);
    }

    @Test void donationAndPaymentAdminPagesHaveDistinctRefsAndExactOwnShapes() throws Exception {
        var doc = document();
        String donationRef = responseRef(doc, "/api/v1/admin/donations");
        String paymentRef = responseRef(doc, "/api/v1/admin/payments");
        assertThat(donationRef).isNotEqualTo(paymentRef);
        var donation = resolve(doc, donationRef).path("properties");
        var payment = resolve(doc, paymentRef).path("properties");
        assertThat(donation.propertyNames()).containsExactlyInAnyOrder("items", "total", "page", "size");
        assertThat(payment.propertyNames()).containsExactlyInAnyOrder("items", "total", "page", "size", "sort");
        assertThat(donation.path("items").path("items").path("$ref").asText())
            .isNotEqualTo(payment.path("items").path("items").path("$ref").asText());
        assertSummary(resolve(doc, donation.path("items").path("items").path("$ref").asText()));
    }

    @Test void customerDonationAndPaymentItemsCannotOverwriteEachOthersAllowlists() throws Exception {
        var doc = document();
        var donations = resolve(doc, responseRef(doc, "/api/v1/customer/donations")).path("properties");
        var payments = resolve(doc, responseRef(doc, "/api/v1/customer/payments")).path("properties");
        String donationItem = donations.path("items").path("items").path("$ref").asText();
        String paymentItem = payments.path("items").path("items").path("$ref").asText();
        assertThat(donationItem).isNotEqualTo(paymentItem);
        assertThat(donations.propertyNames()).containsExactlyInAnyOrder("items", "next_cursor");
        // Preserve the accepted legacy Payment documentation, including its existing camel-case schema.
        assertThat(payments.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
        assertThat(resolve(doc, donationItem).path("properties").propertyNames()).containsExactlyInAnyOrder(
            "donation_id", "payment_id", "campaign_id", "campaign_key", "campaign_version", "amount_minor", "currency",
            "status", "message_state", "created_at", "updated_at");
        assertThat(resolve(doc, paymentItem).path("properties").propertyNames()).containsExactlyInAnyOrder(
            "paymentId", "source", "amountMinor", "currency", "status", "createdAt", "updatedAt", "catalog");
    }

    @Test void redactionSchemaContainsOnlySafeResultFields() throws Exception {
        var doc = document();
        String ref = doc.path("paths").path("/api/v1/admin/donations/{donationId}/message-redactions")
            .path("post").path("responses").path("200").path("content").path("*/*").path("schema").path("$ref").asText();
        var schema = resolve(doc, ref);
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder(
            "donation_id", "message_state", "changed", "replayed");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
            .isSubsetOf("donation_id", "message_state", "changed", "replayed");
    }

    @Test void onlyDonationAdminDetailSchemaContainsMessageAndItsSummaryIsSafe() throws Exception {
        var doc = document();
        String detailRef = responseRef(doc, "/api/v1/admin/donations/{donationId}");
        assertThat(detailRef).isNotEqualTo(responseRef(doc, "/api/v1/admin/payments/{paymentId}"));
        var detail = resolve(doc, detailRef).path("properties");
        assertThat(detail.propertyNames()).containsExactlyInAnyOrder("donation", "message");
        assertThat(detail.path("message").path("type").asText()).isEqualTo("string");
        assertSummary(resolve(doc, detail.path("donation").path("$ref").asText()));
        var partner = resolve(doc, responseRef(doc, "/api/v1/partner/donations/{donationId}")).path("properties");
        assertThat(partner.propertyNames()).containsExactlyInAnyOrder("donation_id", "payment_id", "campaign_id", "campaign_key",
            "campaign_version", "customer_id", "amount_minor", "currency", "status", "created_at", "updated_at");
    }

    private static void assertSummary(JsonNode schema) {
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder("donation_id", "payment_id", "site_id",
            "campaign_id", "campaign_key", "campaign_version", "customer_id", "amount_minor", "currency", "status",
            "message_state", "has_message", "created_at", "updated_at");
    }
    private JsonNode document() throws Exception {
        return mapper.readTree(MockMvcBuilders.webAppContextSetup(context).build().perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private static String responseRef(JsonNode doc, String path) {
        var responses = doc.path("paths").path(path).path("get").path("responses");
        // New Donation/Customer routes document 200; legacy Payment controllers retain their existing response codes.
        String ref = responses.path("200").path("content").path("*/*").path("schema").path("$ref").asText();
        return ref.isEmpty() ? responses.path("401").path("content").path("*/*").path("schema").path("$ref").asText() : ref;
    }
    private static JsonNode resolve(JsonNode doc, String ref) {
        assertThat(ref).startsWith("#/components/schemas/");
        return doc.path("components").path("schemas").path(ref.substring("#/components/schemas/".length()));
    }
}
