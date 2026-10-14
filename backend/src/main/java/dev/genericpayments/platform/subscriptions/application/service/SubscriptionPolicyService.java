package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.ManageSubscriptionPoliciesUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import java.util.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionPolicyService implements ManageSubscriptionPoliciesUseCase {
 private final SubscriptionTransactionPort tx;private final SubscriptionAuthorityPort authority;private final SubscriptionStore subscriptions;
 private final SubscriptionPolicyPort policies;private final SubscriptionAdminReceiptPort receipts;private final AuditLogPort audit;private final Clock clock;
 public SubscriptionPolicyService(SubscriptionTransactionPort tx,SubscriptionAuthorityPort authority,SubscriptionStore subscriptions,SubscriptionPolicyPort policies,SubscriptionAdminReceiptPort receipts,AuditLogPort audit,Clock clock){this.tx=tx;this.authority=authority;this.subscriptions=subscriptions;this.policies=policies;this.receipts=receipts;this.audit=audit;this.clock=clock;}
 public SubscriptionPolicy load(AuthenticatedAdmin admin,SiteId site){return tx.required(()->{authority.revalidateSite(new SubscriptionActor.Admin(admin),site,false);return policies.load(site);});}
 public List<PlanTransition> transitions(AuthenticatedAdmin admin,SiteId site,ProductId product){return tx.required(()->{authority.revalidateSite(new SubscriptionActor.Admin(admin),site,false);var rows=policies.transitions(site,product);if(rows.size()>100)throw SubscriptionRuleErrors.conflict();return rows;});}
 public SubscriptionPolicy save(PolicyContext c,SubscriptionPolicy p,long expected){return tx.required(()->{
   authority.revalidateSite(new SubscriptionActor.Admin(c.admin()),c.siteId(),true);
   if(!p.siteId().equals(c.siteId()))throw SubscriptionRuleErrors.conflict();
   String fingerprint=p.siteId()+"|"+expected+"|"+p.enabled()+"|"+p.graceDays()+"|"+p.allowImmediateCancellation()+"|"+p.portalEnabled()+"|"+p.deferUpgrades()+"|"+new TreeSet<>(p.portalReturnUrls());
   return receipts.once(c.siteId(),c.admin().id().value(),"POLICY",c.idempotencyKey(),fingerprint,SubscriptionPolicy.class,()->{
     var old=policies.load(c.siteId());if(old.version()!=expected)throw SubscriptionRuleErrors.conflict();
     // Same Site lock as CREATE, held from authority revalidation through commit.
     if(old.enabled()&&!p.enabled()&&subscriptions.hasBlockingSiteSubscriptions(c.siteId()))throw SubscriptionRuleErrors.conflict();
     var saved=policies.save(p,expected);audit(c,"policy",c.siteId().value().toString());return saved;
   });
 });}
 public PlanTransition transition(PolicyContext c,PlanTransition t){return tx.required(()->{
   authority.revalidateSite(new SubscriptionActor.Admin(c.admin()),c.siteId(),true);
   if(!t.siteId().equals(c.siteId()))throw SubscriptionRuleErrors.conflict();
   return receipts.once(c.siteId(),c.admin().id().value(),"TRANSITION",c.idempotencyKey(),t.toString(),PlanTransition.class,()->{
     if(policies.load(c.siteId()).version()!=t.policyVersion())throw SubscriptionRuleErrors.conflict();
     policies.saveTransition(t);audit(c,"transition",t.productId().value().toString());return policies.transition(c.siteId(),t.productId(),t.fromPriceId(),t.toPriceId()).orElseThrow();
   });
 });}
 private void audit(PolicyContext c,String kind,String id){audit.append(new AuditEntry(UUID.randomUUID(),"ADMIN",c.admin().id().value().toString(),AuditAction.SUBSCRIPTION_POLICY_UPDATED,"subscription_"+kind,id,c.requestId(),null,Map.of(),clock.instant()));}
}
