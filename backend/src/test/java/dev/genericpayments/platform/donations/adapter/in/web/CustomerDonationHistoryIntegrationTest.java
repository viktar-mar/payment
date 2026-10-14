package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort.Position;

class CustomerDonationHistoryIntegrationTest extends DonationQueryTestSupport {
    @Autowired CustomerPaymentHistoryCursorPort cursors;
    @Test void existingPaymentHistoryIncludesRealIdentifiedDonationAlongsideCatalogAndDynamic() throws Exception {
        var owner=customer();var token=sessions.issue(site,owner.id(),"https://shop.test").rawToken();
        var catalog=save.save(source(dev.genericpayments.platform.payments.domain.PaymentSource.CATALOG));
        var dynamic=save.save(payment());
        jdbc.update("update payments set customer_id=? where id in (?,?)",owner.id().value(),catalog.id().value(),dynamic.id().value());
        var donated=donation(owner,null);
        donation(customer(),null);donation(null,null);foreignDonation();
        var body=api.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/customer/payments?limit=100")
            .header("Origin","https://shop.test").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.next_cursor").isEmpty()).andReturn().getResponse().getContentAsString();
        List<Map<String,Object>> items=JsonPath.read(body,"$.items");
        assertThat(items).extracting(i->i.get("source")).containsExactlyInAnyOrder("CATALOG","DYNAMIC","DONATION");
        var row=items.stream().filter(i->i.get("payment_id").equals(donated.id().value().toString())).findFirst().orElseThrow();
        assertThat(row.get("catalog")).isNull();
        assertThat(row.keySet()).containsExactlyInAnyOrder("payment_id","source","amount_minor","currency","status","created_at","updated_at","catalog");
    }
    @Test void exactOwnerSafeHistoryUsesPaymentOrderAndImmutableCampaignWithBoundCursor() throws Exception {
        var owner=customer(); var token=sessions.issue(site,owner.id(),"https://shop.test").rawToken();
        var first=donation(owner,MESSAGE); var second=donation(owner,null);
        donation(null,MESSAGE); donation(customer(),MESSAGE);
        var ids=new ArrayList<>(List.of(first.id().value().toString(),second.id().value().toString())); ids.sort(Comparator.reverseOrder());
        jdbc.update("update donations set created_at=created_at-interval '1 day',updated_at=updated_at-interval '1 day' where id=?",second.donationId().value());
        jdbc.update("update donation_campaigns set display_name='Changed campaign' where id=?",campaign.id().value());
        String body=history(token,"?limit=1").andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].payment_id").value(ids.getFirst()))
            .andExpect(jsonPath("$.items[0].campaign_key").value("help-now")).andReturn().getResponse().getContentAsString();
        String cursor=JsonPath.read(body,"$.next_cursor");
        history(token,"?limit=1&cursor="+cursor).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].payment_id").value(ids.getLast()))
            .andExpect(jsonPath("$.next_cursor").isEmpty());
        var fields=((Map<?,?>)((List<?>)JsonPath.read(body,"$.items")).getFirst()).keySet().stream().map(Object::toString).toList();
        assertThat(fields).containsExactlyInAnyOrderElementsOf(Set.of("donation_id","payment_id","campaign_id","campaign_key","campaign_version","amount_minor","currency","status","message_state","created_at","updated_at"));
        assertThat(body).doesNotContain(MESSAGE,"provider","email","customer_id","<script>");
        history(token,"?limit=2&cursor="+cursor).andExpect(status().isBadRequest());
        history(token,"?limit=1&cursor="+cursor+"x").andExpect(status().isBadRequest());
        var foreign=sessions.issue(site,customer().id(),"https://shop.test").rawToken();
        history(foreign,"?limit=1&cursor="+cursor).andExpect(status().isBadRequest());
        String otherShape=cursors.encode(new Position(site,owner.id().value(),"customer-payments-v1",1,first.createdAt(),first.id()));
        history(token,"?limit=1&cursor="+otherShape).andExpect(status().isBadRequest());
        String otherSite=cursors.encode(new Position(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),owner.id().value(),"customer-donations-v1",1,first.createdAt(),first.id()));
        history(token,"?limit=1&cursor="+otherSite).andExpect(status().isBadRequest());
        history(token,"").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        history(token,"?limit=100").andExpect(status().isOk());
        history(token,"?customer_id="+owner.id().value()).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(ints={0,-1,101}) void invalidLimitsAreRejected(int limit) throws Exception {
        var owner=customer(); history(sessions.issue(site,owner.id(),"https://shop.test").rawToken(),"?limit="+limit).andExpect(status().isBadRequest());
    }
    @Test void defaultIsTwentyAndForeignAnonymousOtherCustomerAndNonDonationRowsStayExcluded() throws Exception {
        var owner=customer();var token=sessions.issue(site,owner.id(),"https://shop.test").rawToken();
        for(int i=0;i<21;i++)donation(owner,null);
        foreignDonation();donation(null,null);donation(customer(),null);
        var dynamic=save.save(payment());jdbc.update("update payments set customer_id=? where id=?",owner.id().value(),dynamic.id().value());
        String body=history(token,"").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20)).andReturn().getResponse().getContentAsString();
        String cursor=JsonPath.read(body,"$.next_cursor");
        history(token,"?cursor="+cursor).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.next_cursor").isEmpty());
        history(token,"?limit=100").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(21));
        String exhausted=cursors.encode(new Position(site,owner.id().value(),"customer-donations-v1",20,INITIAL_TIME.minusSeconds(1),dynamic.id()));
        history(token,"?cursor="+exhausted).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.next_cursor").isEmpty());
    }
}
