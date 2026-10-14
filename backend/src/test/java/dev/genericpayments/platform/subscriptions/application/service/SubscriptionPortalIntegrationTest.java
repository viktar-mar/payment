package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
@org.springframework.test.context.ActiveProfiles("local")
class SubscriptionPortalIntegrationTest extends SubscriptionRecoveryFixture {
    @org.springframework.beans.factory.annotation.Autowired dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase portals;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"http://localhost:4173/manage?screen=subscriptions","http://127.0.0.1:4173/manage","http://[::1]:4173/manage"})
    void localPortalUsesExactUnmarkedAllowlistWithoutChangingSubscription(String url) {
        var id=paid().subscriptionId();var p=policies.load(site);
        policies.save(new SubscriptionPolicy(site,p.version(),true,3,false,true,false,Set.of(url)),p.version());
        var before=sub(id);int cash=count("payments"),events=count("subscription_events");
        var command=new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase.Command(context("loopback-portal"),id,url);
        var navigation=org.assertj.core.api.Assertions.catchThrowable(()->portals.create(command));
        assertThat(navigation).as("Allowed local HTTP Portal must complete").isNull();
        var result=portals.create(command);
        var operation=jdbc.queryForObject("select public_operation_id from subscription_portal_sessions",java.util.UUID.class);
        assertThat(result.navigationUrl()).isEqualTo(url+(url.contains("?")?"&":"?")+"subscriptionPortal=simulated&operationId="+operation);
        assertThat(bean(SubscriptionPortalService.class).returned(context(null),id,operation)).isEqualTo(before);
        assertThat(count("payments")).isEqualTo(cash);assertThat(count("subscription_events")).isEqualTo(events);
        assertThatThrownBy(()->portals.create(new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase.Command(context("other-return"),id,url+"/other"))).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void portalNavigatesOnlyToAllowlistedManagerWithPublicOperation(){
        var id=paid().subscriptionId();var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,3,false,true,false,Set.of("https://shop.example/account")),p.version());
        var result=portals.create(new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase.Command(context("portal"),id,"https://shop.example/account"));
        assertThat(result).isNotNull();assertThat(result.simulated()).isTrue();
        assertThat(result.navigationUrl()).startsWith("https://shop.example/account?");
        assertThat(count("subscription_portal_sessions")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void portalReturnUrlCannotEscapeAllowlist(){
        var p=new SubscriptionPolicy(site,1,true,3,false,true,false,Set.of("https://shop.example/account"));
        assertThat(SubscriptionPortalService.requireAllowedReturn(p,"https://shop.example/account")).isEqualTo("https://shop.example/account");
        assertThatThrownBy(()->SubscriptionPortalService.requireAllowedReturn(p,"https://shop.example/account.evil")).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void disabledPortalAndChangedRequestFailClosed(){
        var id=paid().subscriptionId();var command=new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase.Command(context("portal"),id,"https://shop.example/account");
        assertThatThrownBy(()->portals.create(command)).isInstanceOf(PaymentApplicationException.class);
        var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,3,false,true,false,Set.of("https://shop.example/account","https://shop.example/other")),p.version());
        var navigation=portals.create(command);assertThat(portals.create(command)).isEqualTo(navigation);
        assertThatThrownBy(()->portals.create(new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase.Command(context("portal"),id,"https://shop.example/other"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("subscription_operations")).isEqualTo(2);
        assertThat(navigation.toString()).doesNotContain(navigation.navigationUrl());
    }
    @Test void portalReturnRequiresOwnerAndExpiryAndNeverGrantsOrBlocksSetup(){
        var id=paid().subscriptionId();var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,3,false,true,false,Set.of("https://shop.example/account")),p.version());
        var navigation=portals.create(new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase.Command(context("portal"),id,"https://shop.example/account"));
        var op=jdbc.queryForObject("select public_operation_id from subscription_portal_sessions",java.util.UUID.class);
        assertThat(navigation.navigationUrl()).isEqualTo("https://shop.example/account?subscriptionPortal=simulated&operationId="+op);
        var service=bean(SubscriptionPortalService.class);var before=sub(id);var events=count("subscription_events");
        assertThat(service.returned(context(null),id,op)).isEqualTo(before);assertThat(count("subscription_events")).isEqualTo(events);
        var other=customers.save(dev.genericpayments.platform.customers.domain.Customer.create(new dev.genericpayments.platform.customers.domain.CustomerId(java.util.UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var session=sessions.resolveRequired(sessions.issue(site,other.id(),"https://facts.test").rawToken(),"https://facts.test");
        var foreign=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(session),site,other.id(),"https://facts.test",null,"test");
        assertThatThrownBy(()->service.returned(foreign,id,op)).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->service.returned(context(null),java.util.UUID.randomUUID(),op)).isInstanceOf(PaymentApplicationException.class);
        TestClock.set(navigation.expiresAt());freshActor();
        assertThatThrownBy(()->service.returned(context(null),id,op)).isInstanceOf(PaymentApplicationException.class);
        assertThat(bean(dev.genericpayments.platform.subscriptions.application.port.in.SetupSubscriptionMethodUseCase.class).setup(new dev.genericpayments.platform.subscriptions.application.port.in.SetupSubscriptionMethodUseCase.Command(context("after-expiry"),id,true)).nextAction()).isNotNull();
        assertThat(count("payments")).isEqualTo(1);
    }
}
