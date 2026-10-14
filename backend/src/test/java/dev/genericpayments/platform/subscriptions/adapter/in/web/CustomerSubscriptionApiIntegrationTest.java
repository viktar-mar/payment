package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.customers.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.jayway.jsonpath.JsonPath;

class CustomerSubscriptionApiIntegrationTest extends SubscriptionApiFixture {
    @Test void readOnlyAuthorityCannotFetchActionAndDisabledAccountHasNoPermittedActions() throws Exception {
        var made=start();apply(made.subscriptionId(),retrieve(made),"actions-state");
        jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",account.id().value());
        read(BASE+"/"+made.subscriptionId()).andExpect(status().isOk()).andExpect(jsonPath("$.permitted_actions.length()").value(0));
        jdbc.update("update payment_accounts set account_status='ACTIVE' where id=?",account.id().value());
        jdbc.update("update customer_sessions set scopes='[\"SUBSCRIPTION_READ\"]' where customer_id=?",customer.id().value());
        read(BASE+"/"+made.subscriptionId()).andExpect(status().isOk());
        read(BASE+"/"+made.subscriptionId()+"/operations/"+made.operationId()+"/action").andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void createReplayReadAndPollingUseSafeSeparateShapes() throws Exception {
        String response=write(BASE,"create",createBody()).andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(response,"$.subscription_id"),operation=JsonPath.read(response,"$.operation_id");
        write(BASE,"create",createBody()).andExpect(status().isAccepted()).andExpect(jsonPath("$.operation_id").value(operation));
        read(BASE+"/"+id).andExpect(status().isOk()).andExpect(jsonPath("$.customer_id").value(customer.id().value().toString())).andExpect(jsonPath("$.billing_price.amount_minor").value(2000)).andExpect(jsonPath("$.access.allowed").value(false));
        String poll=read(BASE+"/"+id+"/operations/"+operation).andExpect(status().isOk()).andExpect(jsonPath("$.operation_id").value(operation)).andReturn().getResponse().getContentAsString();
        assertThat(poll).doesNotContain("next_action","secret","provider","fingerprint");
        read(BASE).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(count("subscription_operations")).isEqualTo(1);
    }
    @Test void foreignAndUnknownReadsAndMutationsAreIdenticalSafe404WithoutWrites() throws Exception {
        var made=start();int commands=count("fake_provider_operations"),audit=count("audit_log");
        var other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        token=sessions.issue(site,other.id(),"https://facts.test").rawToken();
        for(UUID id:List.of(made.subscriptionId(),UUID.randomUUID())) {
            read(BASE+"/"+id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
            for(String suffix:List.of("changes","method-setups","cancellation")) {
                String body=suffix.equals("changes")?"{\"quote_id\":\""+UUID.randomUUID()+"\"}":suffix.equals("method-setups")?"{\"recurring_consent\":true}":"{\"mode\":\"PERIOD_END\"}";
                write(BASE+"/"+id+"/"+suffix,UUID.randomUUID().toString(),body).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
            }
        }
        assertThat(count("fake_provider_operations")).isEqualTo(commands);assertThat(count("audit_log")).isEqualTo(audit);
    }
    @Test void cursorCannotChangeOwnerOrFiltersAndLifecycleIsSeparateFromCash() throws Exception {
        var first=start();apply(first.subscriptionId(),retrieve(first),"http-history-first");price=price(0);var second=start();apply(second.subscriptionId(),retrieve(second),"http-history-second");
        String page=read(BASE+"?limit=1").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cursor=JsonPath.read(page,"$.next_cursor");assertThat(cursor).isNotBlank();
        read(BASE+"?limit=1&cursor="+cursor).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.next_cursor").isEmpty());
        read(BASE+"?limit=2&cursor="+cursor).andExpect(status().isBadRequest());
        read(BASE+"?limit=1&cursor="+cursor+"x").andExpect(status().isBadRequest());
        String id=JsonPath.read(page,"$.items[0].subscription_id");
        read(BASE+"/"+id+"/events").andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sequence").value(1));
        String bills=read(BASE+"/"+id+"/billing-records").andExpect(status().isOk()).andExpect(jsonPath("$.items[0].payment_ids.length()").value(1)).andReturn().getResponse().getContentAsString();
        String payment=JsonPath.read(bills,"$.items[0].payment_ids[0]");
        assertThat(jdbc.queryForObject("select subscription_id from payments where id=?",UUID.class,UUID.fromString(payment))).isEqualTo(UUID.fromString(id));
        assertThat(bills).doesNotContain("provider_invoice","provider_reference","fingerprint","secret");
    }
}
