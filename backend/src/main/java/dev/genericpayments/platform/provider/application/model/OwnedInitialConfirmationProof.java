package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant;
import java.util.Objects;

/** Versioned explicit-field proof encoding. descriptor is canonical input; hash is the SHA-256 Binding.proof. */
public final class OwnedInitialConfirmationProof {
    private OwnedInitialConfirmationProof() {}
    public static String hash(OwnedClockSubscriptionReceipt receipt,String invoice,String allocation,String intent){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(descriptor(receipt,invoice,allocation,intent).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String descriptor(OwnedClockSubscriptionReceipt receipt,String invoice,String allocation,String intent){
        Objects.requireNonNull(receipt);Objects.requireNonNull(invoice);Objects.requireNonNull(allocation);Objects.requireNonNull(intent);
        var i=receipt.intent();var c=i.clock();var o=c.owner();
        return SubscriptionClientGrant.canonical("gp:stripe-billing-owned-initial-proof:v1",i.id(),i.fingerprint(),i.admittedAt(),
            o.intent(),o.run(),o.account().value(),o.site().value(),o.customer().value(),o.externalAccount(),o.credentialsRef(),o.apiVersion(),o.initialFrozenTime(),o.createKey(),o.createFingerprint(),o.admittedAt(),
            c.creationOperation(),c.reference(),c.frozenTime(),c.providerCreated(),c.providerDeletesAfter(),c.observedAt(),c.customerFingerprint(),
            i.customerBinding(),i.customerCreationOperation(),i.customerReference(),i.subscription(),i.createOperation(),i.operationKey(),i.requestFingerprint(),i.expectedRevision(),i.policyVersion(),i.priceReference(),i.productReference(),
            receipt.providerOperation(),receipt.reference(),receipt.providerCreated(),receipt.itemReference(),receipt.periodStart(),receipt.periodEnd(),receipt.acknowledgedAt(),invoice,allocation,intent);
    }
}
