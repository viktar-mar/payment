package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase.Command;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Versioned durable server decision; no fallback to mutable eligibility is permitted on replay. */
public final class CreateSubscriptionFingerprint {
    private CreateSubscriptionFingerprint() {}
    public static boolean startsTrial(String fingerprint) {
        if(fingerprint==null||!fingerprint.matches("v1\\.(trial|paid)\\.[0-9a-f]{64}"))throw SubscriptionRuleErrors.conflict();
        return fingerprint.startsWith("v1.trial.");
    }
    static String encode(Command command,String authority,SubscriptionPriceTerms terms,long revision,long policy,boolean trial) {
        var c=command.context();
        String canonical=canonical("CREATE","v1",trial,authority,c.siteId().value(),c.customerId().value(),c.origin(),
            command.recurringConsent(),terms.priceId().value(),terms.priceKey(),terms.version(),terms.productId().value(),
            terms.regularAmount().minorUnits(),terms.regularAmount().currency(),terms.interval(),terms.trialDays(),
            terms.trialPolicy(),terms.taxMode(),revision,policy);
        return (trial?"v1.trial.":"v1.paid.")+digest(canonical);
    }
    private static String canonical(Object... fields) {
        var value=new StringBuilder();
        for(var field:fields){String s=field==null?null:field.toString();if(s==null)value.append("-1:");else value.append(s.length()).append(':').append(s);}
        return value.toString();
    }
    static String digest(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
