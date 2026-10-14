package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
class SubscriptionOpenApiIntegrationTest extends SubscriptionApiFixture {
    @Test void stateEnumDoesNotRestrictAnyCursorParameter() throws Exception {
        String json=admin.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<Map<String,Object>> parameters=JsonPath.read(json,"$.paths..parameters[*]");
        var cursors=parameters.stream().filter(p->"cursor".equals(p.get("name"))).toList();
        assertThat(cursors).isNotEmpty().allSatisfy(p->{
            Map<?,?> schema=(Map<?,?>)p.get("schema");
            assertThat(schema.get("type")).isEqualTo("string");
            assertThat(schema.containsKey("enum")).isFalse();
        });
    }
    @Test void displayedStateAndNullableDebtDeadlineDescribeActualSafeViews() throws Exception {
        String json=admin.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> state=JsonPath.read(json,"$.components.schemas.SubscriptionResponse.properties.state");
        assertThat(state).containsKey("enum");
        java.util.List<String> states=JsonPath.read(json,"$.components.schemas.SubscriptionResponse.properties.state.enum");
        assertThat(states).containsExactlyInAnyOrder("INCOMPLETE","TRIALING","ACTIVE","PAST_DUE","PAUSED","UNPAID","CANCELLED","CANCEL_AT_PERIOD_END");
        java.util.List<Map<String,Object>> params=JsonPath.read(json,"$.paths['"+BASE+"'].get.parameters");
        Map<String,Object> parameter=params.stream().filter(p->p.get("name").equals("state")).findFirst().orElseThrow();
        assertThat(((Map<?,?>)parameter.get("schema")).containsKey("enum")).isTrue();
    }
    @Test void debtDeadlineAllowsNullWithoutMakingTheFieldOptional() throws Exception {
        String json=admin.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> deadline=JsonPath.read(json,"$.components.schemas.SubscriptionDebtResponse.properties.grace_deadline");
        assertThat(deadline).containsKey("anyOf");
        java.util.List<Map<String,Object>> variants=JsonPath.read(json,"$.components.schemas.SubscriptionDebtResponse.properties.grace_deadline.anyOf");
        assertThat(variants).anySatisfy(s->assertThat(s).containsEntry("type","null"));
        java.util.List<String> required=JsonPath.read(json,"$.components.schemas.SubscriptionDebtResponse.required");assertThat(required).contains("grace_deadline");
    }
    @Test void nullableFactsAndRoleSpecificCreationAreAccuratelyDescribed() throws Exception {
        String json=admin.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> entitled=JsonPath.read(json,"$.components.schemas.SubscriptionResponse.properties.entitled_price");
        assertThat(entitled).containsKey("anyOf");
        java.util.List<Map<String,Object>> nullable=(java.util.List<Map<String,Object>>)entitled.get("anyOf");
        assertThat(nullable).anySatisfy(s->assertThat(s).containsEntry("type","null"));
        Map<String,Object> props=JsonPath.read(json,"$.components.schemas.CustomerSubscriptionCreateRequest.properties");
        assertThat(props).containsOnlyKeys("price_id","recurring_consent");
        java.util.List<String> required=JsonPath.read(json,"$.components.schemas.AdminSubscriptionCreateRequest.required");
        assertThat(required).containsExactlyInAnyOrder("site_id","customer_id","price_id","recurring_consent");
        assertThat((String)JsonPath.read(json,"$.paths['"+BASE+"'].post.requestBody.content['application/json'].schema['$ref']")).endsWith("/CustomerSubscriptionCreateRequest");
    }
    @Test void safeAndCapabilitySchemasAreSeparateAndSubscriptionRoutesArePublished() throws Exception {
        String json=admin.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> paths=JsonPath.read(json,"$.paths");assertThat(paths.keySet()).contains(BASE,BASE+"/{id}",BASE+"/{id}/operations/{operationId}/action","/api/v1/admin/sites/{siteId}/subscription-policy");
        Map<String,Object> props=JsonPath.read(json,"$.components.schemas.SubscriptionOperationResponse.properties");
        assertThat(props).containsOnlyKeys("operation_id","subscription_id","status","action_available");
        Map<String,Object> view=JsonPath.read(json,"$.components.schemas.SubscriptionResponse.properties");
        assertThat(view).containsKeys("billing_price","entitled_price","pending_operation","permitted_actions","access");
        for(String value:java.util.List.of("SUBSCRIPTION","subscriptions:read","SUBSCRIPTION_READ"))assertThat(json.contains(value)).as("schema enum %s",value).isTrue();
    }
}
