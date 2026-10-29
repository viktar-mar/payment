package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ConfirmSubscriptionActionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
/** Short locked phases only. Provider calls belong to the services, outside these transactions. */
@Component
public class SubscriptionInteractionTransaction {
    private final SubscriptionTransactionPort tx;private final SubscriptionOperationStore operations;
    private final SubscriptionStore subscriptions;private final SubscriptionAuthorityPort authority;
    private final SubscriptionPolicyPort policies;private final LockPaymentAccountPort accounts;
    private final SubscriptionActionStore actions;private final SubscriptionPortalStore portals;private final TrialEligibilityStore trials;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required=false) @org.springframework.beans.factory.annotation.Qualifier("providerReferenceClock")
    private Clock securityClock=Clock.systemUTC();
    public SubscriptionInteractionTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,
        SubscriptionAuthorityPort authority,SubscriptionPolicyPort policies,LockPaymentAccountPort accounts,SubscriptionActionStore actions,
        SubscriptionPortalStore portals,TrialEligibilityStore trials,Clock clock){
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.authority=authority;this.policies=policies;this.accounts=accounts;this.actions=actions;this.portals=portals;this.trials=trials;this.clock=clock;
    }
    public record State(Subscription subscription,PaymentAccount account,SubscriptionPolicy policy,SubscriptionOperation operation,boolean owner) {}
    public void outside(){if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");}
    public State candidate(SubscriptionCommandContext c,UUID id){outside();return tx.required(()->authorized(c,id,null,false));}
    private State authorized(SubscriptionCommandContext c,UUID id,SubscriptionOperation o,boolean lock){
        principal(c);authority.revalidate(c,true);
        var s=subscriptions.find(c.siteId(),id).orElseThrow(SubscriptionRuleErrors::conflict);
        if(!s.customerId().equals(c.customerId())||o!=null&&(!o.siteId().equals(c.siteId())||!o.customerId().equals(c.customerId())||!id.equals(o.subscriptionId())))throw SubscriptionRuleErrors.conflict();
        var a=accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
        if(lock)s=subscriptions.lock(c.siteId(),id);
        return new State(s,a,policies.load(c.siteId()),o,false);
    }
    public State originalCreate(SubscriptionCommandContext c,UUID id){
        var candidate=candidate(c,id);if(candidate.subscription().billingStatus()!=Subscription.Status.INCOMPLETE)return null;
        var owner=trials.find(c.siteId(),c.customerId(),candidate.subscription().productId()).filter(e->e.consumedAt()==null).map(e->e.reservedByOperation()).orElse(null);
        var o=owner==null?operations.unresolved(c.siteId(),id).stream().filter(x->x.kind()==SubscriptionOperation.Kind.CREATE).findFirst().orElse(null):operations.find(c.siteId(),id,owner).orElse(null);
        return o==null?null:action(c,id,o.id());
    }
    public State action(SubscriptionCommandContext c,UUID id,UUID operation){
        outside();return tx.requiresNew(()->{
            principal(c);
            var found=operations.find(c.siteId(),id,operation)
                .filter(o->o.customerId().equals(c.customerId()))
                .orElseThrow(()->{authority.revalidate(c,true);return SubscriptionQueryService.missing();});
            var o=operations.lock(found.id());
            if(!Set.of(SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Kind.RESUME).contains(o.kind()))throw SubscriptionRuleErrors.conflict();
            if(o.kind()==SubscriptionOperation.Kind.CREATE)CreateSubscriptionFingerprint.startsTrial(o.fingerprint());
            return authorized(c,id,o,true);
        });
    }
    public State reserve(SubscriptionCommandContext c,UUID id,SubscriptionOperation.Kind kind,String request,State candidate){
        validateKey(c);var now=clock.instant();var oid=UUID.randomUUID();
        var o=new SubscriptionOperation(oid,c.siteId(),c.customerId(),id,kind,SubscriptionOperation.Status.RESERVED,principal(c),digest(c.idempotencyKey()),request,
            "subscription:"+candidate.account().id().value()+":"+oid+":"+kind,candidate.subscription().revision(),candidate.policy().version(),candidate.subscription().providerReference(),null,now,now,0);
        return translated(()->tx.requiresNew(()->{
            var claim=operations.claim(o);var r=authorized(c,id,claim.operation(),true);
            if(!claim.operation().fingerprint().equals(request))throw SubscriptionRuleErrors.conflict();
            if(!claim.owner())return r;
            if(!r.policy().enabled()||!r.subscription().nonTerminal()||r.account().status()!=PaymentAccountStatus.ACTIVE
                ||!r.account().id().equals(candidate.account().id())||r.subscription().revision()!=candidate.subscription().revision()||r.policy().version()!=candidate.policy().version())throw SubscriptionRuleErrors.conflict();
            if(kind==SubscriptionOperation.Kind.SETUP&&operations.unresolved(c.siteId(),id).stream().anyMatch(x->!x.id().equals(o.id())&&x.kind()!=SubscriptionOperation.Kind.PORTAL&&x.kind()!=SubscriptionOperation.Kind.RECONCILE))throw SubscriptionRuleErrors.conflict();
            return new State(r.subscription(),r.account(),r.policy(),claim.operation(),true);
        }));
    }
    public NextAction issued(SubscriptionCommandContext c,State r,NextAction action){
        if(action==null)return null;
        return tx.requiresNew(()->{
            var now=clock.instant();var fresh=authorized(c,r.subscription().id(),operations.lock(r.operation().id()),true);
            if(!fresh.subscription().nonTerminal()||!fresh.policy().enabled()||fresh.account().status()!=PaymentAccountStatus.ACTIVE)throw SubscriptionRuleErrors.conflict();
            if(fresh.account().provider()==PaymentProviderType.STRIPE){
                requireSame(r,fresh);var g=billingGrant(fresh,action.secret());if(!g.available(securityClock.instant())||!g.expiresAt().equals(action.expiresAt()))throw SubscriptionRuleErrors.conflict();return action;
            }
            return issue(fresh,action,now);
        });
    }
    private NextAction issue(State r,NextAction action,Instant now){
        if(!r.subscription().nonTerminal()||!r.policy().enabled()||r.account().status()!=PaymentAccountStatus.ACTIVE)throw SubscriptionRuleErrors.conflict();
        var expires=action.expiresAt().isBefore(now.plusSeconds(600))?action.expiresAt():now.plusSeconds(600);
        var o=r.operation();var s=r.subscription();var digest=digest(action.secret());
        actions.issue(s.siteId(),s.customerId(),s.paymentAccountId(),s.id(),o.id(),action.kind().name(),digest,expires);
        if(o.kind()==SubscriptionOperation.Kind.SETUP||o.kind()==SubscriptionOperation.Kind.CREATE||o.kind()==SubscriptionOperation.Kind.RESUME||o.kind()==SubscriptionOperation.Kind.CHANGE)
            actions.consent(digest,o.kind().name(),true,o.policyVersion(),o.createdAt());
        return new NextAction(action.kind(),action.secret(),expires);
    }
    public boolean replay(ConfirmSubscriptionActionUseCase.Command c){
        validateKey(c.context());validateToken(c.actionToken());
        return tx.requiresNew(()->{authorized(c.context(),c.subscriptionId(),operations.lock(c.operationId()),true);return actions.replay(c.context().siteId(),principal(c.context()),digest(c.context().idempotencyKey()),confirmationFingerprint(c));});
    }
    public SubscriptionActionStore.ClaimDisposition claim(ConfirmSubscriptionActionUseCase.Command c,State r,NextAction action){
        return translated(()->tx.requiresNew(()->{
            var fresh=authorized(c.context(),c.subscriptionId(),operations.lock(c.operationId()),true);
            if(fresh.account().provider()==PaymentProviderType.STRIPE&&actions.replay(c.context().siteId(),principal(c.context()),digest(c.context().idempotencyKey()),confirmationFingerprint(c)))return SubscriptionActionStore.ClaimDisposition.REPLAY;
            if(!fresh.subscription().nonTerminal()||!fresh.policy().enabled()||fresh.account().status()!=PaymentAccountStatus.ACTIVE)throw SubscriptionRuleErrors.conflict();
            if(fresh.account().provider()==PaymentProviderType.STRIPE){
                requireSame(r,fresh);var session=customer(c.context());var g=billingGrant(fresh,c.actionToken());
                if(!g.available(securityClock.instant())||!session.sessionId().value().equals(g.sessionId())||!session.origin().equals(g.origin())||g.clientExpiresAt()==null||!securityClock.instant().isBefore(g.clientExpiresAt()))throw SubscriptionRuleErrors.conflict();
                return actions.claimBilling(c.context().siteId(),c.context().customerId(),fresh.account().id(),c.subscriptionId(),c.operationId(),digest(c.actionToken()),session.sessionId().value(),session.origin(),principal(c.context()),digest(c.context().idempotencyKey()),confirmationFingerprint(c),securityClock.instant());
            }
            issue(fresh,action,clock.instant());
            return actions.claim(c.context().siteId(),c.context().customerId(),r.account().id(),c.subscriptionId(),c.operationId(),action.kind().name(),digest(c.actionToken()),principal(c.context()),digest(c.context().idempotencyKey()),confirmationFingerprint(c),clock.instant());
        }));
    }
    public SubscriptionClientGrant clientGrant(SubscriptionCommandContext c,State r,String token){
        validateToken(token);return tx.requiresNew(()->{var fresh=authorized(c,r.subscription().id(),operations.lock(r.operation().id()),true);requireSame(r,fresh);customer(c);return availableClientGrant(c,fresh,token);});
    }
    public SubscriptionClientSession clientIssued(SubscriptionCommandContext c,State r,SubscriptionClientGrant expected,String token,SubscriptionClientSession result){
        return tx.requiresNew(()->{
            var fresh=authorized(c,r.subscription().id(),operations.lock(r.operation().id()),true);requireSame(r,fresh);var session=customer(c);var grant=availableClientGrant(c,fresh,token);
            if(!grant.id().equals(expected.id())||!grant.binding().equals(expected.binding())||!"stripe".equals(result.provider())||result.flow()!=grant.binding().flow()||!token.equals(result.actionToken())||!result.expiresAt().equals(grant.expiresAt()))throw SubscriptionRuleErrors.conflict();
            var expiry=grant.expiresAt().isBefore(session.expiresAt())?grant.expiresAt():session.expiresAt();
            actions.bindSession(grant.tokenDigest(),session.sessionId().value(),session.origin(),expiry,securityClock.instant());
            return new SubscriptionClientSession(result.provider(),result.flow(),result.publishableKey(),result.clientSecret(),token,expiry);
        });
    }
    private SubscriptionClientGrant availableClientGrant(SubscriptionCommandContext c,State r,String token){
        var session=customer(c);if(r.account().provider()!=PaymentProviderType.STRIPE||r.account().status()!=PaymentAccountStatus.ACTIVE||!r.policy().enabled()||!r.subscription().nonTerminal()||!Set.of(SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Kind.RESUME,SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Kind.RECOVER).contains(r.operation().kind())||r.operation().status()==SubscriptionOperation.Status.SUCCEEDED||r.operation().status()==SubscriptionOperation.Status.FAILED)throw SubscriptionRuleErrors.conflict();
        var grant=billingGrant(r,token);if(!grant.available(securityClock.instant())||grant.sessionId()!=null&&(!session.sessionId().value().equals(grant.sessionId())||!session.origin().equals(grant.origin())))throw SubscriptionRuleErrors.conflict();return grant;
    }
    private SubscriptionClientGrant billingGrant(State r,String token){
        if(token==null||!token.matches("bga1_[A-Za-z0-9_-]{43}"))throw SubscriptionRuleErrors.conflict();
        var g=actions.billing(digest(token)).orElseThrow(SubscriptionRuleErrors::conflict);
        if(!g.account().equals(r.account().id())||!g.site().equals(r.subscription().siteId())||!g.customer().equals(r.subscription().customerId())||!g.subscription().equals(r.subscription().id())||!g.operation().equals(r.operation().id())
            ||!java.security.MessageDigest.isEqual(g.tokenDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII),digest(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw SubscriptionRuleErrors.conflict();
        if(r.operation().kind()==SubscriptionOperation.Kind.CHANGE&&(!g.binding().schema().equals("change-v1")||r.subscription().pendingChange()==null||!r.subscription().pendingChange().operationId().equals(r.operation().id())))throw SubscriptionRuleErrors.conflict();
        if(r.operation().kind()==SubscriptionOperation.Kind.RECOVER&&(!recoveryPolicyCurrent(r)||!g.binding().schema().equals("recovery-v1")||r.subscription().debt()==null||!SubscriptionRecoveryTransaction.recoveryBill(r.operation()).filter(r.subscription().debt().billingRecordId()::equals).isPresent()))throw SubscriptionRuleErrors.conflict();
        return g;
    }
    private dev.genericpayments.platform.customers.application.model.CustomerSessionContext customer(SubscriptionCommandContext c){
        if(!(c.actor() instanceof SubscriptionActor.Customer actor)||!securityClock.instant().isBefore(actor.session().expiresAt()))throw SubscriptionRuleErrors.conflict();
        actor.session().requireScope(dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE);return actor.session();
    }
    private static void requireSame(State before,State after){if(!before.account().equals(after.account())||before.subscription().revision()!=after.subscription().revision()||before.operation().version()!=after.operation().version())throw SubscriptionRuleErrors.conflict();}
    private static boolean recoveryPolicyCurrent(State r){return r.operation().kind()!=SubscriptionOperation.Kind.RECOVER||r.policy().enabled()&&r.policy().version()==r.operation().policyVersion();}
    public boolean browserConfirmationAllowed(ConfirmSubscriptionActionUseCase.Command c,State r){
        return tx.requiresNew(()->{
            var fresh=authorized(c.context(),r.subscription().id(),operations.lock(r.operation().id()),true);
            if(!recoveryPolicyCurrent(fresh))return false;
            var session=customer(c.context());var now=securityClock.instant();var grant=billingGrant(fresh,c.actionToken());
            // Reply persistence changes the operation version. The immutable consumed receipt remains the authority.
            return fresh.account().provider()==PaymentProviderType.STRIPE&&fresh.account().status()==PaymentAccountStatus.ACTIVE
                &&fresh.policy().enabled()&&fresh.subscription().nonTerminal()&&fresh.operation().status()==SubscriptionOperation.Status.PROCESSING
                &&grant.consumedAt()!=null&&grant.supersededAt()==null&&now.isBefore(grant.expiresAt())
                &&session.sessionId().value().equals(grant.sessionId())&&session.origin().equals(grant.origin())
                &&grant.clientExpiresAt()!=null&&now.isBefore(grant.clientExpiresAt())
                &&actions.replay(c.context().siteId(),principal(c.context()),digest(c.context().idempotencyKey()),confirmationFingerprint(c));
        });
    }
    public SubscriptionOperationResult reply(SubscriptionCommandContext c,State r,Reply reply){
        return tx.requiresNew(()->{
            var o=operations.lock(r.operation().id());var fresh=authorized(c,r.subscription().id(),o,true);
            if(fresh.account().provider()==PaymentProviderType.STRIPE)customer(c);
            if(reply.reference()!=null&&!reply.reference().equals(fresh.subscription().providerReference()))throw SubscriptionRuleErrors.conflict();
            if(o.status()==SubscriptionOperation.Status.SUCCEEDED||fresh.account().provider()==PaymentProviderType.STRIPE&&o.status()==SubscriptionOperation.Status.FAILED)return result(o,null);
            // Receipts never grant success or entitlement. Preserve existing Fake CREATE continuation.
            var status=switch(reply.outcome()){case ACCEPTED,DECLINED->SubscriptionOperation.Status.PROCESSING;case REQUIRES_ACTION->SubscriptionOperation.Status.REQUIRES_ACTION;case UNKNOWN->SubscriptionOperation.Status.UNKNOWN;};
            var saved=save(o,status);var action=reply.action()==null?null:issue(new State(fresh.subscription(),fresh.account(),fresh.policy(),saved,false),reply.action(),clock.instant());
            return result(saved,action);
        });
    }
    public SubscriptionOperationResult replyPreparedSetup(SubscriptionCommandContext c,State r,String token,OwnedSetupReceipt receipt){
        return tx.requiresNew(()->{
            var o=operations.lock(r.operation().id());var fresh=authorized(c,r.subscription().id(),o,true);customer(c);var grant=billingGrant(fresh,token);
            if(o.kind()!=SubscriptionOperation.Kind.CREATE||fresh.subscription().providerReference()!=null||grant.binding().flow()!=SubscriptionClientSession.Flow.SETUP||grant.binding().subscriptionReference()!=null||grant.consumedAt()==null
                ||receipt!=null&&(!receipt.operationId().equals(o.id())||!receipt.stepId().equals(grant.binding().stepId())||!receipt.setupReference().equals(grant.binding().setupReference())))throw SubscriptionRuleErrors.conflict();
            if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)return result(o,null);
            return result(save(o,SubscriptionOperation.Status.PROCESSING),null);
        });
    }
    public PortalNavigation portal(SubscriptionCommandContext c,State r,PortalNavigation navigation,String url){
        return tx.requiresNew(()->{
            var o=operations.lock(r.operation().id());var fresh=authorized(c,r.subscription().id(),o,true);
            SubscriptionPortalService.requireAllowedReturn(fresh.policy(),url);
            if(!navigation.simulated()||!navigation.expiresAt().isAfter(clock.instant()))throw SubscriptionRuleErrors.conflict();
            portals.save(c.siteId(),c.customerId(),r.subscription().id(),o.id(),navigation.expiresAt());
            if(o.status()!=SubscriptionOperation.Status.SUCCEEDED)save(o,SubscriptionOperation.Status.SUCCEEDED);
            return navigation;
        });
    }
    public Subscription portalReturn(SubscriptionCommandContext c,UUID id,UUID operation){
        return tx.required(()->{var fresh=authorized(c,id,null,false);if(!portals.valid(c.siteId(),c.customerId(),id,operation,clock.instant()))throw SubscriptionRuleErrors.conflict();return fresh.subscription();});
    }
    private SubscriptionOperation save(SubscriptionOperation o,SubscriptionOperation.Status status){return operations.save(new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),clock.instant(),o.version()+1),o.version());}
    public static SubscriptionOperationResult result(SubscriptionOperation o,NextAction action){return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),action);}
    public static Command command(State r){var o=r.operation();return new Command(r.account(),o,r.subscription().billingPrice(),null,r.subscription().providerReference(),null,null,true,o.kind()==SubscriptionOperation.Kind.CREATE&&CreateSubscriptionFingerprint.startsTrial(o.fingerprint()));}
    public static String request(SubscriptionCommandContext c,Object... fields){var b=new StringBuilder();var values=new ArrayList<Object>(Arrays.asList(principal(c),c.siteId(),c.customerId(),c.origin()));values.addAll(Arrays.asList(fields));for(var v:values){var s=String.valueOf(v);b.append(s.length()).append(':').append(s);}return digest(b.toString());}
    private static String confirmationFingerprint(ConfirmSubscriptionActionUseCase.Command c){return request(c.context(),"CONFIRM",c.subscriptionId(),c.operationId(),digest(c.actionToken()));}
    static String digest(String s){return CreateSubscriptionFingerprint.digest(s);}
    static String principal(SubscriptionCommandContext c){if(c==null||c.actor()==null||c.siteId()==null||c.customerId()==null)throw SubscriptionRuleErrors.conflict();return switch(c.actor()){case SubscriptionActor.Customer a->"customer:"+a.session().customerId().value();case SubscriptionActor.Partner a->"partner:"+a.site().keyId().value();case SubscriptionActor.Admin a->"admin:"+a.admin().id().value();};}
    static void validateKey(SubscriptionCommandContext c){principal(c);if(c.idempotencyKey()==null||c.idempotencyKey().isBlank()||c.idempotencyKey().length()>255||c.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw SubscriptionRuleErrors.conflict();}
    private static void validateToken(String token){if(token==null||token.isBlank()||token.length()>2048)throw SubscriptionRuleErrors.conflict();}
    private static <T>T translated(Supplier<T> work){try{return work.get();}catch(DataIntegrityViolationException e){throw SubscriptionRuleErrors.conflict();}}
}
