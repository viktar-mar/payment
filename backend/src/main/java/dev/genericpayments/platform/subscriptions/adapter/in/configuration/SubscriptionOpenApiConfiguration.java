package dev.genericpayments.platform.subscriptions.adapter.in.configuration;
import dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionRequestPolicyFilter;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class SubscriptionOpenApiConfiguration {
 @Bean OpenApiCustomizer subscriptionSchemas(){return api->{
  var schemas=api.getComponents().getSchemas();
  var displayStates=Arrays.stream(dev.genericpayments.platform.subscriptions.domain.Subscription.DisplayState.values()).map(Enum::name).toList();
  ((Schema)schemas.get("SubscriptionResponse").getProperties().get("state")).setEnum(displayStates);
  for(var entry:schemas.entrySet())if(entry.getKey().startsWith("Subscription")&&entry.getValue().getProperties()!=null){
   var schema=entry.getValue();schema.setAdditionalProperties(false);
   if(!entry.getKey().endsWith("Request"))schema.setRequired(new ArrayList<String>(schema.getProperties().keySet()));
  }
  Map<String,List<String>> nullable=Map.ofEntries(
   Map.entry("SubscriptionResponse",List.of("entitled_price","period","paid_through","trial","cancellation","pending_change","pending_operation","debt","next_amount")),
   Map.entry("SubscriptionAccessResponse",List.of("subscription_id","entitled_price_id","entitled_price_version","valid_until")),
   Map.entry("SubscriptionDebtResponse",List.of("grace_deadline")),
   Map.entry("SubscriptionMutationResponse",List.of("next_action")),Map.entry("SubscriptionBillingResponse",List.of("previous_price")),
   Map.entry("SubscriptionEventResponse",List.of("operation_id","billing_record_id","payment_id")),Map.entry("SubscriptionOfferResponse",List.of("direction")),
   Map.entry("SubscriptionPage",List.of("next_cursor")),Map.entry("SubscriptionBillingPage",List.of("next_cursor")),Map.entry("SubscriptionEventPage",List.of("next_cursor")),
   Map.entry("SubscriptionFakeResponse",List.of("scenario","until")));
  nullable.forEach((name,fields)->{var schema=schemas.get(name);if(schema!=null)for(String field:fields){var original=(Schema)schema.getProperties().get(field);var nullSchema=new Schema();nullSchema.setTypes(Set.of("null"));schema.addProperty(field,new ComposedSchema().addAnyOfItem(original).addAnyOfItem(nullSchema));}});
  for(var entry:schemas.entrySet())if(entry.getKey().startsWith("Subscription")&&entry.getKey().endsWith("Request")&&entry.getValue().getProperties()!=null){
   var required=new ArrayList<String>(entry.getValue().getProperties().keySet());required.removeAll(List.of("site_id","customer_id","confirmation","scenario","until"));entry.getValue().setRequired(required);
  }
  var create=schemas.get("SubscriptionCreateRequest");
  for(String role:List.of("Customer","Partner","Admin")){
   var exact=new ObjectSchema();exact.setAdditionalProperties(false);
   for(String field:List.of("price_id","recurring_consent","customer_id","site_id"))if(!field.equals("customer_id")||!role.equals("Customer"))if(!field.equals("site_id")||role.equals("Admin"))exact.addProperty(field,(Schema)create.getProperties().get(field));
   exact.setRequired(new ArrayList<String>(exact.getProperties().keySet()));schemas.put(role+"SubscriptionCreateRequest",exact);
  }
  for(String name:List.of("CreateRequest","KeyRevealResponse")){var schema=schemas.get(name);if(schema!=null&&schema.getProperties()!=null){var scope=(Schema)schema.getProperties().get("scopes");if(scope!=null&&scope.getItems()!=null)scope.getItems().setEnum(Arrays.stream(PartnerScope.values()).map(PartnerScope::value).toList());}}
  for(var entry:api.getPaths().entrySet()){
   String path=entry.getKey();if(!SubscriptionRequestPolicyFilter.matches(path))continue;
   entry.getValue().readOperationsMap().forEach((method,op)->{
    boolean post=method.name().equals("POST"),preview=path.endsWith("/change-preview");
    if(post&&path.matches("/api/v1/(customer|partner|admin)/subscriptions")){
     String role=path.contains("/customer/")?"Customer":path.contains("/partner/")?"Partner":"Admin";
     op.getRequestBody().getContent().values().forEach(media->media.setSchema(new Schema().$ref("#/components/schemas/"+role+"SubscriptionCreateRequest")));
    }
    if(post&&!preview){if(op.getParameters()==null)op.setParameters(new ArrayList<>());op.addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(true).schema(new StringSchema().minLength(1).maxLength(255).pattern("[A-Za-z0-9_.:/-]+")));}
    if(path.startsWith("/api/v1/admin/subscriptions")&&!(post&&path.equals("/api/v1/admin/subscriptions")))op.addParametersItem(new Parameter().name("site_id").in("query").required(true).schema(new UUIDSchema()));
    if(method.name().equals("GET")&&path.matches("/api/v1/(admin|partner)/subscriptions"))op.addParametersItem(new Parameter().name("customer_id").in("query").required(path.contains("partner")).schema(new UUIDSchema()));
    if(post&&!preview&&!path.endsWith("/client-session")&&!path.endsWith("/fake-controls")&&!path.endsWith("/subscription-policy")&&!path.endsWith("/subscription-transitions")){
     var response=op.getResponses().remove("200");if(response!=null)op.getResponses().addApiResponse(path.endsWith("/portal-sessions")?"201":"202",response);
    }
    if(op.getParameters()!=null)for(var param:op.getParameters())if(param.getSchema()!=null){if(param.getName().equals("state")){var state=new StringSchema();state.setEnum(displayStates);param.setSchema(state);}if(param.getName().equals("limit")){param.getSchema().setMinimum(java.math.BigDecimal.ONE);param.getSchema().setMaximum(java.math.BigDecimal.valueOf(100));}if(param.getName().equals("cursor"))param.getSchema().setMaxLength(4096);}
   });
  }
 };}
}
