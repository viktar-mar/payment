package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionView.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionQueryService implements QuerySubscriptionsUseCase {
    private final SubscriptionTransactionPort tx;private final SubscriptionAuthorityPort authority;private final SubscriptionStore store;
    private final SubscriptionReadPort reads;private final SubscriptionPolicyPort policies;private final SubscriptionCatalogPort catalog;
    private final TrialEligibilityStore trials;private final LoadPaymentAccountPort accounts;private final SubscriptionProviderRegistry providers;private final Clock clock;
    private final SubscriptionBillingTimePort billingTime;
    public SubscriptionQueryService(SubscriptionTransactionPort tx,SubscriptionAuthorityPort authority,SubscriptionStore store,SubscriptionReadPort reads,SubscriptionPolicyPort policies,SubscriptionCatalogPort catalog,TrialEligibilityStore trials,LoadPaymentAccountPort accounts,SubscriptionProviderRegistry providers,Clock clock){
        this(tx,authority,store,reads,policies,catalog,trials,accounts,providers,clock,SubscriptionBillingTimePort.ordinary());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionQueryService(SubscriptionTransactionPort tx,SubscriptionAuthorityPort authority,SubscriptionStore store,SubscriptionReadPort reads,SubscriptionPolicyPort policies,SubscriptionCatalogPort catalog,TrialEligibilityStore trials,LoadPaymentAccountPort accounts,SubscriptionProviderRegistry providers,Clock clock,SubscriptionBillingTimePort billingTime){
        this.billingTime=Objects.requireNonNull(billingTime);
        this.tx=tx;this.authority=authority;this.store=store;this.reads=reads;this.policies=policies;this.catalog=catalog;this.trials=trials;this.accounts=accounts;this.providers=providers;this.clock=clock;}
    private void authorize(SubscriptionActor actor,SiteId site,CustomerId customer){
        if(customer==null)authority.revalidateSite(actor,site,false);
        else authority.revalidate(new SubscriptionCommandContext(actor,site,customer,actor instanceof SubscriptionActor.Customer c?c.session().origin():null,null,null),false);
    }
    private Subscription owned(SubscriptionActor actor,SiteId site,UUID id){
        // Authenticate the caller before selecting ownership. A foreign and missing row share the same answer.
        if(actor instanceof SubscriptionActor.Customer c)authorize(actor,site,c.session().customerId());else authority.revalidateSite(actor,site,false);
        var s=store.find(site,id).orElseThrow(SubscriptionQueryService::missing);
        if(actor instanceof SubscriptionActor.Customer c&&!s.customerId().equals(c.session().customerId()))throw missing();
        authorize(actor,site,s.customerId());return s;
    }
    public Page list(Query q){return tx.required(()->{authorize(q.actor(),q.siteId(),q.customerId());billingTime.requireUnscopedAllowed(q.siteId(),q.customerId());var now=clock.instant();var p=reads.list(q.siteId(),q.customerId(),q.productId(),q.state(),q.limit(),q.cursor(),now);return new Page(p.items().stream().map(s->view(s,now)).toList(),p.nextCursor());});}
    public SubscriptionView get(SubscriptionActor a,SiteId site,UUID id){return tx.required(()->view(owned(a,site,id)));}
    public SubscriptionHistory history(SubscriptionActor a,SiteId site,UUID id,int limit,String cursor){return tx.required(()->{owned(a,site,id);return reads.events(site,id,limit,cursor);});}
    public BillingPage billingRecords(SubscriptionActor a,SiteId site,UUID id,int limit,String cursor){return tx.required(()->{owned(a,site,id);return reads.billingRecords(site,id,limit,cursor);});}
    public List<SubscriptionOffer> offers(SubscriptionActor a,SiteId site,CustomerId customer,ProductId product){return tx.required(()->{authorize(a,site,customer);billingTime.requireUnscopedAllowed(site,customer);return offers(site,customer,product);});}
    private List<SubscriptionOffer> offers(SiteId site,CustomerId customer,ProductId product){
        var p=policies.load(site);if(!p.enabled())return List.of();
        var current=reads.current(site,customer,product);var result=new ArrayList<SubscriptionOffer>();
        for(var price:catalog.publishedForProduct(site,product)){
            if(result.size()==100)throw new PaymentApplicationException("RESPONSE_TOO_LARGE",413,"Subscription offers exceed limit");
            if(current.isEmpty()){result.add(new SubscriptionOffer(price,null,Set.of(),price.trialDays()>0&&trials.isEligible(site,customer,product)));continue;}
            var s=current.get();try{PlanChangeRules.requireChangeable(s,reads.unresolvedOperation(site,s.id()).isPresent());}catch(SubscriptionRuleViolation e){continue;}
            var transition=policies.transition(site,product,s.billingPrice().priceId(),price.priceId()).orElse(null);
            var timings=PlanChangeRules.allowedTiming(transition,s.billingPrice(),price,p,capabilities(s).conditionalUpdate());
            if(!timings.isEmpty())result.add(new SubscriptionOffer(price,transition.direction(),timings,false));
        }return List.copyOf(result);
    }
    public AccessDecision access(SubscriptionActor a,SiteId site,CustomerId customer,ProductId product){return tx.required(()->{authorize(a,site,customer);var now=clock.instant();return reads.current(site,customer,product).map(s->new SubscriptionAccessPolicy().evaluate(s,billingTime.sample(s.paymentAccountId(),s.siteId(),s.customerId(),now).billingAt())).orElseGet(()->new AccessDecision(false,AccessDecision.Reason.NO_SUBSCRIPTION,site,customer,product,null,null,null,0,now,null));});}
    public SubscriptionOperationResult operation(SubscriptionActor a,SiteId site,UUID id){return tx.required(()->{
        if(a instanceof SubscriptionActor.Customer c)authorize(a,site,c.session().customerId());else authority.revalidateSite(a,site,false);
        var o=reads.operation(site,id).orElseThrow(SubscriptionQueryService::missing);owned(a,site,o.subscriptionId());
        return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),null);
    });}
    private SubscriptionProviderModels.Capabilities capabilities(Subscription s){var account=accounts.findById(s.paymentAccountId()).orElseThrow(SubscriptionQueryService::missing);return providers.forAccount(account).capabilities(account);}
    private SubscriptionView view(Subscription s){
        var now=clock.instant();
        return view(s,billingTime.sample(s.paymentAccountId(),s.siteId(),s.customerId(),now));
    }
    private SubscriptionView view(Subscription s,java.time.Instant now){
        return view(s,new SubscriptionBillingTimePort.Sample(now,SubscriptionBillingTimePort.Mode.ORDINARY));
    }
    private SubscriptionView view(Subscription s,SubscriptionBillingTimePort.Sample sample){
        var now=sample.billingAt();
        var p=policies.load(s.siteId());var op=reads.unresolvedOperation(s.siteId(),s.id()).orElse(null);
        var actions=EnumSet.noneOf(PermittedAction.class);
        boolean accountActive=accounts.findById(s.paymentAccountId()).map(a->a.status()==dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus.ACTIVE).orElse(false);
        if(!sample.isolated()&&s.nonTerminal()&&p.enabled()&&op==null&&accountActive){
            var caps=capabilities(s);
            if(caps.setup())actions.add(PermittedAction.SETUP_METHOD);
            if(caps.portal()&&p.portalEnabled())actions.add(PermittedAction.OPEN_PORTAL);
            if(s.pendingChange()==null){
                if(s.cancellation()==null){if(SubscriptionCancellationRules.liveBoundary(s,now)!=null)actions.add(PermittedAction.CANCEL_PERIOD_END);
                    if(caps.safeImmediateCancel()&&SubscriptionCancellationRules.allowImmediate(s,p,now))actions.add(PermittedAction.CANCEL_IMMEDIATE);}
                else if(now.isBefore(s.cancellation().effectiveAt()))actions.add(PermittedAction.WITHDRAW_CANCELLATION);
            }else if(s.pendingChange().state()==Subscription.ChangeState.SCHEDULED)actions.add(PermittedAction.CLEAR_CHANGE);
            if(!offers(s.siteId(),s.customerId(),s.productId()).isEmpty())actions.add(PermittedAction.CHANGE_PLAN);
            if(s.debt()!=null){var b=reads.bill(s.siteId(),s.id(),s.debt().billingRecordId()).orElse(null);if(b!=null&&b.settlement()==BillingRecord.Settlement.OPEN)actions.add(PermittedAction.RECOVER_INVOICE);}
            if(s.billingStatus()==Subscription.Status.PAUSED&&s.pendingChange()==null)actions.add(PermittedAction.RESUME);
        }
        DebtView debt=null;if(s.debt()!=null){var b=reads.bill(s.siteId(),s.id(),s.debt().billingRecordId()).orElseThrow(SubscriptionQueryService::missing);debt=new DebtView(b.id(),s.debt().unpaidPeriod(),s.debt().graceDeadline(),b.amountDueMinor(),b.currency());}
        var next=s.pendingChange()==null?s.billingPrice():s.pendingChange().target();
        return new SubscriptionView(s,new SubscriptionAccessPolicy().evaluate(s,now),actions,new NextAmount(next.regularAmount().minorUnits(),next.regularAmount().currency(),false),debt,op==null?null:new PendingOperation(op.id(),op.kind(),op.status()));
    }
    public static PaymentApplicationException missing(){return new PaymentApplicationException("SUBSCRIPTION_NOT_FOUND",404,"Subscription unavailable");}
}
