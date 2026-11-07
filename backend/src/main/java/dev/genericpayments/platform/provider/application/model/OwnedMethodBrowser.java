package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Instant;
import java.util.UUID;

/** Safe immutable descriptions; the store must authenticate every description against its original. */
public final class OwnedMethodBrowser {
    private OwnedMethodBrowser() {}
    public record Enrollment(UUID sessionId,String origin,Instant sessionExpiresAt) {
        public Enrollment { required(sessionId,sessionExpiresAt);if(!matches(origin,"https?://[^\\s\\p{Cntrl}]{1,2040}")||origin.length()>2048)throw invalid(); }
        @Override public String toString(){return "OwnedMethodBrowser.Enrollment[redacted]";}
    }
    public record Pending(Reservation reservation,Enrollment enrollment,PaymentAccount account,Subscription subscription,
            SubscriptionOperation operation,CustomerBinding binding,Setup setup,ProviderOperation setupStep) {
        public Pending { required(reservation,enrollment,account,subscription,operation,binding,setup,setupStep); }
        @Override public String toString(){return "OwnedMethodBrowser.Pending[redacted]";}
    }
    public record Root(UUID id,UUID reservationId,UUID setupOperation,UUID setupStep,long stepVersion,
            String setupReference,String keyVersion,String digest,Instant issuedAt,Instant expiresAt) {
        public Root {
            required(id,reservationId,setupOperation,setupStep,issuedAt,expiresAt);
            if(stepVersion!=2||!matches(setupReference,"seti_[A-Za-z0-9]{1,250}")||!matches(keyVersion,"v[1-9][0-9]{0,8}")
                ||!matches(digest,"[a-f0-9]{64}")||!expiresAt.isAfter(issuedAt)||expiresAt.isAfter(issuedAt.plusSeconds(600)))throw invalid();
        }
        @Override public String toString(){return "OwnedMethodBrowser.Root[redacted]";}
    }
    public record Selection(UUID rootId,String keyDigest,Instant startedAt,Instant deadlineAt) {
        public Selection {
            required(rootId,startedAt,deadlineAt);
            if(!matches(keyDigest,"[a-f0-9]{64}")||!deadlineAt.isAfter(startedAt)||deadlineAt.isAfter(startedAt.plusSeconds(30)))throw invalid();
        }
        @Override public String toString(){return "OwnedMethodBrowser.Selection[redacted]";}
    }
    private static void required(Object... values){for(var v:values)if(v==null)throw invalid();}
    private static boolean matches(String v,String pattern){return v!=null&&v.matches(pattern);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid owned method browser evidence");}
}
