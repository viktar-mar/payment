package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.Set;
public record SubscriptionOffer(SubscriptionPriceTerms price,PlanTransition.Direction direction,Set<ChangeTiming> allowedTimings,boolean trialEligible) {public SubscriptionOffer{allowedTimings=Set.copyOf(allowedTimings);}}
