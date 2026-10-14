package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.donations.application.service.DonationFinancialTestFixture;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.partners.application.service.SiteApiKeyService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
abstract class DonationQueryTestSupport extends DonationFinancialTestFixture {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired SiteApiKeyService keys;
    AdminApiClient api;
    @BeforeEach void login() throws Exception { bootstrap.initialize(); api=new AdminApiClient(context); api.login(); }
    dev.genericpayments.platform.payments.domain.Payment foreignDonation() throws Exception {
        var foreign=new dev.genericpayments.platform.sites.domain.SiteId(UUID.fromString(api.site(account.id().value().toString(),"foreign-donations")));
        var c=campaigns.insert(dev.genericpayments.platform.donations.domain.DonationCampaign.draft(
            new dev.genericpayments.platform.donations.domain.DonationCampaignId(UUID.randomUUID()),foreign,
            new dev.genericpayments.platform.donations.domain.DonationCampaignKey("foreign-help"),"Foreign",null,"EUR",List.of(2000L),100,10000,true,true,INITIAL_TIME));
        var id=new dev.genericpayments.platform.donations.domain.DonationId(UUID.randomUUID());
        donations.save(dev.genericpayments.platform.donations.domain.Donation.create(id,foreign,c.id(),null,INITIAL_TIME));
        return save.save(dev.genericpayments.platform.payments.domain.Payment.donation(new dev.genericpayments.platform.payments.domain.PaymentId(UUID.randomUUID()),
            foreign,null,account.id(),id,new dev.genericpayments.platform.payments.domain.DonationSnapshot(c.id(),c.campaignKey(),0,"EUR"),
            dev.genericpayments.platform.catalog.domain.Money.of(2000,"EUR"),dev.genericpayments.platform.catalog.domain.TaxMode.NONE,Map.of(),INITIAL_TIME));
    }
    ResultActions history(String token,String query) throws Exception {
        return api.mvc.perform(get("/api/v1/customer/donations"+query).header("Origin","https://shop.test").header("Authorization","Bearer "+token));
    }
    ResultActions redact(UUID id,String confirmation,String key) throws Exception {
        var request=post("/api/v1/admin/donations/"+id+"/message-redactions").cookie(api.session,api.xsrf)
            .header("X-XSRF-TOKEN",api.xsrf.getValue()).contentType("application/json").content("{\"confirmation\":\""+confirmation+"\"}");
        if(key!=null)request.header("Idempotency-Key",key);
        return api.mvc.perform(request);
    }
}
