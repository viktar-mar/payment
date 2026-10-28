package dev.genericpayments.platform.payments.adapter.in.configuration;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

/** Align explicitly listed payment schemas with the existing snake-case HTTP serialization. */
@Configuration(proxyBeanMethods=false)
public class PaymentAdminOpenApiConfiguration {
    @Bean OpenApiCustomizer paymentAdminSchemas(){return api->{
        var schemas=api.getComponents().getSchemas();
        for(String name:List.of("RefundRequest","RefundResponse","FakeRequest","ApiValidationProblem","Projection","Page","Attempt","Event","RefundView","Outbox","Delivery","Details","Accepted",
                "ProviderOperationView","DeadProviderReferenceView","RecoveryRequest","RecoveryAccepted","UpdateReturnUrl","CheckoutReturnUrlPolicy","ClientPaymentSessionResponse")){
            Schema<?> schema=schemas.get(name);if(schema==null||schema.getProperties()==null)continue;
            var properties=new LinkedHashMap<String,Schema>();
            schema.getProperties().forEach((key,value)->properties.put(snake(key),value));schema.setProperties(properties);
            if(schema.getRequired()!=null)schema.setRequired(schema.getRequired().stream().map(PaymentAdminOpenApiConfiguration::snake).toList());
        }
        enumeration(schemas,"Projection","status",PaymentStatus.values());enumeration(schemas,"Projection","provider",PaymentProviderType.values());enumeration(schemas,"Projection","source",PaymentSource.values());
        enumeration(schemas,"Attempt","status",PaymentAttemptStatus.values());enumeration(schemas,"Event","event_type",PaymentEventType.values());enumeration(schemas,"Event","source",PaymentEventSource.values());enumeration(schemas,"RefundView","status",RefundStatus.values());
        enumeration(schemas,"Projection","tax_mode",dev.genericpayments.platform.catalog.domain.TaxMode.values());
        var projection=schemas.get("Projection");
        if(projection!=null&&projection.getProperties()!=null)for(String field:List.of("subscription_id","billing_record_id","subscription_billing_kind","subscription_period_start","subscription_period_end")) {
            var property=(Schema<?>)projection.getProperties().get(field);
            if(property!=null){property.setNullable(true);property.setTypes(new LinkedHashSet<>(List.of("string","null")));}
        }
        enumeration(schemas,"Projection","subscription_billing_kind",SubscriptionBillingContext.Kind.values());
        enumeration(schemas,"Outbox","status",dev.genericpayments.platform.webhooks.domain.OutboxStatus.values());
        enumeration(schemas,"Delivery","delivery_mode",dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.Mode.values());
        enumeration(schemas,"Delivery","error_category",dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort.ErrorCategory.values());
        values(schemas,"Outbox","callback_type",List.of("payment.requires_payment_method","payment.requires_action","payment.processing","payment.succeeded","payment.failed","payment.cancelled","refund.processing","refund.succeeded","refund.failed"));
        values(schemas,"Delivery","status",List.of("SUCCEEDED","FAILED"));values(schemas,"Accepted","status",List.of("ACCEPTED"));
        values(schemas,"RefundView","reason_code",List.of("REQUESTED_BY_CUSTOMER","DUPLICATE","FRAUDULENT","OTHER"));values(schemas,"FieldError","code",List.of("INVALID_VALUE"));
        var clientSession=schemas.get("ClientPaymentSessionResponse");
        if(clientSession!=null)clientSession.setRequired(List.of("checkout_id","payment_id","attempt_id","provider_type",
            "confirmation_mode","publishable_key","client_secret","expires_at","return_url"));
        values(schemas,"ClientPaymentSessionResponse","provider_type",List.of("STRIPE"));
        values(schemas,"ClientPaymentSessionResponse","confirmation_mode",List.of("CLIENT_PROVIDER"));
        var path=api.getPaths().get("/api/v1/public/checkouts/{checkoutId}/client-session");
        if(path!=null&&path.getPost()!=null){
            var operation=path.getPost();
            operation.setDescription("Prepare an ephemeral client capability using this checkout's confirm-scoped bearer, exact allowed Origin and idempotency key. The request body must be empty.");
            operation.setRequestBody(null);
            operation.addParametersItem(new Parameter().name("Origin").in("header").required(true)
                .description("Exactly one Origin matching this checkout's allowed Site origin.")
                .schema(new StringSchema().minLength(1)));
            operation.addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(true)
                .description("Exactly one nonblank key, without ISO control characters; reuse for the same operation.")
                .schema(new StringSchema().minLength(1).maxLength(255)));
            api.getComponents().addSecuritySchemes("checkoutBearer",new SecurityScheme().type(SecurityScheme.Type.HTTP)
                .scheme("bearer").bearerFormat("checkout token")
                .description("Exactly one Authorization: Bearer header with the 43-character opaque checkout token ([A-Za-z0-9_-]), scoped to confirm this checkout. This is not an Admin or Customer session."));
            operation.setSecurity(List.of(new SecurityRequirement().addList("checkoutBearer")));
            var response=operation.getResponses().get("200");
            if(response!=null)response.setContent(new Content().addMediaType("application/json",new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/ClientPaymentSessionResponse"))));
        }
    };}
    private static String snake(String name){return name.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void enumeration(Map<String,Schema> schemas,String type,String field,Enum<?>[] values){var schema=schemas.get(type);if(schema!=null&&schema.getProperties()!=null){Schema property=(Schema)schema.getProperties().get(field);if(property!=null)property.setEnum(Arrays.stream(values).map(Enum::name).toList());}}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void values(Map<String,Schema> schemas,String type,String field,List<String> values){var schema=schemas.get(type);if(schema!=null&&schema.getProperties()!=null){Schema property=(Schema)schema.getProperties().get(field);if(property!=null)property.setEnum(values);}}
}
