package dev.genericpayments.platform.webhooks.adapter.out.persistence;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public final class CanonicalCallbackPayloadAdapter implements CallbackPayloadPort {
    private static final Set<String> LIFECYCLE_FIELDS=Set.of("schema_version","event_id","event_type","site_id","subscription_id","customer_id","product_id","revision","state","access_allowed","access_valid_until","occurred_at","billing_record_id","payment_id");
    private static final Set<String> FIELDS=Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at");
    private static final Set<String> DONATION_FIELDS=Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","donation_id","campaign_key","customer_id");
    private static final Set<String> SUBSCRIPTION_FIELDS=Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","subscription_id","billing_record_id","customer_id","product_id","billing_kind","price_id","price_version","period_start","period_end");
    private static final Map<String,String> TYPES=Map.of("payment.requires_payment_method","REQUIRES_PAYMENT_METHOD","payment.requires_action","REQUIRES_ACTION","payment.processing","PROCESSING","payment.succeeded","SUCCEEDED","payment.failed","FAILED","payment.cancelled","CANCELLED","refund.processing","PROCESSING","refund.succeeded","SUCCEEDED","refund.failed","FAILED");
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Override public byte[] bytes(OutboxPort.Claim claim) {
        try{
            if(claim.payload()==null||claim.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw invalid();var root=mapper.readTree(claim.payload());
            if(!root.isObject())throw invalid();
            var version=root.get("schema_version");
            if(version==null||!version.isIntegralNumber()||!version.canConvertToInt())throw invalid();
            if(version.intValue()==4)return lifecycle(root,claim);
            Set<String> expected=switch(version.intValue()){case 1 -> FIELDS;case 2 -> DONATION_FIELDS;case 3 -> SUBSCRIPTION_FIELDS;default -> throw invalid();};
            var fields=new HashSet<String>();root.fieldNames().forEachRemaining(fields::add);if(!fields.equals(expected))throw invalid();
            if(!uuid(root,"event_id").equals(claim.eventId())||!uuid(root,"site_id").equals(claim.siteId()))throw invalid();uuid(root,"payment_id");
            String type=text(root,"event_type",40),status=text(root,"payment_status",32);
            // Refund events carry the Payment projection, not a Refund status.
            if(!TYPES.containsKey(type)||!(type.startsWith("refund.")?Set.of("SUCCEEDED","PARTIALLY_REFUNDED","REFUNDED").contains(status):status.equals(TYPES.get(type))))throw invalid();
            var order=root.get("external_order_id");if(!order.isNull())text(root,"external_order_id",255);
            if(version.intValue()==2) {
                if(!order.isNull())throw invalid();
                uuid(root,"donation_id");
                if(!text(root,"campaign_key",63).matches("[a-z0-9][a-z0-9-]{2,62}"))throw invalid();
                if(!root.get("customer_id").isNull())uuid(root,"customer_id");
            }
            var amount=root.get("amount_minor");var currency=root.get("currency");
            if(version.intValue()==3) {
                if(!order.isNull()||amount.isNull())throw invalid();
                for(String field:List.of("subscription_id","billing_record_id","customer_id","product_id","price_id"))uuid(root,field);
                if(!Set.of("INITIAL","RENEWAL","PRORATION").contains(text(root,"billing_kind",16)))throw invalid();
                var priceVersion=root.get("price_version");
                if(!priceVersion.isIntegralNumber()||!priceVersion.canConvertToInt()||priceVersion.intValue()<=0)throw invalid();
                if(!Instant.parse(text(root,"period_start",64)).isBefore(Instant.parse(text(root,"period_end",64))))throw invalid();
            }
            if(amount.isNull()!=currency.isNull())throw invalid();
            if(!amount.isNull()&&(!amount.isIntegralNumber()||!amount.canConvertToLong()||amount.longValue()<=0||!text(root,"currency",3).matches("[A-Z]{3}")))throw invalid();
            if(!currency.isNull())Currency.getInstance(currency.textValue());
            Instant.parse(text(root,"occurred_at",64));
            var canonical=new TreeMap<String,Object>();for(String field:expected){var value=root.get(field);canonical.put(field,value.isNull()?null:value.isIntegralNumber()?value.longValue():value.textValue());}
            return mapper.writeValueAsBytes(canonical);
        }catch(Exception failure){throw invalid();}
    }
    private byte[] lifecycle(JsonNode root,OutboxPort.Claim claim)throws Exception{
        var fields=new HashSet<String>();root.fieldNames().forEachRemaining(fields::add);if(!fields.equals(LIFECYCLE_FIELDS))throw invalid();
        if(!uuid(root,"event_id").equals(claim.eventId())||!uuid(root,"site_id").equals(claim.siteId()))throw invalid();
        for(String field:List.of("subscription_id","customer_id","product_id"))uuid(root,field);
        for(String field:List.of("billing_record_id","payment_id"))if(!root.get(field).isNull())uuid(root,field);
        if(!Set.of("subscription.updated","subscription.access_changed").contains(text(root,"event_type",40)))throw invalid();
        if(!Set.of("INCOMPLETE","TRIALING","ACTIVE","PAST_DUE","PAUSED","UNPAID","CANCELLED","CANCEL_AT_PERIOD_END").contains(text(root,"state",32)))throw invalid();
        var revision=root.get("revision");if(!revision.isIntegralNumber()||!revision.canConvertToLong()||revision.longValue()<0||revision.longValue()>9007199254740991L)throw invalid();
        var allowed=root.get("access_allowed");if(!allowed.isBoolean())throw invalid();
        var occurred=Instant.parse(text(root,"occurred_at",64));
        if(allowed.booleanValue()){
            var until=Instant.parse(text(root,"access_valid_until",64));if(!occurred.isBefore(until)||until.isAfter(occurred.plusSeconds(300)))throw invalid();
        }else if(!root.get("access_valid_until").isNull())throw invalid();
        var canonical=new TreeMap<String,Object>();for(String field:LIFECYCLE_FIELDS){var value=root.get(field);canonical.put(field,value.isNull()?null:value.isBoolean()?value.booleanValue():value.isIntegralNumber()?value.longValue():value.textValue());}
        return mapper.writeValueAsBytes(canonical);
    }
    private static String text(JsonNode node,String field,int max){var value=node.get(field);if(value==null||!value.isTextual()||value.textValue().isBlank()||value.textValue().length()>max||value.textValue().chars().anyMatch(Character::isISOControl))throw invalid();return value.textValue();}
    private static UUID uuid(JsonNode node,String field){String text=text(node,field,36);UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid();return id;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid callback payload");}
}
