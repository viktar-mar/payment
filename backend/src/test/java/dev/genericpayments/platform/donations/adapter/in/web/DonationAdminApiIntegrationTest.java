package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DonationAdminApiIntegrationTest extends DonationQueryTestSupport {
    @org.springframework.beans.factory.annotation.Autowired dev.genericpayments.platform.donations.application.port.in.QueryAdminDonationsUseCase queries;
    @Test void filteredListAndPaymentContextAreSafeAndOnlyDetailRevealsFramedMessage() throws Exception {
        var owner=customer(); var p=donation(owner,"<script>café</script>"); donation(null,null);
        String query="?site_id="+site.value()+"&campaign_id="+campaign.id().value()+"&customer_id="+owner.id().value()+"&donation_id="+p.donationId().value()+"&payment_id="+p.id().value()+"&status=CREATED&created_from=2026-09-13T00:00:00Z&created_to=2026-09-14T00:00:00Z";
        String list=api.getJson("/api/v1/admin/donations"+query).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].has_message").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("<script>","café","ciphertext","nonce");
        api.getJson("/api/v1/admin/donations/"+p.donationId().value()).andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("<script>café</script>"));
        String payment=api.getJson("/api/v1/admin/payments"+query.substring(0,query.indexOf("&status"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].donation_id").value(p.donationId().value().toString()))
            .andReturn().getResponse().getContentAsString();
        assertThat(payment).doesNotContain("<script>","café");
        api.getJson("/api/v1/admin/donations?size=51").andExpect(status().isBadRequest());
        api.getJson("/api/v1/admin/donations?status=SUCCEEDED").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={""," ","café"}) void detailPreservesPresentEmptyWhitespaceAndNfc(String message) throws Exception {
        var p=donation(null,message); api.getJson("/api/v1/admin/donations/"+p.donationId().value()).andExpect(status().isOk()).andExpect(jsonPath("$.message").value(message));
    }
    @Test void redactionRequiresConfirmationAndKeyErasesTupleAndAuditsOnlyOnce() throws Exception {
        var p=donation(null,MESSAGE); var id=p.donationId().value();
        redact(id,UUID.randomUUID().toString(),"intent").andExpect(status().isBadRequest());
        redact(id,id.toString(),null).andExpect(status().isBadRequest());
        redact(id,id.toString(),"intent").andExpect(status().isOk()).andExpect(jsonPath("$.message_state").value("REDACTED"));
        redact(id,id.toString(),"intent").andExpect(status().isOk());
        redact(id,id.toString(),"another-intent").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from donations where id=? and message_state='REDACTED' and message_ciphertext is null and message_nonce is null and message_key_version is null",Integer.class,id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_type='DONATION'",Integer.class)).isOne();
        assertThat(jdbc.queryForList("select details::text from audit_log where resource_type='DONATION'",String.class).toString()).doesNotContain(MESSAGE);
        api.getJson("/api/v1/admin/donations/"+id).andExpect(status().isOk()).andExpect(jsonPath("$.message").isEmpty());
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(p);
    }
    @ParameterizedTest @ValueSource(strings={"no-frame","\u0001\u0001double-frame","\u0001e\u0301","\u0001\n"})
    void malformedStoredPlaintextFailsSafelyAndNeverDecryptsInSafeProjections(String malformed) throws Exception {
        var p=donation(null,MESSAGE);var encrypted=encryption.encrypt(malformed);
        jdbc.update("update donations set message_ciphertext=?,message_nonce=?,message_key_version=? where id=?",encrypted.ciphertext(),encrypted.nonce(),encrypted.keyVersion(),p.donationId().value());
        api.getJson("/api/v1/admin/donations/"+p.donationId().value()).andExpect(status().is(503)).andExpect(jsonPath("$.code").value("DONATION_MESSAGE_UNAVAILABLE"));
        String list=api.getJson("/api/v1/admin/donations").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(MESSAGE,malformed);
        var credential=keys.create(site,Set.of(dev.genericpayments.platform.partners.domain.PartnerScope.DONATIONS_READ),null).apiKey();
        api.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/partner/donations/"+p.donationId().value()).header("Authorization","Bearer "+credential)).andExpect(status().isOk());
    }
    @Test void ciphertextFailureIsSafeDtoDiagnosticsAreRedactedAndAnonymousDetailRequiresAdmin() throws Exception {
        var p=donation(null,MESSAGE);
        assertThat(queries.detail(p.donationId().value()).toString()).contains("REDACTED").doesNotContain(MESSAGE);
        api.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/donations/"+p.donationId().value())).andExpect(status().isUnauthorized());
        jdbc.update("update donations set message_ciphertext=? where id=?",new byte[32],p.donationId().value());
        String body=api.getJson("/api/v1/admin/donations/"+p.donationId().value()).andExpect(status().is(503)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(MESSAGE,"ciphertext","decrypt","GCM");
    }
    @Test void sameSiteDifferentIntentConflictsAbsentRemainsAbsentAndReceiptUsesOriginalPayment() throws Exception {
        var first=donation(null,MESSAGE);var second=donation(null,MESSAGE);var absent=donation(null,null);
        redact(first.donationId().value(),first.donationId().value().toString(),"shared-intent").andExpect(status().isOk());
        redact(second.donationId().value(),second.donationId().value().toString(),"shared-intent").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        redact(absent.donationId().value(),absent.donationId().value().toString(),"absent").andExpect(status().isOk()).andExpect(jsonPath("$.message_state").value("ABSENT"));
        assertThat(jdbc.queryForObject("select resource_id from idempotency_records where idempotency_key='shared-intent'",UUID.class)).isEqualTo(first.id().value());
        assertThat(donations.findEncryptedMessage(site,second.donationId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_type='DONATION'",Integer.class)).isOne();
    }
    @Test void everyAdminFilterActuallyNarrowsAndLegacyPaymentDefaultsRemain() throws Exception {
        var owner=customer();var own=donation(owner,MESSAGE);var foreign=foreignDonation();
        for(String filter:List.of("site_id="+site.value(),"campaign_id="+campaign.id().value(),"customer_id="+owner.id().value(),"donation_id="+own.donationId().value(),"payment_id="+own.id().value())){
            api.getJson("/api/v1/admin/donations?"+filter).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].payment_id").value(own.id().value().toString()));
            api.getJson("/api/v1/admin/payments?"+filter).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        }
        api.getJson("/api/v1/admin/donations?created_from=2026-09-14T00:00:00Z").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.getJson("/api/v1/admin/donations?created_to=2026-09-13T10:15:30Z").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.getJson("/api/v1/admin/donations?site_id="+site.value()+"&donation_id="+foreign.donationId().value()).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.getJson("/api/v1/admin/payments").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.sort").value("CREATED_DESC"));
        String detail=api.getJson("/api/v1/admin/payments/"+own.id().value()).andExpect(status().isOk()).andExpect(jsonPath("$.payment.customer_id").value(owner.id().value().toString())).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(MESSAGE,"message_ciphertext");
        for(String query:List.of("page=-1","page=100001","size=0","size=51","status=INVALID","created_from=2026-09-14T00:00:00Z&created_to=2026-09-13T00:00:00Z","created_from=2026-09-13T00:00:00+00:00"))api.getJson("/api/v1/admin/donations?"+query).andExpect(status().isBadRequest());
    }
    @Test void adminPaymentTimestampTiesAreStableAndPaginationHappensAfterFiltering() throws Exception {
        var first=donation(null,MESSAGE);var second=donation(null,MESSAGE);var third=donation(null,null);
        foreignDonation();
        var ids=new ArrayList<>(List.of(first.id().value().toString(),second.id().value().toString(),third.id().value().toString()));
        ids.sort(Comparator.reverseOrder());
        jdbc.update("update donations set created_at=created_at-interval '1 day',updated_at=updated_at-interval '1 day' where id=?",first.donationId().value());
        for(int page=0;page<3;page++)api.getJson("/api/v1/admin/donations?site_id="+site.value()+"&size=1&page="+page)
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].payment_id").value(ids.get(page)));
        api.getJson("/api/v1/admin/donations?site_id="+site.value()+"&size=1&page=3").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.getJson("/api/v1/admin/donations?size=50").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
    }
}
