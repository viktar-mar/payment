package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
/** Three phases: locked durable intent, unlocked provider I/O, locked authority/version fence. No automatic replay. */
@Service
public class SubscriptionConfirmationService implements SubscriptionConfirmationUseCase {
 private final SubscriptionInteractionTransaction phases;private final SubscriptionActionStore roots;private final SubscriptionConfirmationStore store;
 private final ObjectProvider<SubscriptionConfirmationProvider> providers;private final CreateSubscriptionClientSessionUseCase legacy;private final Clock clock;
 private final dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt ownedInitial;
 @org.springframework.beans.factory.annotation.Autowired
 public SubscriptionConfirmationService(SubscriptionInteractionTransaction phases,SubscriptionActionStore roots,SubscriptionConfirmationStore store,ObjectProvider<SubscriptionConfirmationProvider> providers,CreateSubscriptionClientSessionUseCase legacy,@Qualifier("providerReferenceClock") Clock clock){this.ownedInitial=null;this.phases=phases;this.roots=roots;this.store=store;this.providers=providers;this.legacy=legacy;this.clock=clock;}
 public SubscriptionConfirmationService(SubscriptionInteractionTransaction phases,SubscriptionActionStore roots,SubscriptionConfirmationStore store,ObjectProvider<SubscriptionConfirmationProvider> providers,CreateSubscriptionClientSessionUseCase legacy,Clock clock,dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt expectedOwnedInitial){this.ownedInitial=Objects.requireNonNull(expectedOwnedInitial);this.phases=phases;this.roots=roots;this.store=store;this.providers=providers;this.legacy=legacy;this.clock=clock;}
 private record Snapshot(SubscriptionInteractionTransaction.State state,SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt) {
  Lookup lookup(){return new Lookup(state.account(),state.subscription().id(),state.subscription().providerReference(),state.operation().providerOperationKey());}
 }
 public Session select(Scope s,String token){
  key(s);var selected=locked(s,state->{
   var root=phases.confirmationRoot(state,token);requireLane(root);
   if(root.binding().flow()==SubscriptionClientSession.Flow.SETUP)return new Snapshot(state,root,null,null);
   var chain=store.chain(root.id()).orElse(null);
   if(chain==null){
    if(!root.available(now())||root.sessionId()!=null)throw conflict();var actor=customer(s);var expiry=root.expiresAt().isBefore(actor.expiresAt())?root.expiresAt():actor.expiresAt();
    chain=new SubscriptionConfirmationChain(root.id(),root.tokenDigest(),actor.sessionId().value(),actor.origin(),expiry,state.policy().version(),state.subscription().revision());
    var attempt=SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),chain.id(),null,root.keyVersion(),now(),expiry);store.reserve(root,chain,attempt);
    return checked(s,state,root,chain,attempt);
   }
   return checked(s,state,root,chain,store.latest(chain.id()));
  });
  if(selected.chain()==null){var r=legacy.issue(new CreateSubscriptionClientSessionUseCase.Command(s.context(),s.subscription(),s.operation(),token));return new Session("BROWSER_CONFIRM_V1",r.provider(),r.flow(),r.publishableKey(),r.clientSecret(),r.actionToken(),null,null,r.expiresAt());}
  return issue(s,selected);
 }
 public Session session(Scope s,UUID id){key(s);return issue(s,load(s,id));}
 private Session issue(Scope s,Snapshot selected){
  if(selected.attempt().state()!=SubscriptionConfirmationAttempt.State.READY)throw conflict();
  var provider=provider();var receipt=selected.attempt().predecessor()==null?null:locked(s,state->store.attempt(selected.attempt().predecessor()).receipt());
  var material=provider.read(selected.lookup(),selected.root(),receipt);
  return locked(s,state->{var fresh=load(s,state,selected.attempt().id());same(selected,fresh);if(fresh.attempt().state()!=SubscriptionConfirmationAttempt.State.READY)throw conflict();
   return new Session("SERVER_CONFIRM_V2","stripe",SubscriptionClientSession.Flow.PAYMENT,material.publishableKey(),material.clientSecret(),null,fresh.attempt().id(),provider.capability(fresh.root(),fresh.chain(),fresh.attempt()),fresh.chain().expiresAt());});
 }
 public Status status(Scope s,UUID id){return view(load(s,id).attempt());}
 public Status current(Scope s){return locked(s,state->{var chain=store.current(s.operation()).orElse(null);if(chain==null)return null;var root=roots.billing(chain.rootDigest()).orElseThrow(SubscriptionConfirmationService::conflict);return view(checked(s,state,root,chain,store.latest(chain.id())).attempt());});}
 public Confirm confirm(Scope s,UUID id,String capability,String token){
  key(s);if(capability==null||!capability.matches("bca2_[A-Za-z0-9_-]{43}")||token==null||!token.matches("ctoken_[A-Za-z0-9]{1,200}"))throw conflict();
  var initial=load(s,id);String key=hash(s.context().idempotencyKey()),tokenDigest=hash(token),fingerprint=hash(SubscriptionClientGrant.canonical("SERVER_CONFIRM_V2",id,initial.root().descriptor(),tokenDigest,"use_stripe_sdk=true"));
  if(initial.attempt().state()!=SubscriptionConfirmationAttempt.State.READY)return replay(initial,key,fingerprint);
  var provider=provider();if(!constant(capability,provider.capability(initial.root(),initial.chain(),initial.attempt())))throw conflict();
  var predecessorReceipt=initial.attempt().predecessor()==null?null:locked(s,state->store.attempt(initial.attempt().predecessor()).receipt());
  provider.read(initial.lookup(),initial.root(),predecessorReceipt);
  var claim=locked(s,state->{var fresh=load(s,state,id);if(fresh.attempt().state()!=SubscriptionConfirmationAttempt.State.READY){replay(fresh,key,fingerprint);return new Claim(fresh,false);}
   same(initial,fresh);var consumed=fresh.attempt().claim(key,tokenDigest,fingerprint);store.save(consumed,fresh.attempt().version(),now());return new Claim(new Snapshot(state,fresh.root(),fresh.chain(),consumed),true);});
  if(!claim.owner())return new Confirm(view(claim.snapshot().attempt()),false);
  SubscriptionConfirmationProvider.Outcome outcome;
  try{outcome=provider.confirm(claim.snapshot().lookup(),claim.snapshot().root(),claim.snapshot().attempt(),token);}catch(RuntimeException uncertain){outcome=new SubscriptionConfirmationProvider.Outcome(SubscriptionConfirmationAttempt.State.UNKNOWN,null);}
  var result=ownedInitial!=null&&(outcome==null||outcome.state()==null||!Set.of(SubscriptionConfirmationAttempt.State.OBSERVE,SubscriptionConfirmationAttempt.State.REQUIRES_ACTION,SubscriptionConfirmationAttempt.State.UNKNOWN).contains(outcome.state())||outcome.receipt()!=null)?new SubscriptionConfirmationProvider.Outcome(SubscriptionConfirmationAttempt.State.UNKNOWN,null):outcome;
  return locked(s,state->{var fresh=load(s,state,id);same(claim.snapshot(),fresh);var finished=fresh.attempt().finish(result.state(),result.receipt());store.save(finished,fresh.attempt().version(),now());return new Confirm(view(finished),finished.state()==SubscriptionConfirmationAttempt.State.REQUIRES_ACTION);});
 }
 private record Claim(Snapshot snapshot,boolean owner) {}
 private Confirm replay(Snapshot snapshot,String key,String fingerprint){var a=snapshot.attempt();if(!key.equals(a.confirmKey())||!fingerprint.equals(a.fingerprint()))throw conflict();return new Confirm(view(a),false);}
 public Status retry(Scope s,UUID id){
  if(ownedInitial!=null)throw conflict();
  key(s);var before=load(s,id);String key=hash(s.context().idempotencyKey());
  if(before.attempt().successor()!=null){if(!key.equals(before.attempt().retryKey()))throw conflict();return view(before.attempt());}
  if(!before.attempt().retryAllowed(now()))throw conflict();provider().read(before.lookup(),before.root(),before.attempt().receipt());
  return locked(s,state->{var fresh=load(s,state,id);if(fresh.attempt().successor()!=null){if(!key.equals(fresh.attempt().retryKey()))throw conflict();return view(fresh.attempt());}
   same(before,fresh);if(!fresh.attempt().retryAllowed(now()))throw conflict();var next=SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),fresh.chain().id(),id,fresh.root().keyVersion(),now(),fresh.chain().expiresAt());
   store.insert(next);var retired=fresh.attempt().successor(next.id(),key);store.save(retired,fresh.attempt().version(),now());return view(retired);});
 }
 private Snapshot load(Scope s,UUID id){return locked(s,state->load(s,state,id));}
 private Snapshot load(Scope s,SubscriptionInteractionTransaction.State state,UUID id){var a=store.attempt(id);var chain=store.chain(a.chain()).orElseThrow(SubscriptionConfirmationService::conflict);var root=roots.billing(chain.rootDigest()).orElseThrow(SubscriptionConfirmationService::conflict);return checked(s,state,root,chain,a);}
 private Snapshot checked(Scope s,SubscriptionInteractionTransaction.State state,SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt a){
  requireLane(root);if(ownedInitial!=null&&(a.predecessor()!=null||a.successor()!=null||a.receipt()!=null))throw conflict();
  var actor=customer(s);if(!now().isBefore(actor.expiresAt())||!root.operation().equals(s.operation())||!root.subscription().equals(s.subscription())||!root.site().equals(s.context().siteId())||!root.customer().equals(s.context().customerId())||!root.account().equals(state.account().id())
   ||!root.id().equals(chain.id())||!a.chain().equals(chain.id())||!a.expiresAt().equals(chain.expiresAt())||root.supersededAt()!=null||root.binding().flow()!=SubscriptionClientSession.Flow.PAYMENT
   ||!chain.sessionId().equals(actor.sessionId().value())||!chain.origin().equals(actor.origin())||!now().isBefore(chain.expiresAt())||chain.policyVersion()!=state.policy().version()||chain.subscriptionRevision()!=state.subscription().revision())throw conflict();
  return new Snapshot(state,root,chain,a);
 }
 private static void same(Snapshot a,Snapshot b){if(!a.state().account().equals(b.state().account())||a.state().operation().version()!=b.state().operation().version()||a.state().subscription().revision()!=b.state().subscription().revision()||a.attempt().version()!=b.attempt().version()||!a.root().binding().equals(b.root().binding()))throw conflict();}
 private <T>T locked(Scope s,Function<SubscriptionInteractionTransaction.State,T> work){return ownedInitial==null?phases.confirmationLocked(s.context(),s.subscription(),s.operation(),work):phases.confirmationLocked(s.context(),s.subscription(),s.operation(),ownedInitial,work);}
 private void requireLane(SubscriptionClientGrant root){
  var b=root.binding();if(ownedInitial==null){if(b.ownedIntent()!=null||"owned-initial-v1".equals(b.schema()))throw conflict();return;}
  var i=ownedInitial.intent();var o=i.clock().owner();
  if(!"owned-initial-v1".equals(b.schema())||!i.id().equals(b.ownedIntent())||!root.account().equals(o.account())||!root.site().equals(o.site())||!root.customer().equals(o.customer())
    ||!root.subscription().equals(i.subscription())||!root.operation().equals(i.createOperation())||!b.stepId().equals(ownedInitial.providerOperation())||!b.operationKey().equals(i.operationKey())
    ||!dev.genericpayments.platform.provider.application.model.OwnedInitialConfirmationProof.hash(ownedInitial,b.invoiceReference(),b.allocationReference(),b.intentReference()).equals(b.proof())
    ||!ownedInitial.reference().equals(b.subscriptionReference())||!i.customerReference().equals(b.customerReference()))throw conflict();
 }
 private Status view(SubscriptionConfirmationAttempt a){return new Status(a.id(),a.state(),ownedInitial==null&&a.retryAllowed(now()),a.successor(),a.expiresAt());}
 private SubscriptionConfirmationProvider provider(){var p=providers.getIfAvailable();if(p==null)throw conflict();return p;}
 private static dev.genericpayments.platform.customers.application.model.CustomerSessionContext customer(Scope s){if(!(s.context().actor() instanceof SubscriptionActor.Customer actor))throw conflict();return actor.session();}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
 private static void key(Scope s){SubscriptionInteractionTransaction.validateKey(s.context());}
 private static String hash(String raw){return CreateSubscriptionFingerprint.digest(raw);}
 private static boolean constant(String a,String b){return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
 private static RuntimeException conflict(){return SubscriptionRuleErrors.conflict();}
}
