package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates exact bounded bytes before decoding or inspecting JSON. No provider IO. */
public final class StripeWebhookVerifier {
    private static final Set<String> TYPES=Set.of("payment_intent.processing","payment_intent.requires_action",
        "payment_intent.succeeded","payment_intent.payment_failed","payment_intent.canceled",
        "refund.created","refund.updated","refund.failed");
    private static final Map<String,String> BILLING=Map.ofEntries(
        Map.entry("customer.subscription.created","subscription"),Map.entry("customer.subscription.updated","subscription"),
        Map.entry("customer.subscription.deleted","subscription"),Map.entry("customer.subscription.paused","subscription"),
        Map.entry("customer.subscription.resumed","subscription"),Map.entry("customer.subscription.trial_will_end","subscription"),
        Map.entry("invoice.created","invoice"),Map.entry("invoice.updated","invoice"),Map.entry("invoice.finalized","invoice"),
        Map.entry("invoice.finalization_failed","invoice"),Map.entry("invoice.paid","invoice"),Map.entry("invoice.payment_failed","invoice"),
        Map.entry("invoice.payment_action_required","invoice"),Map.entry("invoice.voided","invoice"),Map.entry("invoice.marked_uncollectible","invoice"),
        Map.entry("setup_intent.succeeded","setup_intent"),Map.entry("setup_intent.setup_failed","setup_intent"),Map.entry("setup_intent.canceled","setup_intent"),
        Map.entry("subscription_schedule.created","subscription_schedule"),Map.entry("subscription_schedule.updated","subscription_schedule"),
        Map.entry("subscription_schedule.released","subscription_schedule"),Map.entry("subscription_schedule.completed","subscription_schedule"),
        Map.entry("subscription_schedule.canceled","subscription_schedule"),Map.entry("subscription_schedule.aborted","subscription_schedule"));
    private final StripeCredentialResolver credentials;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public StripeWebhookVerifier(StripeCredentialResolver credentials) { this.credentials=credentials; }

    public static boolean timestampAllowed(Instant timestamp, Instant now) {
        return timestamp != null && now != null && Duration.between(timestamp,now).abs().compareTo(Duration.ofSeconds(300))<=0;
    }

    public Optional<ProviderReferenceReceipt> verify(byte[] raw,String signature,PaymentAccount account,Instant receivedAt) {
        return verifyAny(raw,signature,account,receivedAt).filter(StripeVerifiedReceipt.Reference.class::isInstance)
            .map(StripeVerifiedReceipt.Reference.class::cast).map(StripeVerifiedReceipt.Reference::receipt);
    }

    public Optional<StripeVerifiedReceipt> verifyAny(byte[] raw,String signature,PaymentAccount account,Instant receivedAt) {
        try {
            if(raw==null || raw.length>262144 || signature==null || signature.length()>4096 || signature.getBytes(StandardCharsets.UTF_8).length>4096
                    || account==null || account.status()!=PaymentAccountStatus.ACTIVE
                    || account.provider()!=PaymentProviderType.STRIPE || account.mode()!=PaymentAccountMode.SANDBOX)throw invalid();
            String timestamp=null;List<byte[]> macs=new ArrayList<>();
            for(String part:signature.split(",",-1)) {
                String[] pair=part.trim().split("=",2);
                if(pair.length!=2)throw invalid();
                if(pair[0].equals("t")) {if(timestamp!=null || !pair[1].matches("[0-9]{1,12}"))throw invalid();timestamp=pair[1];}
                else if(pair[0].equals("v1")) {if(!pair[1].matches("[0-9a-fA-F]{64}"))throw invalid();macs.add(HexFormat.of().parseHex(pair[1]));}
            }
            if(timestamp==null || macs.isEmpty() || !timestampAllowed(Instant.ofEpochSecond(Long.parseLong(timestamp)),receivedAt))throw invalid();
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credentials.resolve(account).webhookSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));byte[] expected=mac.doFinal(raw);
            boolean matches=false;for(byte[] candidate:macs)matches|=MessageDigest.isEqual(expected,candidate);
            if(!matches)throw invalid();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            JsonNode event;
            try(var parser=json.createParser(text)) {event=json.readTree(parser);if(parser.nextToken()!=null)throw invalid();}
            if(event==null || !event.isObject() || !"event".equals(string(event,"object"))
                    || !event.path("livemode").isBoolean() || event.path("livemode").booleanValue()
                    || event.hasNonNull("account") || event.hasNonNull("context")
                    || !"2026-09-30.endive".equals(string(event,"api_version"))
                    || !event.path("created").isIntegralNumber() || !event.path("created").canConvertToLong()
                    || event.path("created").longValue()<0)throw invalid();
            String type=string(event,"type");
            if(!TYPES.contains(type)&&!BILLING.containsKey(type))return Optional.empty();
            JsonNode object=event.path("data").path("object");
            boolean payment=type.startsWith("payment_intent.");
            String billing=BILLING.get(type);
            if(!object.isObject() || !(billing!=null?billing:payment?"payment_intent":"refund").equals(string(object,"object"))
                    || ((billing!=null || payment || object.has("livemode")) && (!object.path("livemode").isBoolean() || object.path("livemode").booleanValue())))throw invalid();
            if(billing!=null){
                String eventId=string(event,"id"),reference=string(object,"id");
                String prefix=switch(billing){case "subscription"->"sub_";case "invoice"->"in_";case "setup_intent"->"seti_";default->"sub_sched_";};
                if(!eventId.matches("evt_[A-Za-z0-9]{1,251}")||!reference.matches(prefix+"[A-Za-z0-9]{1,"+(255-prefix.length())+"}"))throw invalid();
                String normalized=switch(billing){case "subscription"->"SUBSCRIPTION_CHANGED";case "invoice"->"SUBSCRIPTION_INVOICE_CHANGED";case "setup_intent"->"SUBSCRIPTION_SETUP_CHANGED";default->"SUBSCRIPTION_SCHEDULE_CHANGED";};
                return Optional.of(new StripeVerifiedReceipt.Billing(new dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope(
                    account.provider(),account.id(),eventId,reference,normalized,Instant.ofEpochSecond(event.path("created").longValue()),receivedAt)));
            }
            return Optional.of(new StripeVerifiedReceipt.Reference(new ProviderReferenceReceipt(account.provider(),account.id(),string(event,"id"),string(object,"id"),
                payment?ProviderReferenceReceipt.ObjectType.PAYMENT:ProviderReferenceReceipt.ObjectType.REFUND,type,
                string(event,"api_version"),Instant.ofEpochSecond(event.path("created").longValue()),receivedAt)));
        } catch(Exception failure) {throw invalid();}
    }
    private static String string(JsonNode node,String name) {
        JsonNode value=node.get(name);if(value==null || !value.isTextual())throw invalid();return value.textValue();
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid Stripe webhook");}
}
