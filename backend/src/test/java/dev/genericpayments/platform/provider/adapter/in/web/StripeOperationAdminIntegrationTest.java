package dev.genericpayments.platform.provider.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;

class StripeOperationAdminIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    AdminApiClient client;
    final String path="/api/v1/admin/provider-operations";
    @Test void generatedContractMatchesSafeSnakeCaseHttpFields()throws Exception {
        client.getJson("/v3/api-docs").andExpect(status().isOk())
            .andExpect(jsonPath("$.components.schemas.ProviderOperationView.properties.operation_id").exists())
            .andExpect(jsonPath("$.components.schemas.ProviderOperationView.properties.account_mode").exists())
            .andExpect(jsonPath("$.components.schemas.DeadProviderReferenceView.properties.account_mode").exists())
            .andExpect(jsonPath("$.components.schemas.ProviderOperationView.properties.credentials_ref").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.DeadProviderReferenceView.properties.receipt_id").exists())
            .andExpect(jsonPath("$.components.schemas.UpdateReturnUrl.properties.expected_version").exists())
            .andExpect(jsonPath("$.components.schemas.CheckoutReturnUrlPolicy.properties.return_url").exists())
            .andExpect(jsonPath("$.components.schemas.CheckoutReturnUrlPolicy.properties.origin").exists())
            .andExpect(jsonPath("$.components.schemas.CheckoutReturnUrlPolicy.properties.version").exists())
            .andExpect(jsonPath("$.components.schemas.Policy.properties.mode").exists())
            .andExpect(jsonPath("$.paths['/api/v1/admin/sites/{siteId}/checkout-return-urls'].get.responses['200'].content['*/*'].schema.items['$ref']").value("#/components/schemas/CheckoutReturnUrlPolicy"))
            .andExpect(jsonPath("$.paths['/api/v1/admin/provider-operations/{operationId}/recover'].post.responses['202']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/admin/provider-operations/{operationId}/recover'].post.parameters[?(@.name == 'Idempotency-Key')].required").value(org.hamcrest.Matchers.contains(true)))
            .andExpect(jsonPath("$.paths['/api/v1/admin/sites/{siteId}/checkout-return-urls'].put.responses['204']").exists());
    }
    @BeforeEach void login() throws Exception {
        bootstrap.initialize(); client=new AdminApiClient(context); client.login();
    }
    @Test void paymentInspectionUsesOriginalAccountAndProjectsNoInternalFacts() throws Exception {
        var payment=save.save(payment());
        jdbc.update("update payment_accounts set provider='STRIPE' where id=?",account.id().value());
        UUID operation=UUID.randomUUID();
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint)
            values (?,?,'acct_fixture','credential-sentinel','PAYMENT_PREPARE',?,'operation-sentinel','fingerprint-sentinel')
            """,operation,account.id().value(),payment.id().value());
        String body=client.getJson(path+"?payment_id="+payment.id().value()).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$[0].operation_id").value(operation.toString()))
            .andExpect(jsonPath("$[0].account_id").value(account.id().value().toString()))
            .andExpect(jsonPath("$[0].account_mode").value("SANDBOX"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("credential-sentinel","operation-sentinel","fingerprint-sentinel","credentials_ref","client_secret");
        client.getJson(path+"?payment_id="+UUID.randomUUID()).andExpect(status().isNotFound());
        UUID routed=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?, 'rerouted', 'STRIPE', 'LIVE', 'ACTIVE', 'acct_routed', 'route-credential', now(), 0)",routed);
        jdbc.update("update sites set payment_account_id=? where id=?",routed,site.value());
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint)
            values (?,?,'acct_routed','other-credential','PAYMENT_PREPARE',?,'other-operation','other-fingerprint')
            """,UUID.randomUUID(),routed,payment.id().value());
        client.getJson(path+"?payment_id="+payment.id().value()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].account_mode").value("SANDBOX"))
            .andExpect(jsonPath("$[0].account_id").value(account.id().value().toString()));
    }
    @Test void globalQueuesAreBoundedAndRequireAdmin() throws Exception {
        for(String suffix:java.util.List.of("/unresolved","/dead-references")) {
            client.getJson(path+suffix).andExpect(status().isOk()).andExpect(content().json("[]"));
            client.getJson(path+suffix+"?limit=101").andExpect(status().isBadRequest());
            client.mvc.perform(get(path+suffix)).andExpect(status().isUnauthorized());
            client.mvc.perform(get(path+suffix).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        }
    }
    @Test void catalogFollowUpAndForeignDeadReferenceRemainSeparateAndKeysetBounded()throws Exception {
        jdbc.update("update payment_accounts set provider='STRIPE' where id=?",account.id().value());
        for(int n=1;n<=2;n++) jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,
                status,provider_reference,first_dispatched_at,retry_before,manual_review_required,safe_error_code)
            values (?,?,'acct_fixture','credential-sentinel',?,?,?,'fingerprint-sentinel','ACKNOWLEDGED',?,now()-interval '1 hour',now()+interval '22 hours',false,'PUBLICATION_REQUIRED')
            """,new UUID(0,n),account.id().value(),n==1?"PRODUCT_CREATE":"PRICE_CREATE",n==1?product.value():price.id().value(),"catalog-"+n,"catalog_ref"+n);
        client.getJson(path+"/unresolved?limit=1").andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].site_id").value(site.value().toString())).andExpect(jsonPath("$[0].payment_id").doesNotExist());
        client.getJson(path+"/unresolved?limit=1&after="+new UUID(0,1)).andExpect(jsonPath("$[0].operation_id").value(new UUID(0,2).toString()));
        jdbc.update("""
            insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,
              occurred_at,received_at,payload_hash,safe_payload,status,attempt_count,event_family,reference_object_type,provider_event_type,provider_api_version,error_category)
            values (?,'STRIPE',?,'evt_foreign','pi_foreign','PROVIDER_REFERENCE',now(),now(),?,'{}','DEAD',12,'PROVIDER_REFERENCE','PAYMENT','payment_intent.succeeded','2026-09-30.endive','PROCESSING_FAILED')
            """,new UUID(0,3),account.id().value(),"a".repeat(64));
        var dead=client.getJson(path+"/dead-references").andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].object_id").value("pi_foreign")).andExpect(jsonPath("$[0].status").value("DEAD"))
            .andExpect(jsonPath("$[0].attempt_count").value(12)).andReturn().getResponse().getContentAsString();
        assertThat(dead).doesNotContain("safe_payload","payload_hash","payment_id","credential-sentinel");
        client.getJson(path+"/dead-references?after="+new UUID(0,3)).andExpect(content().json("[]"));
        jdbc.update("update payment_accounts set account_mode='LIVE' where id=?",account.id().value());
        client.getJson(path+"/dead-references").andExpect(jsonPath("$[0].account_mode").value("LIVE"));
        assertThat(count("payments")).isZero();
    }
    @Test void manualRecoveryNeedsCsrfAndSingleIdempotencyKey() throws Exception {
        String recovery=path+"/"+UUID.randomUUID()+"/recover";
        client.mvc.perform(post(recovery).cookie(client.session).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        client.postJson(recovery,"{\"provider_reference\":\"pi_original\",\"creation_event_id\":\"evt_original\"}")
            .andExpect(status().isBadRequest());
        assertThat(count("idempotency_records")).isZero();
    }
}
