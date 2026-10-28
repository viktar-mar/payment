package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;

/** Immutable application-authored plan evidence. Never contains SDK objects, payloads or secrets. */
public interface SubscriptionPlanProofStore {
    record Line(String price,String product,String item,long amount,Subscription.Period period,String creditedInvoice,List<String> creditedLines) {
        public Line {creditedLines=List.copyOf(creditedLines);}
    }
    record Quote(UUID id,PaymentAccountId account,SiteId site,CustomerId customer,UUID subscription,String subscriptionReference,String customerReference,
                 SubscriptionPriceTerms source,SubscriptionPriceTerms entitled,SubscriptionPriceTerms target,String sourcePrice,String targetPrice,String product,
                 String item,Subscription.Period period,Instant paidThrough,long revision,long policy,ChangeTiming timing,
                 Instant anchor,Instant expiresAt,long dueNow,List<Line> lines) {
        public Quote {lines=List.copyOf(lines);}
        public String reference(){return "bq1:"+id;}
    }
    Quote saveQuote(Quote quote);
    Optional<Quote> quote(PaymentAccountId account,UUID id);
    record Result(UUID operation,UUID subscription,PaymentAccountId account,UUID quote,UUID step,String reference,String invoice,Long pendingExpiry,String creditLine,String debitLine) {}
    record Proration(Quote quote,Result result) {}
    void authorize(Subscription subscription,SubscriptionOperation operation,Quote quote);
    Result recordResult(Subscription subscription,SubscriptionOperation operation,Quote quote,
                        dev.genericpayments.platform.provider.application.model.ProviderOperation step,String owner,String invoice,Long pendingExpiry,String creditLine,String debitLine);
    Optional<Result> result(PaymentAccountId account,UUID operation);
    Optional<Proration> proration(PaymentAccountId account,String invoice);
    List<Proration> prorations(PaymentAccountId account,UUID subscription);
    /** Canonical effective source settings captured with the original native CREATE receipt. */
    record SourcePhase(String prorationBehavior,String defaultPaymentMethod,String collectionMethod) {
        public SourcePhase {
            if(!Set.of("none","create_prorations","always_invoice").contains(prorationBehavior)
                ||defaultPaymentMethod!=null&&!defaultPaymentMethod.matches("pm_[A-Za-z0-9]{1,252}")
                ||!"charge_automatically".equals(collectionMethod))throw new IllegalArgumentException("Unsupported source phase");
        }
    }
    record Schedule(UUID operation,UUID subscription,PaymentAccountId account,UUID quote,UUID creationStep,String reference,long phaseStart,
                    UUID updateStep,Long targetEnd,UUID clearOperation,UUID releaseStep,SourcePhase sourcePhase) {
        public Schedule {Objects.requireNonNull(sourcePhase);}
    }
    record ScheduleProof(Quote quote,Schedule schedule) {}
    List<ScheduleProof> schedules(PaymentAccountId account,UUID subscription);
    Optional<Schedule> schedule(PaymentAccountId account,UUID operation);
    Optional<Schedule> scheduleReference(PaymentAccountId account,String reference);
    Schedule recordSchedule(Subscription subscription,SubscriptionOperation operation,Quote quote,
                            dev.genericpayments.platform.provider.application.model.ProviderOperation step,String owner,String reference,long phaseStart,SourcePhase sourcePhase);
    Schedule recordScheduleUpdate(Subscription subscription,SubscriptionOperation operation,Schedule expected,
                                  dev.genericpayments.platform.provider.application.model.ProviderOperation step,String owner,long targetEnd);
    Schedule recordRelease(Subscription subscription,SubscriptionOperation clearOperation,Schedule expected,
                           dev.genericpayments.platform.provider.application.model.ProviderOperation step,String owner);
}
