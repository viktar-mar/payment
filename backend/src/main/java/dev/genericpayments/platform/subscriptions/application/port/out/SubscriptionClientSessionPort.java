package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
/** Ephemeral result only; never a fact or durable provider receipt. */
public interface SubscriptionClientSessionPort { SubscriptionClientSession retrieve(Lookup lookup,SubscriptionClientGrant grant,String actionToken); }
