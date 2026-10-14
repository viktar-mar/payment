package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
public sealed interface SubscriptionActor {
    record Customer(CustomerSessionContext session) implements SubscriptionActor {}
    record Partner(AuthenticatedSite site) implements SubscriptionActor {}
    record Admin(AuthenticatedAdmin admin) implements SubscriptionActor {}
}
