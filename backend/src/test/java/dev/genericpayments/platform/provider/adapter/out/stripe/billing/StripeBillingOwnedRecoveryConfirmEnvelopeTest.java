package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalRecoveryConfirmAdapterTest.*;
import static org.assertj.core.api.Assertions.*;

/** Fast wire-schema regressions: same actual authentication/service/SDK fixture, no new doubles. */
class StripeBillingOwnedRecoveryConfirmEnvelopeTest {
    private static final String VALID="""
        {"id":"pi_renewal","object":"payment_intent","livemode":false,"status":"succeeded",
         "customer":"cus_owned","payment_method":"pm_owned","currency":"eur","amount":1000,
         "amount_received":1000,"amount_capturable":0,"capture_method":"automatic",
         "payment_method_types":["card"],"last_payment_error":null,"next_action":null,"latest_charge":"ch_recovered"}
        """;

    @Test void literalValidWireReplyStillCommitsTheOriginalProviderEvidence(){
        var f=new Fixture();var before=f.g.local.state();f.g.queueClocks();f.g.sdk.http.reply(VALID);
        var ack=f.confirm();assertThat(ack.operation()).isEqualTo(f.confirmation.row());assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(f.confirmation.acknowledgments()).containsExactly(ack);assertThat(f.posts()).hasSize(1);assertThat(f.gets()).hasSize(17);
        assertThat(f.g.local.state()).isEqualTo(before);assertThat(f.g.local.trace()).doesNotContain("stop");
    }

    @ParameterizedTest @ValueSource(strings={"last_payment_error","next_action","both"})
    void absentErrorAndActionRetainTheExistingSuccessfulResponseSemantics(String absent){
        String body=VALID;
        if(absent.equals("last_payment_error")||absent.equals("both"))body=body.replace(",\"last_payment_error\":null","");
        if(absent.equals("next_action")||absent.equals("both"))body=body.replace(",\"next_action\":null","");
        var f=new Fixture();var before=f.g.local.state();f.g.queueClocks();f.g.sdk.http.reply(body);var ack=f.confirm();
        assertThat(f.confirmation.acknowledgments()).containsExactly(ack);assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(f.posts()).hasSize(1);assertThat(f.gets()).hasSize(17);assertThat(f.g.local.state()).isEqualTo(before);assertThat(f.g.local.trace()).doesNotContain("stop");
    }

    @ParameterizedTest @ValueSource(strings={
        "livemode-string-false","livemode-string-invalid","livemode-number","livemode-null","livemode-array","livemode-object",
        "amount-quoted","received-quoted","capturable-quoted","amount-fraction-token","received-exponent-token","capturable-fraction-token",
        "amount-outside-long","received-outside-long","capturable-outside-long",
        "duplicate-livemode","duplicate-received","duplicate-id",
        "id-number","object-array","customer-expanded","method-expanded","currency-array","status-array","capture-array",
        "types-string","types-number-element","charge-expanded","error-boolean","action-boolean"
    })
    void malformedOrUnsupportedWireCannotBeCoercedOrCollapsedIntoAnAcknowledgment(String malformed){
        // Mutate literal JSON bytes, never a parsed tree: duplicate fields and token types must
        // reach the official SDK unchanged so getter coercion cannot conceal the malformed wire.
        String body=switch(malformed){
            case "livemode-string-false"->VALID.replace("\"livemode\":false","\"livemode\":\"false\"");
            case "livemode-string-invalid"->VALID.replace("\"livemode\":false","\"livemode\":\"invalid\"");
            case "livemode-number"->VALID.replace("\"livemode\":false","\"livemode\":0");
            case "livemode-null"->VALID.replace("\"livemode\":false","\"livemode\":null");
            case "livemode-array"->VALID.replace("\"livemode\":false","\"livemode\":[]");
            case "livemode-object"->VALID.replace("\"livemode\":false","\"livemode\":{}");
            case "amount-quoted"->VALID.replace("\"amount\":1000","\"amount\":\"1000\"");
            case "received-quoted"->VALID.replace("\"amount_received\":1000","\"amount_received\":\"1000\"");
            case "capturable-quoted"->VALID.replace("\"amount_capturable\":0","\"amount_capturable\":\"0\"");
            case "amount-fraction-token"->VALID.replace("\"amount\":1000","\"amount\":1000.0");
            case "received-exponent-token"->VALID.replace("\"amount_received\":1000","\"amount_received\":1e3");
            case "capturable-fraction-token"->VALID.replace("\"amount_capturable\":0","\"amount_capturable\":0.0");
            case "amount-outside-long"->VALID.replace("\"amount\":1000","\"amount\":18446744073709552616");
            case "received-outside-long"->VALID.replace("\"amount_received\":1000","\"amount_received\":18446744073709552616");
            case "capturable-outside-long"->VALID.replace("\"amount_capturable\":0","\"amount_capturable\":18446744073709551616");
            case "duplicate-livemode"->VALID.replace("\"livemode\":false","\"livemode\":true,\"livemode\":false");
            case "duplicate-received"->VALID.replace("\"amount_received\":1000","\"amount_received\":999,\"amount_received\":1000");
            case "duplicate-id"->VALID.replace("\"id\":\"pi_renewal\"","\"id\":\"pi_other\",\"id\":\"pi_renewal\"");
            case "id-number"->VALID.replace("\"id\":\"pi_renewal\"","\"id\":1000");
            case "object-array"->VALID.replace("\"object\":\"payment_intent\"","\"object\":[\"payment_intent\"]");
            case "customer-expanded"->VALID.replace("\"customer\":\"cus_owned\"","\"customer\":{\"id\":\"cus_owned\",\"object\":\"customer\"}");
            case "method-expanded"->VALID.replace("\"payment_method\":\"pm_owned\"","\"payment_method\":{\"id\":\"pm_owned\",\"object\":\"payment_method\"}");
            case "currency-array"->VALID.replace("\"currency\":\"eur\"","\"currency\":[\"eur\"]");
            case "status-array"->VALID.replace("\"status\":\"succeeded\"","\"status\":[\"succeeded\"]");
            case "capture-array"->VALID.replace("\"capture_method\":\"automatic\"","\"capture_method\":[\"automatic\"]");
            case "types-string"->VALID.replace("\"payment_method_types\":[\"card\"]","\"payment_method_types\":\"card\"");
            case "types-number-element"->VALID.replace("\"payment_method_types\":[\"card\"]","\"payment_method_types\":[1]");
            case "charge-expanded"->VALID.replace("\"latest_charge\":\"ch_recovered\"","\"latest_charge\":{\"id\":\"ch_recovered\",\"object\":\"charge\"}");
            case "error-boolean"->VALID.replace("\"last_payment_error\":null","\"last_payment_error\":false");
            case "action-boolean"->VALID.replace("\"next_action\":null","\"next_action\":false");
            default->throw new AssertionError(malformed);
        };
        assertThat(body).isNotEqualTo(VALID);var f=new Fixture();var claimed=new AtomicReference<ProviderOperation>();
        f.confirmation.afterCommit(()->{var row=f.confirmation.row();if(row!=null&&row.status()==ProviderOperation.Status.DISPATCHING)claimed.compareAndSet(null,row);});
        f.g.queueClocks();f.g.sdk.http.reply(body);refused(f::confirm);
        assertThat(f.gets()).hasSize(17);assertThat(f.posts()).hasSize(1);assertThat(f.posts().getFirst().url().getPath()).isEqualTo("/v1/payment_intents/pi_renewal/confirm");
        f.onlyOwnUnknown();assertThat(f.confirmation.acknowledgments()).isEmpty();assertThat(f.g.sdk.http.replies).isEmpty();
        var own=claimed.get();assertThat(own).isNotNull();
        var expected=new ProviderOperation(own.id(),own.accountId(),own.externalAccountId(),own.credentialsRef(),own.kind(),own.resourceId(),own.operationKey(),own.fingerprint(),
            ProviderOperation.Status.UNKNOWN,null,own.firstDispatchedAt(),own.retryBefore(),null,null,2);
        assertThat(f.confirmation.row()).isEqualTo(expected);
    }
}
