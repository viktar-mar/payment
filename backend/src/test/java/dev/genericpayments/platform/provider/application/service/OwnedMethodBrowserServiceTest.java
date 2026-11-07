package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedMethodBrowserServiceTest {
    static OwnedMethodBrowserTokens tokens(){return new StripeOwnedMethodBrowserTokens(new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))));}
    static OwnedMethodBrowserService service(OwnedMethodBrowserInertFixture f){return new OwnedMethodBrowserService(f.reservation,f.methods,f.store,(p,d)->{assertThat(f.active()).isFalse();assertThat(f.selection).isNotNull();assertThat(f.selectionTransaction).isLessThanOrEqualTo(f.transactionId);f.reads++;f.onRead.run();return new OwnedMethodBrowserReader.EphemeralSetup("seti_owned","pk_test_INERT","seti_owned_secret_INERT");},tokens(),f.clock);}
    static String action(OwnedMethodBrowserService s,OwnedMethodBrowserInertFixture f){return s.get(new GetSubscriptionActionUseCase.Query(f.command(null),f.pending().subscription().id(),f.reservation.setupOperationId())).secret();}
    static SubscriptionConfirmationUseCase.Scope scope(OwnedMethodBrowserInertFixture f,String key){return new SubscriptionConfirmationUseCase.Scope(f.command(key),f.pending().subscription().id(),f.reservation.setupOperationId());}
    static ConfirmSubscriptionActionUseCase.Command confirm(OwnedMethodBrowserInertFixture f,String key,String token){return new ConfirmSubscriptionActionUseCase.Command(f.command(key),f.pending().subscription().id(),f.reservation.setupOperationId(),token);}
    @Test void originalUnselectedActionIsReproducedWithoutRemoteReadAndExpiryNeverExtends(){
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);
        f.at(f.now().plusNanos(123456789));var token=action(s,f);var original=f.root;

        f.at(f.now().plusSeconds(4));var repeated=action(s,f);

        assertThat(repeated).isEqualTo(token);assertThat(f.root).isEqualTo(original);
        assertThat(original.issuedAt().getNano()%1000).isZero();assertThat(original.expiresAt()).isEqualTo(f.enrollment.sessionExpiresAt());
        assertThat(f.reads).isZero();f.noBusinessChanges();
    }
    @Test void selectionCommitsBeforeOneReaderInvocationAndReleaseBeforeReservedFirstPermission(){
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);

        var selected=s.select(scope(f,"select-key"),token);

        assertThat(selected.protocol()).isEqualTo("BROWSER_CONFIRM_V1");assertThat(selected.provider()).isEqualTo("stripe");
        assertThat(selected.flow()).isEqualTo(SubscriptionClientSession.Flow.SETUP);assertThat(selected.attemptId()).isNull();assertThat(selected.capability()).isNull();
        assertThat(selected.expiresAt()).isEqualTo(f.root.expiresAt());assertThat(f.released).isTrue();assertThat(f.reads).isEqualTo(1);

        f.at(f.now().plusSeconds(31));var first=s.confirm(confirm(f,"confirm-key",token));

        assertThat(first.operationId()).isEqualTo(f.reservation.setupOperationId());
        assertThat(first.status()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.RESERVED);
        assertThat(first.nextAction()).isNull();assertThat(first.browserConfirmAllowed()).isTrue();
        assertThat(s.confirm(confirm(f,"confirm-key",token)).browserConfirmAllowed()).isFalse();assertThat(f.consumeTransaction).isGreaterThan(f.releaseTransaction);
        refused(()->s.confirm(confirm(f,"changed-key",token)));refused(()->action(s,f));assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
    }
    @Test void legacyIssueAndSelectionShareOneOpportunity(){
        for(boolean legacy:List.of(true,false)){
            var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);

            if(legacy){
                var dto=s.issue(new CreateSubscriptionClientSessionUseCase.Command(f.command("issue"),f.pending().subscription().id(),f.reservation.setupOperationId(),token));
                assertThat(dto.flow()).isEqualTo(SubscriptionClientSession.Flow.SETUP);assertThat(dto.clientSecret()).isEqualTo("seti_owned_secret_INERT");
            }else s.select(scope(f,"select"),token);

            refused(()->s.issue(new CreateSubscriptionClientSessionUseCase.Command(f.command("issue-again"),f.pending().subscription().id(),f.reservation.setupOperationId(),token)));
            refused(()->s.select(scope(f,"select-again"),token));assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
        }
    }
    @Test void failedReadLateReadContextDriftAndLostReleaseBurnSelection(){
        for(var bad:List.of("read","late","authority","release","commit")){
            var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);
            f.onRead=()->{switch(bad){
                case "read"->throw new IllegalStateException("secret-provider-payload");
                case "late"->f.at(f.selection.deadlineAt());case "authority"->f.authorized=false;
                case "release"->f.failRelease=true;case "commit"->f.failCommit=true;
            }};

            refused(()->s.select(scope(f,"select"),token));

            f.authorized=true;f.failRelease=false;assertThat(f.selection).isNotNull();assertThat(f.released).isFalse();
            refused(()->s.select(scope(f,"second"),token));refused(()->s.confirm(confirm(f,"confirm",token)));
            assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
        }
    }
    @Test void wrongSessionOriginLocatorTokenKeyExpiryAndChangedRootRefuseSafely(){
        for(var bad:List.of("session","origin","operation","subscription","token","key","expired","root")){
            var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);var original=scope(f,"select");var c=original.context();
            if(bad.equals("session")){
                var old=((SubscriptionActor.Customer)c.actor()).session();
                var other=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(new dev.genericpayments.platform.customers.domain.CustomerSessionId(UUID.randomUUID()),old.siteId(),old.customerId(),old.origin(),old.scopes(),old.expiresAt());
                c=new SubscriptionCommandContext(new SubscriptionActor.Customer(other),c.siteId(),c.customerId(),c.origin(),"select","inert");
            }
            if(bad.equals("origin"))c=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),"https://foreign.example","select","inert");
            if(bad.equals("key"))c=f.command(null);if(bad.equals("expired"))f.at(f.root.expiresAt());
            if(bad.equals("root")){
                var r=f.root;f.root=new Root(r.id(),r.reservationId(),r.setupOperation(),r.setupStep(),r.stepVersion(),r.setupReference(),r.keyVersion(),"a".repeat(64),r.issuedAt(),r.expiresAt());
            }
            var scope=new SubscriptionConfirmationUseCase.Scope(c,bad.equals("subscription")?UUID.randomUUID():original.subscription(),bad.equals("operation")?UUID.randomUUID():original.operation());
            var supplied=bad.equals("token")?"bga1_"+"A".repeat(43):token;

            refused(()->s.select(scope,supplied));

            assertThat(f.reads).isZero();f.noBusinessChanges();
        }
    }
    @Test void consumptionCommitLossRollsBackAndOnlyOnePermissionCanCommit(){
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);
        s.select(scope(f,"select"),token);f.failCommit=true;

        refused(()->s.confirm(confirm(f,"confirm",token)));

        assertThat(f.consumed).isFalse();assertThat(s.confirm(confirm(f,"confirm",token)).browserConfirmAllowed()).isTrue();
        assertThat(s.confirm(confirm(f,"confirm",token)).browserConfirmAllowed()).isFalse();f.noBusinessChanges();
    }
    @Test void allAttemptEndpointsRefuseWithoutReads(){
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);var scope=scope(f,"key");

        refused(()->s.session(scope,UUID.randomUUID()));refused(()->s.status(scope,UUID.randomUUID()));refused(()->s.current(scope));
        refused(()->s.confirm(scope,UUID.randomUUID(),"capability","confirmation"));refused(()->s.retry(scope,UUID.randomUUID()));

        assertThat(f.reads).isZero();assertThat(f.root).isNull();f.noBusinessChanges();
    }
    @Test void expiryAfterBlockingRootReadCannotUseEarlierTimestamp(){
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);f.onRoot=()->f.at(f.root.expiresAt());

        refused(()->s.select(scope(f,"select"),token));

        assertThat(f.selection).isNull();assertThat(f.reads).isZero();f.noBusinessChanges();
    }
    @Test void releaseCommitThatReturnsAfterDeadlineCannotReturnTheSecret(){
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);
        f.onRead=()->f.afterCommit=()->f.at(f.selection.deadlineAt());

        refused(()->s.select(scope(f,"select"),token));

        assertThat(f.released).isTrue();assertThat(f.reads).isEqualTo(1);refused(()->s.select(scope(f,"second"),token));
        assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
    }
    @Test void fullPendingAndOriginalDescriptorMustSurviveTheRead(){
        for(boolean duringRead:List.of(false,true)){
            var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);
            Runnable change=()->f.pendingMutation=p->{
                var step=p.setupStep();
                var changed=new dev.genericpayments.platform.provider.application.model.ProviderOperation(step.id(),step.accountId(),step.externalAccountId(),step.credentialsRef(),step.kind(),step.resourceId(),step.operationKey(),"changed-original-fingerprint",step.status(),step.providerReference(),step.firstDispatchedAt(),step.retryBefore(),step.leaseOwner(),step.leaseExpiresAt(),step.version());
                return new Pending(p.reservation(),p.enrollment(),p.account(),p.subscription(),p.operation(),p.binding(),p.setup(),changed);
            };
            if(duringRead)f.onRead=change;else change.run();

            refused(()->s.select(scope(f,"select"),token));

            assertThat(f.released).isFalse();assertThat(f.reads).isEqualTo(duringRead?1:0);f.noBusinessChanges();
        }
    }
    @Test void nullMalformedTokenAndMissingReleaseNeverAuthorizeConsumption(){var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);for(var value:Arrays.asList(null,"","raw-secret",token)){refused(()->s.confirm(confirm(f,"confirm",value)));if(!Objects.equals(value,token))refused(()->s.select(scope(f,"select"),value));}assertThat(f.consumed).isFalse();assertThat(f.selection).isNull();assertThat(f.reads).isZero();f.noBusinessChanges();}
    @Test void concurrentLegacyAndSelectionRequestsCannotStartASecondReaderInvocation()throws Exception{
        var f=new OwnedMethodBrowserInertFixture();var s=service(f);var token=action(s,f);
        var entered=new java.util.concurrent.CountDownLatch(1);var complete=new java.util.concurrent.CountDownLatch(1);
        f.onRead=()->{entered.countDown();try{if(!complete.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("inert timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("inert cancellation");}};
        var first=new java.util.concurrent.FutureTask<SubscriptionConfirmationUseCase.Session>(() -> s.select(scope(f,"select"),token));Thread.ofVirtual().start(first);
        try{assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();refused(()->s.issue(new CreateSubscriptionClientSessionUseCase.Command(f.command("other"),f.pending().subscription().id(),f.reservation.setupOperationId(),token)));}
        finally{complete.countDown();}
        assertThat(first.get(2,java.util.concurrent.TimeUnit.SECONDS).flow()).isEqualTo(SubscriptionClientSession.Flow.SETUP);assertThat(f.reads).isEqualTo(1);assertThat(f.released).isTrue();f.noBusinessChanges();
    }
    @Test void injectedReaderCannotReleaseForeignSecretOrLivePublishableKey(){
        for(var ephemeral:List.of(new OwnedMethodBrowserReader.EphemeralSetup("seti_foreign","pk_test_INERT","seti_foreign_secret_INERT"),new OwnedMethodBrowserReader.EphemeralSetup("seti_owned","pk_live_INERT","seti_owned_secret_INERT"),new OwnedMethodBrowserReader.EphemeralSetup("seti_owned","pk_test_INERT","seti_foreign_secret_INERT"))){
            var f=new OwnedMethodBrowserInertFixture();var s=new OwnedMethodBrowserService(f.reservation,f.methods,f.store,(p,d)->ephemeral,tokens(),f.clock);var token=action(s,f);
            refused(()->s.select(scope(f,"select"),token));assertThat(f.selection).isNotNull();assertThat(f.released).isFalse();f.noBusinessChanges();
        }
    }
    @Test void savedKeyVersionSurvivesRotationAndUnknownOriginalVersionRefuses(){
        var f=new OwnedMethodBrowserInertFixture();var original=service(f);var token=action(original,f);
        var next=Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var retained=new StripeOwnedMethodBrowserTokens(new StripeBillingGrantTokens(true,"v2",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]),"v2",next)));
        var rotated=new OwnedMethodBrowserService(f.reservation,f.methods,f.store,(p,d)->{throw new AssertionError("GET forbidden");},retained,f.clock);assertThat(action(rotated,f)).isEqualTo(token);
        var removed=new OwnedMethodBrowserService(f.reservation,f.methods,f.store,(p,d)->{throw new AssertionError("GET forbidden");},new StripeOwnedMethodBrowserTokens(new StripeBillingGrantTokens(true,"v2",Map.of("v2",next))),f.clock);refused(()->action(removed,f));assertThat(f.reads).isZero();f.noBusinessChanges();
    }
    static void refused(Runnable work){assertThatThrownBy(work::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method browser unavailable").hasNoCause();}
}
