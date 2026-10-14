package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.acceptance.runtime.OwnedSubscriptionRuntime;
import dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService;
import dev.genericpayments.platform.webhooks.application.service.CallbackSigner;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

class SubscriptionRuntimeClockIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired CallbackConfigurationPort config;
    @Autowired OutboxPort outbox;
    @Autowired CallbackPayloadPort payload;
    @Autowired EncryptedSecretPort encryption;

    @Test void currentCustomerAuthenticationExpiresOnlyOnSecurityTime() throws Exception {
        var at=Instant.now();var business=new OwnedSubscriptionRuntime.BusinessClock(at);
        var security=new OwnedSubscriptionRuntime.BusinessClock(at);
        var factory=OwnedSubscriptionRuntime.factory(business);factory.registerSingleton("clock",security);
        var constructor=dev.genericpayments.platform.customers.application.service.CustomerSessionService.class.getConstructors()[0];
        Object[] dependencies=new Object[constructor.getParameterCount()];
        for(int i=0;i<dependencies.length;i++){
            var type=constructor.getParameterTypes()[i];
            dependencies[i]=type==Clock.class?factory.resolveDependency(new org.springframework.beans.factory.config.DependencyDescriptor(new org.springframework.core.MethodParameter(constructor,i),true),"customerSessionService"):bean(type);
        }
        var service=(dev.genericpayments.platform.customers.application.service.CustomerSessionService)constructor.newInstance(dependencies);
        var issued=tx.required(()->service.issue(site,customer.id(),"https://facts.test"));
        business.advance(at.plus(Duration.ofDays(35)));
        assertThat(tx.required(()->service.resolveRequired(issued.rawToken(),"https://facts.test")).customerId()).isEqualTo(customer.id());
        security.advance(at.plus(Duration.ofMinutes(30)));
        assertThatThrownBy(()->tx.required(()->service.resolveRequired(issued.rawToken(),"https://facts.test")))
            .isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
    }

    @Test void futureBusinessFactsRetryConsistentlyWhileSignaturesUseRealUtc() {
        var future=Instant.now().plus(Duration.ofDays(35));TestClock.set(future);freshActor();
        config.configure(site.value(),"https://example.com/callback",true);
        var r=start();apply(r.subscriptionId(),retrieve(r),"future-callback");
        var business=new OwnedSubscriptionRuntime.BusinessClock(future);
        assertThat(outbox.claimAutomatic("too-early",10,Instant.now(),Duration.ofSeconds(30))).isEmpty();
        var claims=outbox.claimAutomatic("first",10,business.instant(),Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        for(var claim:claims){
            byte[] secret=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));
            var verifying=new CallbackDeliveryService(OwnedSubscriptionRuntime.completionClock(outbox,business),encryption,payload,(endpoint,headers,body)->{
                long timestamp=Long.parseLong(headers.get("Payment-Timestamp"));
                assertThat(Math.abs(Instant.now().getEpochSecond()-timestamp)).isLessThanOrEqualTo(5);
                assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(secret,timestamp,body));
                return CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK);
            },Clock.systemUTC());
            try{verifying.deliver(claim);}finally{Arrays.fill(secret,(byte)0);}
        }
        assertThat(outbox.claimAutomatic("before-retry",10,future.plusSeconds(59),Duration.ofSeconds(30))).isEmpty();
        business.advance(future.plusSeconds(60));
        var retries=outbox.claimAutomatic("retry",10,business.instant(),Duration.ofSeconds(30));assertThat(retries).hasSize(2);
        var success=new CallbackDeliveryService(OwnedSubscriptionRuntime.completionClock(outbox,business),encryption,payload,(endpoint,headers,body)->CallbackHttpPort.Result.success(204),Clock.systemUTC());
        retries.forEach(success::deliver);
        assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");
        assertThat(jdbc.queryForList("select updated_at from outbox_events",java.sql.Timestamp.class)).allSatisfy(at->assertThat(at.toInstant()).isEqualTo(business.instant()));
    }
}
