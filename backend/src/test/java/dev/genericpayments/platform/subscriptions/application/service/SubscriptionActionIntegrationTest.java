package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.UUID;
class SubscriptionActionIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired GetSubscriptionActionUseCase actions;
    @Autowired ConfirmSubscriptionActionUseCase confirmations;
    void requiredPrice(){price=price(7);var id=java.util.UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,amount_minor,currency,billing_interval,trial_days,'REQUIRED_BEFORE_TRIAL',tax_mode,status,published_at,created_at,updated_at from prices where id=?",id,"required-"+id,price.value());price=new dev.genericpayments.platform.catalog.domain.PriceId(id);}
    @Test void confirmationContinuesOriginalOperationAndExactRetryConsumesOnce(){
        requiredPrice();var r=start();
        var command=new ConfirmSubscriptionActionUseCase.Command(context("confirm"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        var result=confirmations.confirm(command);assertThat(result).isNotNull();
        assertThat(result.operationId()).isEqualTo(r.operationId());assertThat(sub(r.subscriptionId()).trial()).isNull();
        drain();assertThat(sub(r.subscriptionId()).trial()).isNotNull();
        assertThat(confirmations.confirm(command).operationId()).isEqualTo(r.operationId());
        assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("payments")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_action_capabilities where consumed_at is not null",Integer.class)).isEqualTo(1);
    }
    @Test void providerConfirmationReplayIgnoresLaterEconomicArgumentsButRejectsChangedTrialChoice(){
        requiredPrice();var r=start();var o=op(r.operationId());var s=sub(r.subscriptionId());
        var request=new Command(account,o,s.billingPrice(),null,s.providerReference(),null,null,true,true);
        var receipt=providers.forAccount(account).confirmAction(request,r.nextAction().secret());
        var changed=new Command(account,o,s.billingPrice(),s.billingPrice(),s.providerReference(),"unused-invoice","unused-quote",false,true);
        assertThatCode(()->assertThat(restartedProvider().confirmAction(changed,r.nextAction().secret())).isEqualTo(receipt)).doesNotThrowAnyException();
        assertThatThrownBy(()->restartedProvider().confirmAction(new Command(account,o,s.billingPrice(),null,s.providerReference(),null,null,true,false),r.nextAction().secret())).isInstanceOf(RuntimeException.class);
    }
    @Test void authorizedRequiredCreateRefreshesExpiredOriginalAction(){
        requiredPrice();
        var r=start();var before=view(r.subscriptionId());var old=r.nextAction();
        advance(INITIAL_TIME.plusSeconds(901));
        var fresh=actions.get(new GetSubscriptionActionUseCase.Query(context(null),r.subscriptionId(),r.operationId()));
        assertThat(fresh).isNotNull();assertThat(fresh.secret().equals(old.secret())).isFalse();
        assertThat(view(r.subscriptionId()).invoices()).isEqualTo(before.invoices());
        assertThatThrownBy(()->confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("expired"),r.subscriptionId(),r.operationId(),old.secret()))).isInstanceOf(PaymentApplicationException.class);
        assertThat(confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("fresh"),r.subscriptionId(),r.operationId(),fresh.secret())).operationId()).isEqualTo(r.operationId());
        drain();assertThat(sub(r.subscriptionId()).trial()).isNotNull();assertThat(count("payments")).isZero();
    }
    @Test void immutableReceiptRetryAcceptsLaterPriceWithoutTrialTerms(){
        requiredPrice();var r=start();var s=sub(r.subscriptionId());var p=s.billingPrice();var o=op(r.operationId());
        var receipt=providers.forAccount(account).confirmAction(new Command(account,o,p,null,s.providerReference(),null,null,true,true),r.nextAction().secret());
        var later=new SubscriptionPriceTerms(p.priceId(),p.priceKey(),p.version(),p.productId(),p.regularAmount(),p.interval(),0,p.trialPolicy(),p.taxMode());
        assertThatCode(()->assertThat(restartedProvider().confirmAction(new Command(account,o,later,null,s.providerReference(),null,null,false,true),r.nextAction().secret())).isEqualTo(receipt)).doesNotThrowAnyException();
    }
    @Test void firstCreateStillRejectsTrialDecisionWithoutTrialTermsBeforeAnyProviderRows(){
        var reserved=commands.reserveCreate(command("invalid-trial"));var o=reserved.operation();
        assertThatThrownBy(()->providers.forAccount(account).create(new Command(account,o,reserved.subscription().billingPrice(),null,null,null,null,true,true))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_subscription_objects")).isZero();assertThat(count("fake_subscription_invoices")).isZero();assertThat(count("fake_subscription_cash_objects")).isZero();assertThat(count("fake_subscription_operations")).isZero();
    }
    @Test void firstConfirmationRejectsWrongOriginalTrialDecisionAndTokenWithoutEffects(){
        requiredPrice();var r=start();var o=op(r.operationId());var s=sub(r.subscriptionId());var before=view(s.id());var provider=restartedProvider();
        assertThatThrownBy(()->provider.confirmAction(new Command(account,o,s.billingPrice(),null,s.providerReference(),null,null,true,false),r.nextAction().secret())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->provider.confirmAction(new Command(account,o,s.billingPrice(),null,s.providerReference(),null,null,true,true),"not-the-action")).isInstanceOf(RuntimeException.class);
        assertThat(view(s.id())).isEqualTo(before);assertThat(count("fake_subscription_operations")).isEqualTo(1);assertThat(count("fake_subscription_cash_objects")).isZero();
    }
    @Test void changedRequestAndSecondConfirmationKeyCannotReuseConsumedCapability(){
        requiredPrice();var r=start();var c=new ConfirmSubscriptionActionUseCase.Command(context("once"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        confirmations.confirm(c);
        assertThatThrownBy(()->confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("once"),r.subscriptionId(),r.operationId(),"wrong"))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("twice"),r.subscriptionId(),r.operationId(),r.nextAction().secret()))).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("subscription_operations")).isEqualTo(1);
    }
    @Test void capabilityStoreIsAtomicBoundAndNeverExtendsExpiry(){
        requiredPrice();var r=start();var a=actions.get(new GetSubscriptionActionUseCase.Query(context(null),r.subscriptionId(),r.operationId()));
        var store=bean(SubscriptionActionStore.class);var digest=SubscriptionInteractionTransaction.digest(a.secret());
        assertThat(tx.required(()->store.consume(site,customer.id(),account.id(),r.subscriptionId(),r.operationId(),"WRONG",digest,INITIAL_TIME))).isFalse();
        assertThat(tx.required(()->store.consume(site,customer.id(),account.id(),r.subscriptionId(),UUID.randomUUID(),a.kind().name(),digest,INITIAL_TIME))).isFalse();
        assertThat(tx.required(()->store.consume(site,customer.id(),new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()),r.subscriptionId(),r.operationId(),a.kind().name(),digest,INITIAL_TIME))).isFalse();
        assertThat(tx.required(()->store.consume(site,new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),account.id(),r.subscriptionId(),r.operationId(),a.kind().name(),digest,INITIAL_TIME))).isFalse();
        assertThat(tx.required(()->store.consume(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),customer.id(),account.id(),r.subscriptionId(),r.operationId(),a.kind().name(),digest,INITIAL_TIME))).isFalse();
        assertThat(tx.required(()->store.consume(site,customer.id(),account.id(),r.subscriptionId(),r.operationId(),a.kind().name(),digest,a.expiresAt()))).isFalse();
        assertThat(tx.required(()->store.consume(site,customer.id(),account.id(),r.subscriptionId(),r.operationId(),a.kind().name(),digest,INITIAL_TIME))).isTrue();
        assertThat(tx.required(()->store.consume(site,customer.id(),account.id(),r.subscriptionId(),r.operationId(),a.kind().name(),digest,INITIAL_TIME))).isFalse();
        var row=jdbc.queryForMap("select token_digest,expires_at,issued_at,recurring_consent,consent_policy_version from subscription_action_capabilities");
        assertThat(row.get("token_digest")).isEqualTo(digest);assertThat(row.get("recurring_consent")).isEqualTo(true);
        assertThat(a.expiresAt()).isBeforeOrEqualTo(INITIAL_TIME.plusSeconds(600));
        TestClock.set(INITIAL_TIME.plusSeconds(30));
        tx.required(()->{store.issue(site,customer.id(),account.id(),r.subscriptionId(),r.operationId(),a.kind().name(),digest,INITIAL_TIME.plusSeconds(630));return null;});
        assertThat(jdbc.queryForObject("select expires_at from subscription_action_capabilities",java.sql.Timestamp.class).toInstant()).isEqualTo(a.expiresAt());
        assertThat(new ConfirmSubscriptionActionUseCase.Command(context("redacted"),r.subscriptionId(),r.operationId(),a.secret()).toString()).doesNotContain(a.secret());
        assertThat(a.toString()).doesNotContain(a.secret());
        for(var table:java.util.List.of("subscriptions","subscription_operations","subscription_events","subscription_action_capabilities"))
            assertThat(jdbc.queryForList("select row_to_json(t)::text from "+table+" t",String.class).stream().anyMatch(rowText->rowText.contains(a.secret()))).as("platform table %s is capability-free",table).isFalse();
    }
    @Test void wrongOwnerAndOperationCannotReadOrConfirm(){
        requiredPrice();var r=start();var other=customers.save(dev.genericpayments.platform.customers.domain.Customer.create(new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var session=sessions.resolveRequired(sessions.issue(site,other.id(),"https://facts.test").rawToken(),"https://facts.test");
        var ctx=new SubscriptionCommandContext(new SubscriptionActor.Customer(session),site,other.id(),"https://facts.test","foreign","test");
        assertThatThrownBy(()->actions.get(new GetSubscriptionActionUseCase.Query(ctx,r.subscriptionId(),r.operationId()))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(ctx,r.subscriptionId(),r.operationId(),r.nextAction().secret()))).isInstanceOf(PaymentApplicationException.class);
        var crossSite=new SubscriptionCommandContext(actor,new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),customer.id(),"https://facts.test","cross-site","test");
        assertThatThrownBy(()->actions.get(new GetSubscriptionActionUseCase.Query(crossSite,r.subscriptionId(),r.operationId()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("wrong-op"),r.subscriptionId(),UUID.randomUUID(),r.nextAction().secret()))).isInstanceOf(RuntimeException.class);
        assertThat(count("subscription_action_capabilities")).isZero();
    }
    @Test void durableClaimPrecedesOutsideProviderCallAndRetrySurvivesTransportLoss(){
        requiredPrice();var r=start();var actual=restartedProvider();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var proxy=(SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(p,m,args)->{
            assertThat(tx.active()).isFalse();
            if(m.getName().equals("confirmAction")){
                assertThat(jdbc.queryForObject("select count(*) from subscription_action_capabilities where consumed_at is not null and confirmation_key_digest is not null",Integer.class)).isEqualTo(1);
                if(calls.getAndIncrement()==0)throw new IllegalStateException("simulated crash before provider effect");
            }
            try{return m.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        var service=new SubscriptionActionService(bean(SubscriptionInteractionTransaction.class),a->proxy);
        var c=new ConfirmSubscriptionActionUseCase.Command(context("crash"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        assertThat(service.confirm(c).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThat(sub(r.subscriptionId()).trial()).isNull();
        assertThat(service.confirm(c).operationId()).isEqualTo(r.operationId());drain();assertThat(sub(r.subscriptionId()).trial()).isNotNull();
        assertThat(count("subscription_operations")).isEqualTo(1);
    }
    @Test void logoutAfterProviderEffectPreventsReplyButFreshSessionResolvesSameReceipt(){
        requiredPrice();var r=start();var actual=restartedProvider();
        var proxy=(SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(p,m,args)->{
            assertThat(tx.active()).isFalse();Object result;try{result=m.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            if(m.getName().equals("confirmAction"))sessions.logout(((SubscriptionActor.Customer)actor).session());return result;
        });
        var service=new SubscriptionActionService(bean(SubscriptionInteractionTransaction.class),a->proxy);
        var c=new ConfirmSubscriptionActionUseCase.Command(context("logout"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        assertThatThrownBy(()->service.confirm(c)).isInstanceOf(PaymentApplicationException.class);
        assertThat(op(r.operationId()).status()).isEqualTo(SubscriptionOperation.Status.REQUIRES_ACTION);
        freshActor();var retry=new ConfirmSubscriptionActionUseCase.Command(context("logout"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        assertThat(confirmations.confirm(retry).operationId()).isEqualTo(r.operationId());drain();assertThat(sub(r.subscriptionId()).trial()).isNotNull();
        assertThat(count("subscription_action_capabilities")).isEqualTo(1);
    }
    @Test void autonomousRecoveryRefreshKeepsAssociationAndOriginalInvoiceAttempt(){
        var id=paid().subscriptionId();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.REQUIRES_ACTION);
        advance(sub(id).currentPeriod().end());var before=view(id);var r=recovery.recover(recoverCommand(id,"recover-action"));var old=r.nextAction();
        advance(before.observedAt().plusSeconds(601));
        assertThat(restartedProvider().nextAction(new Lookup(account,id,sub(id).providerReference(),null))).isNull();
        var fresh=actions.get(new GetSubscriptionActionUseCase.Query(context(null),id,r.operationId()));
        assertThat(fresh.secret().equals(old.secret())).isFalse();assertThat(view(id).invoices()).isEqualTo(before.invoices());
        assertThatThrownBy(()->confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("stale-recovery"),id,r.operationId(),old.secret()))).isInstanceOf(PaymentApplicationException.class);
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("recovery-confirm"),id,r.operationId(),fresh.secret()));drain();
        assertThat(sub(id).debt()).isNull();assertThat(view(id).invoices()).hasSize(before.invoices().size());
        assertThat(view(id).invoices().getLast().cashObjects()).hasSize(1);
    }
    @Test void originalConfirmationReplaysAfterActualPlanChangeAndAdapterReconstruction(){
        requiredPrice();var r=start();var confirm=new ConfirmSubscriptionActionUseCase.Command(context("original-confirm"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        confirmations.confirm(confirm);drain();advance(sub(r.subscriptionId()).trial().end());
        var targetId=UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,3000,currency,billing_interval,0,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at from prices where id=?",targetId,"changed-"+targetId,price.value());
        var target=bean(SubscriptionCatalogPort.class).terms(site,new dev.genericpayments.platform.catalog.domain.PriceId(targetId));var s=sub(r.subscriptionId());
        policies.saveTransition(new PlanTransition(site,s.productId(),s.billingPrice().priceId(),target.priceId(),PlanTransition.Direction.UPGRADE,true,policies.load(site).version()));
        var quote=bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),s.id(),target.priceId(),ChangeTiming.IMMEDIATE));
        var change=bean(ChangeSubscriptionPlanUseCase.class).change(new ChangeSubscriptionPlanUseCase.Command(context("later-change"),s.id(),quote.id()));
        drain();assertThat(sub(s.id()).billingPrice()).isEqualTo(target);
        var invoiceCount=count("fake_subscription_invoices");var cashCount=count("fake_subscription_cash_objects");
        var restartedService=new SubscriptionActionService(bean(SubscriptionInteractionTransaction.class),a->restartedProvider());
        var retry=new ConfirmSubscriptionActionUseCase.Command(context("original-confirm"),r.subscriptionId(),r.operationId(),r.nextAction().secret());
        assertThat(restartedService.confirm(retry).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("fake_subscription_invoices")).isEqualTo(invoiceCount);assertThat(count("fake_subscription_cash_objects")).isEqualTo(cashCount);
        assertThatThrownBy(()->restartedProvider().change(new Command(account,op(change.operationId()),s.billingPrice(),target,s.providerReference(),null,quote.providerQuoteReference(),true,false))).isInstanceOf(RuntimeException.class);
    }
}
