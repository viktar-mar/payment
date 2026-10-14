package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.genericpayments.platform.partners.domain.PartnerScope;

class PartnerDonationApiIntegrationTest extends DonationQueryTestSupport {
    @Test void scopedSafeReadAndUnknownCrossSiteAreIndistinguishable() throws Exception {
        var p=donation(customer(),MESSAGE);
        // Use the persisted wire scope so this test is assertion RED before the enum extension exists.
        var issued=keys.create(site,Set.of(PartnerScope.PAYMENTS_READ),null);
        jdbc.update("update site_api_keys set scopes='[\"donations:read\"]'::jsonb where site_id=?",site.value());
        String path="/api/v1/partner/donations/"+p.donationId().value();
        String body=org.junit.jupiter.api.Assertions.assertDoesNotThrow(()->api.mvc.perform(get(path).header("Authorization","Bearer "+issued.apiKey())))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.payment_id").value(p.id().value().toString()))
            .andExpect(jsonPath("$.customer_id").value(p.customerId().toString())).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(MESSAGE,"message","provider","email","event");
        var denied=keys.create(site,Set.of(PartnerScope.PAYMENTS_READ),null).apiKey();
        api.mvc.perform(get(path).header("Authorization","Bearer "+denied)).andExpect(status().isForbidden());
        api.mvc.perform(get("/api/v1/partner/donations/"+UUID.randomUUID()).header("Authorization","Bearer "+issued.apiKey()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DONATION_NOT_FOUND"));
    }
    @Test void actualScopeCreationCrossSiteUniformityAnonymousAndExactFieldAllowlist() throws Exception {
        var credential=keys.create(site,Set.of(PartnerScope.DONATIONS_READ),null).apiKey();
        var foreign=foreignDonation();var own=donation(null,MESSAGE);
        String body=api.mvc.perform(get("/api/v1/partner/donations/"+own.donationId().value()).header("Authorization","Bearer "+credential))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customer_id").isEmpty()).andReturn().getResponse().getContentAsString();
        Map<String,Object> parsed=com.jayway.jsonpath.JsonPath.read(body,"$");
        assertThat(parsed.keySet()).containsExactlyInAnyOrder("donation_id","payment_id","campaign_id","campaign_key","campaign_version","customer_id","amount_minor","currency","status","created_at","updated_at");
        for(UUID id:List.of(foreign.donationId().value(),UUID.randomUUID()))api.mvc.perform(get("/api/v1/partner/donations/"+id)
            .header("Authorization","Bearer "+credential).header("X-Request-ID","same-request"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DONATION_NOT_FOUND")).andExpect(jsonPath("$.title").value("Donation not found"));
        api.mvc.perform(get("/api/v1/partner/donations/"+own.donationId().value())).andExpect(status().isUnauthorized());
    }
}
