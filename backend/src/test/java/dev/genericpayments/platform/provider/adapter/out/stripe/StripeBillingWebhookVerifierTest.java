package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StripeBillingWebhookVerifierTest {
    static final Instant NOW=Instant.parse("2026-10-28T12:00:00Z");
    static final String SECRET="whsec_"+"C".repeat(32);
    final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"billing-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","stripe-sandbox-default");
    final StripeWebhookVerifier verifier=new StripeWebhookVerifier(new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),SECRET)));
    static List<String> types(){return List.of("customer.subscription.created","customer.subscription.updated","customer.subscription.deleted","customer.subscription.paused","customer.subscription.resumed","customer.subscription.trial_will_end","invoice.created","invoice.updated","invoice.finalized","invoice.finalization_failed","invoice.paid","invoice.payment_failed","invoice.payment_action_required","invoice.voided","invoice.marked_uncollectible","setup_intent.succeeded","setup_intent.setup_failed","setup_intent.canceled","subscription_schedule.created","subscription_schedule.updated","subscription_schedule.released","subscription_schedule.completed","subscription_schedule.canceled","subscription_schedule.aborted");}
    static List<String> legacyTypes(){return List.of("payment_intent.processing","payment_intent.requires_action","payment_intent.succeeded","payment_intent.payment_failed","payment_intent.canceled","refund.created","refund.updated","refund.failed");}
    static String kind(String type){return type.startsWith("customer.")?"subscription":type.substring(0,type.indexOf('.'));}
    static String reference(String type){return switch(kind(type)){case "subscription"->"sub_owned";case "invoice"->"in_owned";case "setup_intent"->"seti_owned";default->"sub_sched_owned";};}
    static String body(String type){return "{\"id\":\"evt_owned\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"created\":1,\"livemode\":false,\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\""+reference(type)+"\",\"object\":\""+kind(type)+"\",\"livemode\":false,\"client_secret\":\"never-cross-boundary\"}}}";}
    static String signature(byte[] raw)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((NOW.getEpochSecond()+".").getBytes(StandardCharsets.US_ASCII));return "t="+NOW.getEpochSecond()+",v1="+HexFormat.of().formatHex(mac.doFinal(raw));}
    @ParameterizedTest @MethodSource("types") void authenticatesEveryExplicitBillingType(String type)throws Exception{
        var raw=body(type).getBytes(StandardCharsets.UTF_8);
        var verified=verifier.verifyAny(raw,signature(raw),account,NOW);
        assertThat(verified).isPresent();
        var receipt=verified.orElseThrow();
        assertThat(receipt).isInstanceOf(StripeVerifiedReceipt.Billing.class);
        var envelope=((StripeVerifiedReceipt.Billing)receipt).envelope();
        assertThat(envelope.objectReference()).isEqualTo(reference(type));
        assertThat(envelope.eventId()).isEqualTo("evt_owned");
        assertThat(envelope.toString()).doesNotContain("never-cross-boundary");
        assertThat(envelope.eventType()).isEqualTo(switch(kind(type)){case "subscription"->"SUBSCRIPTION_CHANGED";case "invoice"->"SUBSCRIPTION_INVOICE_CHANGED";case "setup_intent"->"SUBSCRIPTION_SETUP_CHANGED";default->"SUBSCRIPTION_SCHEDULE_CHANGED";});
        assertThat(verifier.verify(raw,signature(raw),account,NOW)).isEmpty();
    }
    @Test void invalidAuthenticatedBillingShapesAndExactBytesFailClosed()throws Exception{
        String valid=body("subscription_schedule.created");
        for(String bad:List.of(valid.replace("sub_sched_owned","sub_owned"),valid.replace("\"object\":\"subscription_schedule\"","\"object\":\"subscription\""),valid.replace("\"livemode\":false","\"livemode\":true"),valid.replace("2026-09-30.endive","wrong"),valid.replace("\"created\":1","\"account\":\"acct_foreign\",\"created\":1"),valid.replace("\"created\":1","\"context\":\"foreign\",\"created\":1"),valid.replace("\"created\":1","\"created\":-1"),valid.replace("evt_owned","bad"),valid+" {}",valid.replace("\"id\":\"evt_owned\"","\"id\":\"evt_owned\",\"id\":\"evt_duplicate\""))){
            byte[] raw=bad.getBytes(StandardCharsets.UTF_8);assertThatThrownBy(()->verifier.verifyAny(raw,signature(raw),account,NOW)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Stripe webhook");
        }
        byte[] raw=valid.getBytes(StandardCharsets.UTF_8);String signed=signature(raw);
        assertThatThrownBy(()->verifier.verifyAny((valid+" ").getBytes(StandardCharsets.UTF_8),signed,account,NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->verifier.verifyAny(raw,signed,account,NOW.plusSeconds(301))).isInstanceOf(IllegalArgumentException.class);
        byte[] utf8={(byte)0xc3,(byte)0x28};assertThatThrownBy(()->verifier.verifyAny(utf8,signature(utf8),account,NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedStillRequiresAuthentication()throws Exception{
        byte[] raw=body("invoice.created").replace("invoice.created","invoice.not_allowed").getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verifyAny(raw,signature(raw),account,NOW)).isEmpty();
        assertThatThrownBy(()->verifier.verifyAny(raw,"t="+NOW.getEpochSecond()+",v1="+"0".repeat(64),account,NOW)).isInstanceOf(IllegalArgumentException.class);
        byte[] badTime=new String(raw,StandardCharsets.UTF_8).replace("\"created\":1","\"created\":-1").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->verifier.verifyAny(badTime,signature(badTime),account,NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @MethodSource("legacyTypes") void legacyPaymentRefundVerificationKeepsTypedReferenceCompatibility(String type)throws Exception{
        boolean refund=type.startsWith("refund.");
        String text=body("invoice.created").replace("invoice.created",type).replace("\"object\":\"invoice\"","\"object\":\""+(refund?"refund":"payment_intent")+"\"").replace("in_owned",refund?"re_owned":"pi_owned");
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);
        var verified=verifier.verifyAny(raw,signature(raw),account,NOW).orElseThrow();
        assertThat(verified).isInstanceOf(StripeVerifiedReceipt.Reference.class);
        var receipt=((StripeVerifiedReceipt.Reference)verified).receipt();
        assertThat(verifier.verify(raw,signature(raw),account,NOW)).contains(receipt);
        assertThat(receipt.providerEventType()).isEqualTo(type);
        assertThat(receipt.objectType()).isEqualTo(refund?dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt.ObjectType.REFUND:dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt.ObjectType.PAYMENT);
        assertThat(receipt.objectId()).isEqualTo(refund?"re_owned":"pi_owned");
    }
}
