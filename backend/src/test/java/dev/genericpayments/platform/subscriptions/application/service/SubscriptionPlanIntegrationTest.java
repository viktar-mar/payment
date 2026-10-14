package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
class SubscriptionPlanIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired PreviewSubscriptionChangeUseCase preview;
    @Autowired ChangeSubscriptionPlanUseCase changing;
    @Autowired ClearSubscriptionChangeUseCase clearing;
    PriceId target(UUID id,long amount,BillingInterval interval,PlanTransition.Direction direction){
        var s=sub(id);UUID p=UUID.randomUUID();
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',?,'EUR',?,0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",p,site.value(),s.productId().value(),"p-"+p,amount,interval.name(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        var price=new PriceId(p);policies.saveTransition(new PlanTransition(site,s.productId(),s.billingPrice().priceId(),price,direction,true,policies.load(site).version()));return price;
    }
    SubscriptionQuote quote(UUID id,PriceId target,ChangeTiming timing){var q=preview.preview(new PreviewSubscriptionChangeUseCase.Query(context(null),id,target,timing));assertThat(q).as("Persisted preview is returned without a financial key").isNotNull();return q;}
    ChangeSubscriptionPlanUseCase.Command change(UUID id,SubscriptionQuote q,String key){return new ChangeSubscriptionPlanUseCase.Command(context(key),id,q.id());}
    SubscriptionPlanService afterReread(java.util.function.UnaryOperator<ProviderQuote> hook){
        SubscriptionProviderRegistry registry=a->{var actual=restartedProvider();return (SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(proxy,method,args)->{
            assertThat(tx.active()).as("All provider calls are outside platform locks").isFalse();
            Object value;try{value=method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            if(method.getName().equals("preview")&&((QuoteRequest)args[0]).command().providerQuoteReference()!=null)return hook.apply((ProviderQuote)value);
            if(method.getName().equals("change")){var command=(Command)args[0];assertThat(op(command.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(tx.required(()->bean(SubscriptionQuoteStore.class).forOperation(site,command.operation().subscriptionId(),command.operation().id()))).isPresent();}
            return value;
        });};return new SubscriptionPlanService(bean(SubscriptionPlanTransaction.class),registry);
    }
    @Test void paidSameIntervalUpgradeAppliesOnlyAfterFactsAndReplaysSameKey(){
        var created=paid();var id=created.subscriptionId();var before=sub(id);var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);
        var q=quote(id,target,ChangeTiming.IMMEDIATE);assertThat(q.dueNowMinor()).isEqualTo(2000);assertThat(sub(id)).isEqualTo(before);
        var request=change(id,q,"upgrade");var result=changing.change(request);assertThat(result).isNotNull();
        assertThat(sub(id).entitledPrice()).isEqualTo(before.entitledPrice());drain();
        assertThat(sub(id).billingPrice().priceId()).isEqualTo(target);assertThat(sub(id).entitledPrice().priceId()).isEqualTo(target);
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(changing.change(request).operationId()).isEqualTo(result.operationId());assertThat(count("payments")).isEqualTo(2);
    }
    @Test void zeroAndUnknownAndDeclinedChangesNeverInventPaidAccess(){
        var created=paid();var id=created.subscriptionId();var original=sub(id).entitledPrice();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNKNOWN);
        var q=quote(id,target,ChangeTiming.IMMEDIATE);var result=changing.change(change(id,q,"unknown"));assertThat(result).isNotNull();drain();
        assertThat(sub(id).entitledPrice()).isEqualTo(original);assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThatThrownBy(()->changing.change(change(id,q,"bypass"))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->clearing.clear(new ClearSubscriptionChangeUseCase.Command(context("clear"),id))).isInstanceOf(PaymentApplicationException.class);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.DECLINE);advance(INITIAL_TIME.plusSeconds(600));
        assertThat(sub(id).entitledPrice()).isEqualTo(original);assertThat(sub(id).pendingChange()).isNull();assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.ZERO_PRORATION);
        var zero=quote(id,target,ChangeTiming.IMMEDIATE);assertThat(zero.dueNowMinor()).isZero();changing.change(change(id,zero,"zero"));drain();
        assertThat(sub(id).entitledPrice().priceId()).isEqualTo(target);assertThat(count("payments")).isEqualTo(2); // initial plus failed positive attempt; zero creates none
    }
    @Test void scheduledDowngradeKeepsOldGraceAndExactTargetAndCanBeCleared(){
        var created=paid();var id=created.subscriptionId();var original=sub(id).entitledPrice();var target=target(id,1000,BillingInterval.MONTH,PlanTransition.Direction.DOWNGRADE);
        var q=quote(id,target,ChangeTiming.NEXT_PERIOD);var result=changing.change(change(id,q,"schedule"));assertThat(result).isNotNull();drain();
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(sub(id).pendingChange().state()).isEqualTo(Subscription.ChangeState.SCHEDULED);
        var clear=clearing.clear(new ClearSubscriptionChangeUseCase.Command(context("clear"),id));assertThat(sub(id).pendingChange()).isNotNull();drain();
        assertThat(sub(id).pendingChange()).isNull();assertThat(op(clear.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        changing.change(change(id,quote(id,target,ChangeTiming.NEXT_PERIOD),"reschedule"));drain();
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.DECLINE);advance(q.effectiveAt());
        assertThat(sub(id).billingPrice().priceId()).isEqualTo(target);assertThat(sub(id).entitledPrice()).isEqualTo(original);
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(id),q.effectiveAt().plusSeconds(3*86400)).allowed()).isFalse();
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);recovery.recover(recoverCommand(id,"recover"));drain();
        assertThat(sub(id).entitledPrice().priceId()).isEqualTo(target);
    }
    @Test void changedPolicyArchivedTargetAndExpiredQuoteCannotMutate(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);
        var policy=policies.load(site);policies.save(new SubscriptionPolicy(site,policy.version(),true,4,false,false,false,Set.of()),policy.version());
        assertThatThrownBy(()->changing.change(change(id,q,"policy"))).isInstanceOf(PaymentApplicationException.class);
        var fresh=quote(id,target,ChangeTiming.IMMEDIATE);TestClock.set(INITIAL_TIME.plusSeconds(300));freshActor();
        assertThatThrownBy(()->changing.change(change(id,fresh,"expired"))).isInstanceOf(PaymentApplicationException.class);
        var again=quote(id,target,ChangeTiming.IMMEDIATE);jdbc.update("update prices set status='ARCHIVED' where id=?",target.value());
        assertThatThrownBy(()->changing.change(change(id,again,"archived"))).isInstanceOf(PaymentApplicationException.class);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void actionRequiredKeepsOldTermsUntilExactActionCompletes(){
        var id=paid().subscriptionId();var old=sub(id).billingPrice();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.REQUIRES_ACTION);
        var q=quote(id,target,ChangeTiming.IMMEDIATE);var r=changing.change(change(id,q,"action"));drain();
        assertThat(r.status()).isEqualTo(SubscriptionOperation.Status.REQUIRES_ACTION);assertThat(r.nextAction()).isNotNull();
        assertThat(sub(id).billingPrice()).isEqualTo(old);assertThat(sub(id).entitledPrice()).isEqualTo(old);
        assertThat(sub(id).pendingChange().state()).isEqualTo(Subscription.ChangeState.PAYMENT_PENDING);
        assertThatThrownBy(()->quote(id,target,ChangeTiming.IMMEDIATE)).isInstanceOf(PaymentApplicationException.class);
        controls.completeAction(account.id(),id);drain();assertThat(sub(id).entitledPrice().priceId()).isEqualTo(target);assertThat(op(r.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
    }
    @Test void unsupportedSettlementCannotApplyTargetOrCompleteChange(){
        var id=paid().subscriptionId();var old=sub(id).entitledPrice();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNSUPPORTED_SETTLEMENT);
        var r=changing.change(change(id,quote(id,target,ChangeTiming.IMMEDIATE),"unsupported"));drain();
        assertThat(sub(id).billingPrice()).isEqualTo(old);assertThat(sub(id).entitledPrice()).isEqualTo(old);assertThat(sub(id).pendingChange()).isNotNull();
        assertThat(op(r.operationId()).status()).isNotEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void negativeUpgradeAndIntervalChangeReturnDeferredAlternativesWithoutMutation(){
        var id=paid().subscriptionId();var old=sub(id);var negative=target(id,1000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);
        var q=quote(id,negative,ChangeTiming.IMMEDIATE);assertThat(q.timing()).isEqualTo(ChangeTiming.NEXT_PERIOD);assertThat(q.dueNowMinor()).isZero();
        var yearly=target(id,40000,BillingInterval.YEAR,PlanTransition.Direction.UPGRADE);assertThat(quote(id,yearly,ChangeTiming.IMMEDIATE).timing()).isEqualTo(ChangeTiming.NEXT_PERIOD);
        assertThat(sub(id)).isEqualTo(old);assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void everyChangedEconomicConditionRejectsBeforeProviderMutation(){
        var id=paid().subscriptionId();var old=sub(id).entitledPrice();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);
        java.util.List<java.util.function.UnaryOperator<ProviderQuote>> changes=List.of(
            q->new ProviderQuote(q.reference(),q.dueNowMinor()+1,q.nextAmountMinor(),q.currency(),q.effectiveAt(),q.expiresAt(),q.taxFinal()),
            q->new ProviderQuote(q.reference(),q.dueNowMinor(),q.nextAmountMinor()+1,q.currency(),q.effectiveAt(),q.expiresAt(),q.taxFinal()),
            q->new ProviderQuote(q.reference(),q.dueNowMinor(),q.nextAmountMinor(),"USD",q.effectiveAt(),q.expiresAt(),q.taxFinal()),
            q->new ProviderQuote(q.reference(),q.dueNowMinor(),q.nextAmountMinor(),q.currency(),q.effectiveAt().plusSeconds(1),q.expiresAt(),q.taxFinal()),
            q->new ProviderQuote(q.reference(),q.dueNowMinor(),q.nextAmountMinor(),q.currency(),q.effectiveAt(),q.expiresAt().plusSeconds(1),q.taxFinal()),
            q->new ProviderQuote(q.reference(),q.dueNowMinor(),q.nextAmountMinor(),q.currency(),q.effectiveAt(),q.expiresAt(),!q.taxFinal()),
            q->new ProviderQuote(q.reference()+"-rotated",q.dueNowMinor(),q.nextAmountMinor(),q.currency(),q.effectiveAt(),q.expiresAt(),q.taxFinal()));
        int index=0;for(var hook:changes){var q=quote(id,target,ChangeTiming.IMMEDIATE);var command=change(id,q,"reprice-"+index++);
            assertThatThrownBy(()->afterReread(hook).change(command)).isInstanceOf(PaymentApplicationException.class);assertThat(sub(id).pendingChange()).isNull();}
        assertThat(sub(id).entitledPrice()).isEqualTo(old);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void finalLockedPolicyAndRevisionChecksCloseProviderReadGap(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);
        assertThatThrownBy(()->afterReread(read->{var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,4,false,false,false,Set.of()),p.version());return read;}).change(change(id,q,"raced-policy"))).isInstanceOf(PaymentApplicationException.class);
        var fresh=quote(id,target,ChangeTiming.IMMEDIATE);
        assertThatThrownBy(()->afterReread(read->{tx.required(()->{var s=subscriptions.lock(site,id);subscriptions.save(SubscriptionPlanTransaction.withChange(s,s.pendingChange(),INITIAL_TIME),s.revision());return null;});return read;}).change(change(id,fresh,"raced-revision"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(sub(id).pendingChange()).isNull();assertThat(count("payments")).isEqualTo(1);
    }
    @Test void newerPublishedVersionNeverSubstitutesArchivedQuotedTarget(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);
        jdbc.update("update prices set status='ARCHIVED' where id=?",target.value());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,price_key,2,price_type,9000,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,'PUBLISHED',published_at,created_at,updated_at from prices where id=?",UUID.randomUUID(),target.value());
        assertThatThrownBy(()->changing.change(change(id,q,"exact"))).isInstanceOf(PaymentApplicationException.class);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void archivedSourceDoesNotBlockExactPublishedTarget(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);
        jdbc.update("update prices set status='ARCHIVED' where id=?",q.fromPriceId().value());afterReread(java.util.function.UnaryOperator.identity()).change(change(id,q,"exact"));drain();
        assertThat(sub(id).entitledPrice()).isEqualTo(q.target());assertThat(sub(id).entitledPrice().version()).isEqualTo(1);
    }
    @Test void durableIntentSurvivesRestartExpiryAndUnknownWithStablePrincipalReplay(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);var other=quote(id,target,ChangeTiming.IMMEDIATE);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNKNOWN);var r=changing.change(change(id,q,"durable"));
        var store=new dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionQuotePersistenceAdapter(jdbc,bean(java.time.Clock.class));
        assertThat(tx.required(()->store.forOperation(site,id,r.operationId()))).contains(q);
        assertThatThrownBy(()->tx.required(()->{store.bind(op(r.operationId()),other);return null;})).isInstanceOf(IllegalArgumentException.class);
        var foreign=new SubscriptionQuote(q.id(),new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),UUID.randomUUID(),q.authorityScope(),q.fromPriceId(),q.fromPriceVersion(),q.target(),q.timing(),q.dueNowMinor(),q.nextAmountMinor(),q.currency(),q.effectiveAt(),q.expiresAt(),q.taxFinal(),q.subscriptionRevision(),q.policyVersion(),q.providerQuoteReference());
        assertThatThrownBy(()->tx.required(()->{store.bind(op(r.operationId()),foreign);return null;})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.required(()->store.lock(foreign.siteId(),q.id()))).isInstanceOf(SubscriptionRuleViolation.class);
        tx.required(()->{store.bind(op(r.operationId()),q);return null;});assertThat(count("subscription_change_intents")).isEqualTo(1);
        assertThat(tx.required(()->store.forOperation(site,UUID.randomUUID(),r.operationId()))).isEmpty();
        assertThat(tx.required(()->store.forOperation(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),id,r.operationId()))).isEmpty();
        TestClock.set(INITIAL_TIME.plusSeconds(600));freshActor();
        var restarted=new SubscriptionPlanService(bean(SubscriptionPlanTransaction.class),a->restartedProvider());
        assertThat(restarted.change(change(id,q,"durable")).operationId()).isEqualTo(r.operationId());
        assertThatThrownBy(()->restarted.change(change(id,other,"durable"))).isInstanceOf(PaymentApplicationException.class);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);controls.advance(account.id(),INITIAL_TIME.plusSeconds(600));
        var snapshot=restartedProvider().retrieve(new Lookup(account,id,sub(id).providerReference(),op(r.operationId()).providerOperationKey()));
        apply(id,snapshot,"restart-unknown");drain();assertThat(sub(id).entitledPrice()).isEqualTo(q.target());assertThat(op(r.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(restarted.change(change(id,q,"durable")).operationId()).isEqualTo(r.operationId());assertThat(count("payments")).isEqualTo(2);
    }
    @Test void customerQuoteCannotBeAdoptedByPartnerOrAnotherKeyAndRevocationClosesReadGap(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);
        var keys=bean(dev.genericpayments.platform.partners.application.port.out.SiteApiKeyPort.class);
        var key=new dev.genericpayments.platform.partners.domain.SiteApiKey(new dev.genericpayments.platform.partners.domain.SiteApiKeyId(UUID.randomUUID()),site,"abcdefghijkl","$argon2id$test",Set.of(dev.genericpayments.platform.partners.domain.PartnerScope.SUBSCRIPTIONS_MANAGE),dev.genericpayments.platform.partners.domain.SiteApiKey.Status.ACTIVE,INITIAL_TIME,null,null,null);keys.insert(key);
        var partner=new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(site,key.id(),key.scopes()));
        var partnerContext=new SubscriptionCommandContext(partner,site,customer.id(),null,"partner","scope-test");
        assertThatThrownBy(()->changing.change(new ChangeSubscriptionPlanUseCase.Command(partnerContext,id,q.id()))).isInstanceOf(PaymentApplicationException.class);
        var partnerQuote=preview.preview(new PreviewSubscriptionChangeUseCase.Query(partnerContext,id,target,ChangeTiming.IMMEDIATE));
        var key2=new dev.genericpayments.platform.partners.domain.SiteApiKey(new dev.genericpayments.platform.partners.domain.SiteApiKeyId(UUID.randomUUID()),site,"mnopqrstuvwx","$argon2id$test",key.scopes(),key.status(),INITIAL_TIME,null,null,null);keys.insert(key2);
        var otherContext=new SubscriptionCommandContext(new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(site,key2.id(),key2.scopes())),site,customer.id(),null,"other-key","scope-test");
        assertThatThrownBy(()->changing.change(new ChangeSubscriptionPlanUseCase.Command(otherContext,id,partnerQuote.id()))).isInstanceOf(PaymentApplicationException.class);
        assertThat(q.authorityScope()).matches("[a-f0-9]{64}");assertThat(q.authorityScope()).isNotEqualTo(partnerQuote.authorityScope());
        var request=change(id,q,"revoked-read-gap");assertThatThrownBy(()->afterReread(read->{sessions.logout(((SubscriptionActor.Customer)actor).session());return read;}).change(request)).isInstanceOf(PaymentApplicationException.class);
        assertThat(sub(id).pendingChange()).isNull();assertThat(count("payments")).isEqualTo(1);
    }
    @Test void unknownQuoteIdIsSafeConflictAndNoOperationLeaks(){
        var id=paid().subscriptionId();
        assertThatThrownBy(()->changing.change(new ChangeSubscriptionPlanUseCase.Command(context("missing-quote"),id,UUID.randomUUID()))).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("subscription_operations")).isEqualTo(1);
    }
    @Test void lostReplyAfterDurableDispatchIsCompletedByFreshConsumerAndSameKeyNeverRedispatches(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);var context=context("crash");
        var transaction=bean(SubscriptionPlanTransaction.class);var candidate=transaction.candidate(context,id);var reserved=transaction.reserve(context,id,q.id(),candidate,true,false);
        assertThat(tx.required(()->bean(SubscriptionQuoteStore.class).forOperation(site,id,reserved.operation().id()))).contains(q);
        var command=new Command(account,reserved.operation(),reserved.subscription().billingPrice(),q.target(),reserved.subscription().providerReference(),null,q.providerQuoteReference(),false,false);
        var read=restartedProvider().preview(new QuoteRequest(command,q.target(),q.timing()));assertThat(read.reference()).isEqualTo(q.providerQuoteReference());transaction.beforeMutation(context,reserved,true);
        restartedProvider().change(command); // process dies before recording the receipt
        assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(sub(id).entitledPrice()).isEqualTo(reserved.subscription().entitledPrice());
        TestClock.set(INITIAL_TIME.plusSeconds(600));freshActor();var snapshot=restartedProvider().retrieve(new Lookup(account,id,sub(id).providerReference(),reserved.operation().providerOperationKey()));
        var consumer=app.getAutowireCapableBeanFactory().createBean(SubscriptionFactService.class);
        tx.required(()->{consumer.apply(new ApplySubscriptionSnapshotUseCase.Command(account,id,snapshot,SubscriptionEvent.Source.RECONCILIATION,"restart-crash","task9"));return null;});
        assertThat(sub(id).entitledPrice()).isEqualTo(q.target());assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(new SubscriptionPlanService(transaction,a->restartedProvider()).change(change(id,q,"crash")).operationId()).isEqualTo(reserved.operation().id());drain();assertThat(count("payments")).isEqualTo(2);
    }
    @Test void concurrentSameKeyCallsHaveOneDurableIntentAndOneProviderCharge()throws Exception{
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var q=quote(id,target,ChangeTiming.IMMEDIATE);var command=change(id,q,"concurrent");
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new java.util.concurrent.CountDownLatch(1);var first=pool.submit(()->{gate.await();return changing.change(command);});var second=pool.submit(()->{gate.await();return changing.change(command);});gate.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).operationId()).isEqualTo(second.get(10,java.util.concurrent.TimeUnit.SECONDS).operationId());
        }
        drain();assertThat(count("subscription_change_intents")).isEqualTo(1);assertThat(count("payments")).isEqualTo(2);
    }
    SubscriptionCommandContext admin(UUID user,String key){
        var session=UUID.randomUUID();var username="admin-"+user;
        jdbc.update("insert into admin_users(id,username,password_hash,status,created_at) values (?,?,'unused','ACTIVE',?) on conflict(id) do nothing",user,username,Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into admin_sessions(id,admin_user_id,token_hash,created_at,last_seen_at,expires_at,absolute_expires_at) values (?,?,?,?,?,?,?)",session,user,UUID.randomUUID().toString(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME.plusSeconds(1200)),Timestamp.from(INITIAL_TIME.plusSeconds(2400)));
        return new SubscriptionCommandContext(new SubscriptionActor.Admin(new dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin(new dev.genericpayments.platform.admin.domain.AdminUserId(user),username,new dev.genericpayments.platform.admin.domain.AdminSessionId(session),INITIAL_TIME.plusSeconds(1200))),site,customer.id(),null,key,"admin-plan");
    }
    @Test void adminQuoteBindsUserRatherThanSessionAndOtherAdminCannotAdopt(){
        var id=paid().subscriptionId();var target=target(id,4000,BillingInterval.MONTH,PlanTransition.Direction.UPGRADE);var user=UUID.randomUUID();var original=admin(user,null);
        var q=preview.preview(new PreviewSubscriptionChangeUseCase.Query(original,id,target,ChangeTiming.IMMEDIATE));var other=admin(UUID.randomUUID(),"other-admin");
        assertThatThrownBy(()->changing.change(new ChangeSubscriptionPlanUseCase.Command(other,id,q.id()))).isInstanceOf(PaymentApplicationException.class);
        jdbc.update("update admin_sessions set revoked_at=? where admin_user_id=?",Timestamp.from(INITIAL_TIME),user);
        var fresh=admin(user,"admin-change");assertThat(SubscriptionPlanTransaction.scope(fresh)).isEqualTo(q.authorityScope());
        var r=changing.change(new ChangeSubscriptionPlanUseCase.Command(fresh,id,q.id()));drain();
        var again=admin(user,"admin-change");assertThat(changing.change(new ChangeSubscriptionPlanUseCase.Command(again,id,q.id())).operationId()).isEqualTo(r.operationId());assertThat(count("payments")).isEqualTo(2);
    }
}
