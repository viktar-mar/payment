package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.List;
public interface ManageSubscriptionPoliciesUseCase {
 record PolicyContext(AuthenticatedAdmin admin,SiteId siteId,String idempotencyKey,String requestId) { @Override public String toString(){return "PolicyContext[redacted]";} }
 SubscriptionPolicy load(AuthenticatedAdmin admin,SiteId site);
 List<PlanTransition> transitions(AuthenticatedAdmin admin,SiteId site,ProductId product);
 SubscriptionPolicy save(PolicyContext context,SubscriptionPolicy policy,long expectedVersion);
 PlanTransition transition(PolicyContext context,PlanTransition transition);
}
