package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical;

/** Explicit per-reservation browser permission. No bean, route, provider mutation or completion authority. */
public final class OwnedMethodBrowserService implements GetSubscriptionActionUseCase,CreateSubscriptionClientSessionUseCase,
        ConfirmSubscriptionActionUseCase,SubscriptionConfirmationUseCase {
    private static final String DOMAIN="gp:owned-failed-method-browser:v1";
    private final Reservation expected;private final OwnedFailedRenewalMethodService methods;
    private final OwnedFailedRenewalMethodStore store;private final OwnedMethodBrowserReader reader;
    private final OwnedMethodBrowserTokens tokens;private final Clock clock;
    public OwnedMethodBrowserService(Reservation expected,OwnedFailedRenewalMethodService methods,OwnedFailedRenewalMethodStore store,
            OwnedMethodBrowserReader reader,OwnedMethodBrowserTokens tokens,Clock clock){
        this.expected=Objects.requireNonNull(expected);this.methods=Objects.requireNonNull(methods);this.store=Objects.requireNonNull(store);
        this.reader=Objects.requireNonNull(reader);this.tokens=Objects.requireNonNull(tokens);this.clock=Objects.requireNonNull(clock);
    }
    public NextAction get(GetSubscriptionActionUseCase.Query q){return safely(()->{
        if(q==null)throw unavailable();scope(q.context(),q.subscriptionId(),q.operationId());
        return methods.browserPhase(expected,q.context(),p->{
            var existing=store.browserRootInTransaction(expected.id());Root root;
            if(existing.isPresent())root=existing.get();else{
                var now=now();var expires=min(now.plusSeconds(600),p.enrollment().sessionExpiresAt());
                var draft=new Root(UUID.randomUUID(),expected.id(),p.operation().id(),p.setupStep().id(),p.setupStep().version(),p.setup().reference(),tokens.activeVersion(),"0".repeat(64),now,expires);
                var proposed=new Root(draft.id(),draft.reservationId(),draft.setupOperation(),draft.setupStep(),draft.stepVersion(),draft.setupReference(),draft.keyVersion(),tokens.hash(tokens.token(draft.keyVersion(),descriptor(p,draft))),draft.issuedAt(),draft.expiresAt());
                root=store.insertBrowserRootInTransaction(p,proposed);if(!proposed.equals(root))throw unavailable();
            }
            var token=verify(p,root,null);store.requireBrowserActionInTransaction(p,root,now());
            return new NextAction(ActionKind.PROVIDER_CLIENT,token,root.expiresAt());
        });
    });}
    public SubscriptionClientSession issue(CreateSubscriptionClientSessionUseCase.Command c){return safely(()->{
        if(c==null)throw unavailable();var release=release(c.context(),c.subscriptionId(),c.operationId(),c.actionToken());
        return new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.SETUP,release.ephemeral().publishableKey(),release.ephemeral().clientSecret(),c.actionToken(),release.root().expiresAt());
    });}
    public Session select(Scope s,String token){return safely(()->{
        if(s==null)throw unavailable();var release=release(s.context(),s.subscription(),s.operation(),token);
        return new Session("BROWSER_CONFIRM_V1","stripe",SubscriptionClientSession.Flow.SETUP,release.ephemeral().publishableKey(),release.ephemeral().clientSecret(),token,null,null,release.root().expiresAt());
    });}
    private Released release(SubscriptionCommandContext context,UUID subscription,UUID operation,String token){
        scope(context,subscription,operation);requireToken(token);var key=key(context);
        // The start receipt commits before the sole SDK retrieve. Any later failure burns this opportunity.
        var selected=methods.browserPhase(expected,context,p->{
            var root=store.browserRootInTransaction(expected.id()).orElseThrow(OwnedMethodBrowserService::unavailable);verify(p,root,token);
            var at=now();var selection=store.startBrowserSelectionInTransaction(p,root,key,at);
            if(!selection.rootId().equals(root.id())||!selection.keyDigest().equals(key)||!selection.startedAt().equals(at)
                ||!selection.deadlineAt().equals(min(at.plusSeconds(30),root.expiresAt()))||!now().isBefore(selection.deadlineAt()))throw unavailable();
            return new Selected(p,root,selection);
        });
        var ephemeral=reader.read(selected.pending(),selected.selection().deadlineAt());
        requireEphemeral(selected.root(),ephemeral);
        // Persistence runs only on this caller; returning from browserPhase means release committed.
        var released=methods.browserPhase(expected,context,p->{
            if(!selected.pending().equals(p))throw unavailable();
            var root=store.browserRootInTransaction(expected.id()).orElseThrow(OwnedMethodBrowserService::unavailable);
            if(!root.equals(selected.root()))throw unavailable();verify(p,root,token);
            var at=now();if(at.isBefore(selected.selection().startedAt())||!at.isBefore(selected.selection().deadlineAt()))throw unavailable();
            store.finishBrowserSelectionInTransaction(p,root,selected.selection(),at);
            return new Released(root,ephemeral);
        });
        // A lock/commit wait may consume the remaining window after the on-time release INSERT.
        var returnedAt=now();if(!returnedAt.isBefore(selected.selection().deadlineAt())||!returnedAt.isBefore(selected.root().expiresAt()))throw unavailable();
        return released;
    }
    public SubscriptionOperationResult confirm(ConfirmSubscriptionActionUseCase.Command c){return safely(()->{
        if(c==null)throw unavailable();scope(c.context(),c.subscriptionId(),c.operationId());requireToken(c.actionToken());var key=key(c.context());
        return methods.browserPhase(expected,c.context(),p->{
            var root=store.browserRootInTransaction(expected.id()).orElseThrow(OwnedMethodBrowserService::unavailable);verify(p,root,c.actionToken());
            var request=tokens.hash(canonical(DOMAIN,"confirm",p.enrollment().sessionId(),p.enrollment().origin(),p.enrollment().sessionExpiresAt(),p.account().id().value(),p.subscription().id(),p.operation().id(),tokens.hash(c.actionToken()),key));
            var first=store.consumeBrowserFirstInTransaction(p,root,key,request,now());var o=p.operation();
            return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),null,first);
        });
    });}
    private String verify(Pending p,Root r,String supplied){
        if(!expected.equals(p.reservation())||!expected.id().equals(r.reservationId())||!p.operation().id().equals(r.setupOperation())
            ||!p.setupStep().id().equals(r.setupStep())||p.setupStep().version()!=r.stepVersion()||!p.setup().reference().equals(r.setupReference())
            ||!r.expiresAt().equals(min(r.issuedAt().plusSeconds(600),p.enrollment().sessionExpiresAt())))throw unavailable();
        var now=now();if(now.isBefore(r.issuedAt())||!now.isBefore(r.expiresAt()))throw unavailable();
        var original=tokens.token(r.keyVersion(),descriptor(p,r));
        if(!tokens.matches(original,r.digest())||supplied!=null&&!tokens.matches(supplied,r.digest()))throw unavailable();
        if(supplied==null)return original;
        return supplied;
    }
    private String descriptor(Pending p,Root r){
        var a=p.account();var s=p.subscription();var o=p.operation();var b=p.binding();var step=p.setupStep();var f=expected.failure();var enrollment=p.enrollment();
        var intent=expected.admission().firstRenewalAdmission().receipt().intent();var owner=intent.clock().owner();
        return canonical(DOMAIN,r.id(),r.reservationId(),r.setupOperation(),r.setupStep(),r.stepVersion(),r.setupReference(),r.keyVersion(),r.issuedAt(),r.expiresAt(),
            enrollment.sessionId(),enrollment.origin(),enrollment.sessionExpiresAt(),expected.admission().id(),expected.admission().sourceSha(),intent.id(),owner.intent(),owner.run(),
            a.id().value(),a.provider(),a.mode(),a.status(),a.externalAccountId(),a.credentialsRef(),s.siteId().value(),s.customerId().value(),s.id(),s.providerReference(),s.revision(),
            o.kind(),o.status(),o.version(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.createdAt(),
            b.id(),b.accountId().value(),b.siteId().value(),b.customerId().value(),b.providerReference(),b.creationOperationId(),b.version(),
            step.kind(),step.status(),step.resourceId(),step.operationKey(),step.fingerprint(),step.externalAccountId(),step.credentialsRef(),step.firstDispatchedAt(),step.retryBefore(),step.providerReference(),
            p.setup().operation(),p.setup().subscription(),p.setup().account(),p.setup().step(),p.setup().reference(),p.setup().method(),
            f.claimId(),f.billId(),f.billVersion(),f.invoice(),f.allocation(),f.paymentIntent(),f.period().start(),f.period().end(),f.graceDeadline(),f.revision(),f.observationSequence(),f.recordedAt(),
            expected.requestFingerprint(),expected.initialOperationVersion(),expected.billingSample().mode(),expected.billingSample().billingAt(),expected.reservedAt());
    }
    private void scope(SubscriptionCommandContext c,UUID subscription,UUID operation){
        var intent=expected.admission().firstRenewalAdmission().receipt().intent();
        if(c==null||!(c.actor() instanceof SubscriptionActor.Customer)||!intent.subscription().equals(subscription)||!expected.setupOperationId().equals(operation)
            ||!expected.binding().siteId().equals(c.siteId())||!expected.binding().customerId().equals(c.customerId()))throw unavailable();
    }
    private String key(SubscriptionCommandContext c){var value=c.idempotencyKey();if(value==null||value.isBlank()||value.length()>255||value.codePoints().anyMatch(Character::isISOControl))throw unavailable();return tokens.hash(value);}
    private static void requireToken(String token){if(token==null||!token.matches("bga1_[A-Za-z0-9_-]{43}"))throw unavailable();}
    private static void requireEphemeral(Root root,OwnedMethodBrowserReader.EphemeralSetup e){
        if(e==null||!root.setupReference().equals(e.setupReference())||e.publishableKey()==null||!e.publishableKey().matches("pk_test_[A-Za-z0-9]+")
            ||e.clientSecret()==null||e.clientSecret().length()>512||!e.clientSecret().matches(java.util.regex.Pattern.quote(root.setupReference())+"_secret_[A-Za-z0-9_]+"))throw unavailable();
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static <T>T safely(Supplier<T> work){try{return work.get();}catch(RuntimeException failure){throw unavailable();}}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_METHOD_BROWSER_UNAVAILABLE",409,"Owned method browser unavailable");}
    private record Selected(Pending pending,Root root,Selection selection){@Override public String toString(){return "OwnedMethodBrowser.Selected[redacted]";}}
    private record Released(Root root,OwnedMethodBrowserReader.EphemeralSetup ephemeral){@Override public String toString(){return "OwnedMethodBrowser.Released[redacted]";}}
    public Session session(Scope s,UUID attempt){throw unavailable();}
    public Status status(Scope s,UUID attempt){throw unavailable();}
    public Status current(Scope s){throw unavailable();}
    public Confirm confirm(Scope s,UUID attempt,String capability,String confirmationToken){throw unavailable();}
    public Status retry(Scope s,UUID attempt){throw unavailable();}
    @Override public String toString(){return "OwnedMethodBrowserService[redacted]";}
}
