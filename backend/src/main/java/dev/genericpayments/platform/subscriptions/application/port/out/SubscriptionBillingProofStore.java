package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.*;

/** Immutable request/result evidence. These records never grant access or store provider secrets. */
public interface SubscriptionBillingProofStore {
    record Trial(UUID operation,UUID subscription,PaymentAccountId account,long requestedEnd,
                 UUID step,String subscriptionReference,String item,String invoice,Long start) {
        public boolean verified(){return start!=null;}
        public Subscription.Period period(){return new Subscription.Period(java.time.Instant.ofEpochSecond(start),java.time.Instant.ofEpochSecond(requestedEnd));}
    }
    record Setup(UUID operation,UUID subscription,PaymentAccountId account,UUID step,String reference,String method){}
    record Resume(UUID operation,UUID subscription,PaymentAccountId account,UUID step,String reference,String invoice,Subscription.Period period){}
    Optional<Trial> trial(PaymentAccountId account,UUID subscription);
    Trial reserveTrial(Subscription subscription,SubscriptionOperation operation,long end);
    Trial bindTrial(Trial expected,UUID step,String reference,String item,String invoice,long start);
    default Trial bindTrialInTransaction(Trial expected,UUID step,String reference,String item,String invoice,long start){return bindTrial(expected,step,reference,item,invoice,start);}
    Optional<Setup> setup(PaymentAccountId account,UUID operation);
    Optional<Setup> setupReference(PaymentAccountId account,String reference);
    Optional<Setup> method(PaymentAccountId account,UUID subscription,String method);
    Setup recordSetup(Subscription subscription,SubscriptionOperation operation,UUID step,String reference);
    Setup bindMethod(Setup expected,String method);
    default void authorizeSetup(Subscription subscription,SubscriptionOperation operation){throw new UnsupportedOperationException();}
    Optional<Resume> resume(PaymentAccountId account,UUID operation);
    Resume recordResume(Subscription subscription,SubscriptionOperation operation,UUID step,String invoice,Subscription.Period period);
    default Resume recordResumeInTransaction(Subscription subscription,SubscriptionOperation operation,UUID step,String invoice,Subscription.Period period){return recordResume(subscription,operation,step,invoice,period);}
    default void authorizeResume(Subscription subscription,SubscriptionOperation operation){throw new UnsupportedOperationException();}
}
