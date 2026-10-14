package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import org.junit.jupiter.api.Test;
class PartnerSubscriptionApiIntegrationTest extends SubscriptionApiFixture {
    @Test void verifiedSiteReadScopeAndExplicitCustomerAreRequired() throws Exception {
        var made=start();String credential=partner(PartnerScope.SUBSCRIPTIONS_READ);
        admin.mvc.perform(get("/api/v1/partner/subscriptions/"+made.subscriptionId()).header("Authorization","Bearer "+credential)).andExpect(status().isOk()).andExpect(jsonPath("$.site_id").value(site.value().toString()));
        admin.mvc.perform(post("/api/v1/partner/subscriptions/"+made.subscriptionId()+"/method-setups").header("Authorization","Bearer "+credential).header("Idempotency-Key","setup").contentType("application/json").content("{\"recurring_consent\":true}")).andExpect(status().isUnauthorized());
        String product=sub(made.subscriptionId()).productId().value().toString();
        admin.mvc.perform(get("/api/v1/partner/customers/"+customer.id().value()+"/products/"+product+"/access").header("Authorization","Bearer "+credential)).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        admin.mvc.perform(get("/api/v1/partner/subscriptions/"+made.subscriptionId()).header("Authorization","Bearer "+partner(PartnerScope.PAYMENTS_READ))).andExpect(status().isUnauthorized());
    }
}
