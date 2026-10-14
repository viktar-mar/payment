package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionTransport.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ManageSubscriptionPoliciesUseCase;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/sites/{siteId}")
public class SubscriptionPolicyController {
 private final ManageSubscriptionPoliciesUseCase policies;
 public SubscriptionPolicyController(ManageSubscriptionPoliciesUseCase policies){this.policies=policies;}
 @GetMapping("/subscription-policy") public SubscriptionPolicyResponse get(@AuthenticationPrincipal AuthenticatedAdmin a,@PathVariable UUID siteId){return policy(policies.load(a,new SiteId(siteId)));}
 @PostMapping("/subscription-policy") public SubscriptionPolicyResponse save(@AuthenticationPrincipal AuthenticatedAdmin a,@PathVariable UUID siteId,@RequestBody SubscriptionPolicyRequest b,HttpServletRequest r){if(b.portalReturnUrls()==null||b.portalReturnUrls().size()>20||b.graceDays()<0||b.graceDays()>365)throw AbstractSubscriptionController.invalid();var site=new SiteId(siteId);return policy(policies.save(context(a,site,r),new SubscriptionPolicy(site,b.version(),b.enabled(),b.graceDays(),b.allowImmediateCancellation(),b.portalEnabled(),b.deferUpgrades(),b.portalReturnUrls()),b.version()));}
 @GetMapping("/products/{productId}/subscription-transitions") public SubscriptionTransitions transitions(@AuthenticationPrincipal AuthenticatedAdmin a,@PathVariable UUID siteId,@PathVariable UUID productId){return new SubscriptionTransitions(policies.transitions(a,new SiteId(siteId),new ProductId(productId)).stream().map(SubscriptionTransport::transition).toList());}
 @PostMapping("/products/{productId}/subscription-transitions") public SubscriptionTransitionResponse transition(@AuthenticationPrincipal AuthenticatedAdmin a,@PathVariable UUID siteId,@PathVariable UUID productId,@RequestBody SubscriptionTransitionRequest b,HttpServletRequest r){if(b.fromPriceId()==null||b.toPriceId()==null||b.direction()==null)throw AbstractSubscriptionController.invalid();var site=new SiteId(siteId);return SubscriptionTransport.transition(policies.transition(context(a,site,r),new PlanTransition(site,new ProductId(productId),new PriceId(b.fromPriceId()),new PriceId(b.toPriceId()),b.direction(),b.immediateAllowed(),b.policyVersion())));}
 private ManageSubscriptionPoliciesUseCase.PolicyContext context(AuthenticatedAdmin a,SiteId site,HttpServletRequest r){return new ManageSubscriptionPoliciesUseCase.PolicyContext(a,site,r.getHeader("Idempotency-Key"),RequestIdFilter.requestId(r));}
}
