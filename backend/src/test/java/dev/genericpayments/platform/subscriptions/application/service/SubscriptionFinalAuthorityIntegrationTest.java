package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.customers.domain.*;
import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Final handoff: actual two-caller overlap and equal safe foreign/unknown lookups. */
class SubscriptionFinalAuthorityIntegrationTest extends SubscriptionRecoveryFixture {
    void requiredTrial() {
        price=price(7);
        var id=UUID.randomUUID();
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,amount_minor,currency,billing_interval,trial_days,'REQUIRED_BEFORE_TRIAL',tax_mode,status,published_at,created_at,updated_at from prices where id=?",id,"required-"+id,price.value());
        price=new dev.genericpayments.platform.catalog.domain.PriceId(id);
    }
    @Test void unknownAndForeignActionsAndConfirmationsAreEqual404WithoutProviderLookup() {
        requiredTrial();var r=start();var calls=new AtomicInteger();
        var service=new SubscriptionActionService(bean(SubscriptionInteractionTransaction.class),a->{calls.incrementAndGet();return restartedProvider();});
        var other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var session=sessions.resolveRequired(sessions.issue(site,other.id(),"https://facts.test").rawToken(),"https://facts.test");
        var foreign=new SubscriptionCommandContext(new SubscriptionActor.Customer(session),site,other.id(),"https://facts.test","foreign","final-acceptance");
        for(var ctx:java.util.List.of(context("missing"),foreign)) {
            var operation=ctx==foreign?r.operationId():UUID.randomUUID();
            assertThatThrownBy(()->service.get(new GetSubscriptionActionUseCase.Query(ctx,r.subscriptionId(),operation)))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.status()).isEqualTo(404));
            assertThatThrownBy(()->service.confirm(new ConfirmSubscriptionActionUseCase.Command(ctx,r.subscriptionId(),operation,r.nextAction().secret())))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.status()).isEqualTo(404));
        }
        assertThat(calls).hasValue(0);assertThat(count("subscription_action_capabilities")).isZero();
    }
    @Test void simultaneousSameKeyConfirmationConsumesOneCapabilityAndOneImmutableReceipt() throws Exception { simultaneous(true); }
    @Test void simultaneousCompetingConfirmationKeysAllowOnlyOneWinner() throws Exception { simultaneous(false); }
    void simultaneous(boolean sameKey) throws Exception {
        requiredTrial();var r=start();var rendezvous=new CountDownLatch(2);var release=new CountDownLatch(1);
        var actual=restartedProvider();
        var proxy=(SubscriptionProviderPort)Proxy.newProxyInstance(SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(p,m,args)->{
            Object value;try {value=m.invoke(actual,args);} catch(InvocationTargetException e){throw e.getCause();}
            if(m.getName().equals("nextAction")) {rendezvous.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("confirmation rendezvous timed out");}
            return value;
        });
        var service=new SubscriptionActionService(bean(SubscriptionInteractionTransaction.class),a->proxy);
        String winningKey;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->outcome(service,new ConfirmSubscriptionActionUseCase.Command(context("first"),r.subscriptionId(),r.operationId(),r.nextAction().secret())));
            var second=executor.submit(()->outcome(service,new ConfirmSubscriptionActionUseCase.Command(context(sameKey?"first":"second"),r.subscriptionId(),r.operationId(),r.nextAction().secret())));
            try {assertThat(rendezvous.await(10,TimeUnit.SECONDS)).as("both callers retrieved before either claims").isTrue();}
            finally {release.countDown();}
            int a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            if(sameKey)assertThat(java.util.List.of(a,b)).containsExactly(200,200);
            else assertThat(java.util.List.of(a,b)).containsExactlyInAnyOrder(200,409);
            winningKey=a==200?"first":"second";
        }
        drain();
        assertThat(jdbc.queryForObject("select count(*) from subscription_action_capabilities where consumed_at is not null",Integer.class)).isEqualTo(1);
        assertThat(count("fake_subscription_operations")).isEqualTo(2); // CREATE and its one immutable confirmation receipt.
        assertThat(count("fake_subscription_invoices")).isZero();assertThat(count("fake_subscription_cash_objects")).isZero();
        assertThat(count("payments")).isZero();assertThat(count("subscription_operations")).isEqualTo(1);
        var before=jdbc.queryForList("select row_to_json(t)::text from fake_subscription_operations t order by operation_key",String.class);
        service.confirm(new ConfirmSubscriptionActionUseCase.Command(context(winningKey),r.subscriptionId(),r.operationId(),r.nextAction().secret()));
        assertThat(jdbc.queryForList("select row_to_json(t)::text from fake_subscription_operations t order by operation_key",String.class)).isEqualTo(before);
    }
    int outcome(SubscriptionActionService service,ConfirmSubscriptionActionUseCase.Command command) {
        try {service.confirm(command);return 200;} catch(PaymentApplicationException e){return e.status();}
    }
}
