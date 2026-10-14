package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
class SubscriptionRequestPolicyIntegrationTest extends SubscriptionApiFixture {
    @Test void displayedStateIsBoundedAndScheduledCancellationIsAccepted() throws Exception {
        read(BASE+"?state=CANCEL_AT_PERIOD_END").andExpect(status().isOk());
        for(String state:java.util.List.of("EXPIRED","active","ACTIVE,CANCEL_AT_PERIOD_END","x".repeat(256)))
            read(BASE+"?state="+state).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void rejectsCoercionTrailingJsonAndNonCanonicalPathIds() throws Exception {
        for(String body:java.util.List.of(createBody().replace("true","\"true\""),createBody()+" {}"))
            write(BASE,"strict-type",body).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        read(BASE+"/1-1-1-1-1").andExpect(status().isBadRequest());
        assertThat(count("subscription_operations")).isZero();
    }
    @Test void strictBoundsAndAmbiguityRejectBeforeProviderMutation() throws Exception {
        write(BASE,null,createBody()).andExpect(status().isBadRequest());
        write(BASE,"large"," ".repeat(8193)).andExpect(status().isPayloadTooLarge());
        write(BASE,"unknown",createBody().replace("}",",\"customer_id\":\""+customer.id().value()+"\"}")).andExpect(status().isBadRequest());
        write(BASE,"duplicate",createBody().replace("}",",\"recurring_consent\":false}")).andExpect(status().isBadRequest());
        read(BASE+"?limit=1&limit=2").andExpect(status().isBadRequest());
        read(BASE+"?unknown=true").andExpect(status().isBadRequest());
        read(BASE+"?cursor="+"x".repeat(4097)).andExpect(status().isBadRequest());
        admin.mvc.perform(post(BASE).header("Authorization","Bearer "+token).header("Origin","https://foreign.test").header("Idempotency-Key","wrong").contentType("application/json").content(createBody())).andExpect(status().isUnauthorized());
        admin.mvc.perform(post(BASE).header("Authorization","Bearer "+token).header("Origin","https://facts.test").header("Idempotency-Key","one","two").contentType("application/json").content(createBody())).andExpect(status().isBadRequest());
        admin.mvc.perform(post(BASE).cookie(admin.session).header("Authorization","Bearer "+token).header("Origin","https://facts.test").header("Idempotency-Key","mixed").contentType("application/json").content(createBody())).andExpect(status().isBadRequest());
        assertThat(count("subscription_operations")).isZero();assertThat(count("fake_provider_operations")).isZero();
    }
    @Test void subscriptionPreflightAdmitsIdempotencyHeaderWithoutCredentials() throws Exception {
        admin.mvc.perform(options(BASE).header("Origin","https://facts.test").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type,idempotency-key"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.containsString("Idempotency-Key"))).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
