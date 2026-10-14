package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.payments.application.service.SubscriptionCashFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.AdminApiClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
class SubscriptionPaymentAdminContextIntegrationTest extends SubscriptionCashFixture {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired ObjectMapper mapper;

    @Test void existingPaymentDetailAndListExposeOnlyImmutableOwnedInvoiceContext() throws Exception {
        bootstrap.initialize();var client=new AdminApiClient(context);client.login();
        var id=record("context-cash",PaymentStatus.SUCCEEDED,"context-fact").paymentId().value();
        client.getJson("/api/v1/admin/payments/"+id).andExpect(status().isOk())
            .andExpect(jsonPath("$.payment.subscription_id").value(subscription.id().toString()))
            .andExpect(jsonPath("$.payment.billing_record_id").value(bill.id().toString()))
            .andExpect(jsonPath("$.payment.subscription_billing_kind").value("PRORATION"))
            .andExpect(jsonPath("$.payment.subscription_period_start").value(INITIAL_TIME.toString()))
            .andExpect(jsonPath("$.payment.subscription_period_end").value(INITIAL_TIME.plusSeconds(86400).toString()))
            .andExpect(jsonPath("$.payment.refundable_amount_minor").value(700));
        String list=client.getJson("/api/v1/admin/payments?payment_id="+id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(list).path("items").get(0).path("subscription_id").asText()).isEqualTo(subscription.id().toString());
        assertThat(list).doesNotContain("invoice-1","provider_invoice_reference");
        var ordinary=save.save(Payment.dynamic(new PaymentId(UUID.randomUUID()),site,account.id(),"ordinary",Money.of(100,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME));
        var document=mapper.readTree(client.getJson("/api/v1/admin/payments/"+ordinary.id().value()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("payment");
        for(String field:List.of("subscription_id","billing_record_id","subscription_billing_kind","subscription_period_start","subscription_period_end"))assertThat(document.has(field)&&document.path(field).isNull()).as(field).isTrue();
    }

    @Test void actualSnakeCaseOpenApiDocumentsNullableUuidUtcAndClosedKind() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        var properties=mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("components").path("schemas").path("Projection").path("properties");
        for(String field:List.of("subscription_id","billing_record_id","subscription_billing_kind","subscription_period_start","subscription_period_end")) {
            var property=properties.path(field);assertThat(property.isMissingNode()).as(field).isFalse();
            assertThat(property.path("type").toString().contains("null")||property.path("nullable").asBoolean()).as(field+" nullable").isTrue();
            if(field.endsWith("_id"))assertThat(property.path("format").asText()).isEqualTo("uuid");
            if(field.contains("period"))assertThat(property.path("format").asText()).isEqualTo("date-time");
        }
        assertThat(properties.path("subscription_billing_kind").path("enum").toString()).isEqualTo("[\"INITIAL\",\"RENEWAL\",\"PRORATION\"]");
        assertThat(properties.has("provider_invoice_reference")).isFalse();
    }
}
