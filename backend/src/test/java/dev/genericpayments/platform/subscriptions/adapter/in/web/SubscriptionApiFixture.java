package dev.genericpayments.platform.subscriptions.adapter.in.web;

import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.partners.application.service.SiteApiKeyService;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@org.springframework.test.context.TestPropertySource(properties={"payment.admin.bootstrap.enabled=true","payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
abstract class SubscriptionApiFixture extends SubscriptionFactFixture {
    @Autowired WebApplicationContext web;
    @Autowired AdminBootstrap bootstrap;
    @Autowired SiteApiKeyService keys;
    protected AdminApiClient admin;
    protected String token;
    protected static final String BASE="/api/v1/customer/subscriptions";
    @BeforeEach void httpFixture() throws Exception {
        admin=new AdminApiClient(web); bootstrap.initialize(); admin.login();
        token=sessions.issue(site,customer.id(),"https://facts.test").rawToken();
    }
    protected ResultActions read(String path) throws Exception {
        return admin.mvc.perform(get(path).header("Origin","https://facts.test").header("Authorization","Bearer "+token));
    }
    protected ResultActions write(String path,String key,String body) throws Exception {
        var request=post(path).header("Origin","https://facts.test").header("Authorization","Bearer "+token).contentType("application/json").content(body);
        if(key!=null)request.header("Idempotency-Key",key);
        return admin.mvc.perform(request);
    }
    protected ResultActions adminWrite(String path,String key,String body) throws Exception {
        return admin.mvc.perform(post(path).cookie(admin.session,admin.xsrf).header("X-XSRF-TOKEN",admin.xsrf.getValue()).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    protected String createBody(){return "{\"price_id\":\""+price.value()+"\",\"recurring_consent\":true}";}
    protected String partner(PartnerScope... scopes){return keys.create(site,Set.of(scopes),null).apiKey();}
}
