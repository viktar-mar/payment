package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Hosted CI only. Uses real application transactions/SQL; never execute in local pure-test runs.
 * Only this context imports the scoped sample. The normal operational/security Clock is retained. */
@Import(SubscriptionBillingTimeDomainIntegrationTest.BillingConfiguration.class)
class SubscriptionBillingTimeDomainIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired IsolatedBillingTime billing;
    @Autowired QuerySubscriptionsUseCase queries;
    @Autowired SubscriptionDeadlineWorker deadlines;
    @Autowired SubscriptionSweepPort sweep;
    @Autowired SubscriptionEventStore events;
    @Autowired SubscriptionActionStore actions;
    @Autowired CallbackConfigurationPort config;
    @Autowired OutboxPort outbox;
    @Autowired CallbackPayloadPort payload;
    @Autowired EncryptedSecretPort encryption;
    Instant utc;

    @BeforeEach void operationalTime(){
        billing.clear();utc=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        TestClock.set(utc);freshActor();
    }

    @Test void paidRenewalAndExactGraceKeepUtcAuditDeliveryFencesAndSecurity() throws Exception {
        var reveal=config.configure(site.value(),"https://example.com/callback",true);
        var r=start();var source=retrieve(r);var initial=sub(r.subscriptionId());
        var billingAt=utc.plus(Duration.ofDays(35));billing.own(initial,billingAt);
        var paid=paidSnapshot(source,billingAt);
        var session=sessions.issue(site,customer.id(),"https://facts.test");
        tx.required(()->{for(var key:List.of("live-capability","expired-capability"))actions.issue(site,customer.id(),account.id(),initial.id(),r.operationId(),"FAKE_CONFIRM_PAYMENT",key,utc.plusSeconds(120));return null;});
        // The existing claim/fence uses UTC even though billing is already 35 days ahead.
        var lease=sweep.claim("billing-test",10,utc,Duration.ofSeconds(30)).stream().filter(c->c.subscriptionId().equals(initial.id())).findFirst().orElseThrow();
        var selected=op(r.operationId());
        tx.required(()->{facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,initial.id(),paid,SubscriptionEvent.Source.RECONCILIATION,"paid","billing-time",new ApplySubscriptionSnapshotUseCase.Fence(initial.revision(),selected.id(),selected.version(),lease,null)));return null;});
        var active=sub(initial.id());
        assertThat(active.currentPeriod()).isEqualTo(paid.period());assertThat(active.paidThrough()).isEqualTo(paid.period().end());
        assertThat(active.createdAt()).isEqualTo(utc);assertThat(active.updatedAt()).isEqualTo(utc);
        assertThat(op(r.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(op(r.operationId()).updatedAt()).isEqualTo(utc);
        var advanced=tx.required(()->sweep.advanceRevision(lease,active.revision(),utc));
        tx.required(()->{sweep.requireCurrent(advanced,utc);return null;});
        assertThat(sweep.claim("cannot-steal",10,utc.plusSeconds(29),Duration.ofSeconds(30))).isEmpty();
        assertThat(queries.get(actor,site,active.id()).permittedActions()).isEmpty();
        assertThat(queries.get(actor,site,active.id()).access().evaluatedAt()).isEqualTo(billingAt);
        assertThat(queries.access(actor,site,customer.id(),active.productId()).allowed()).isTrue();
        assertUtcRows("subscription_billing_records","created_at","updated_at");
        assertUtcRows("subscription_provider_objects","created_at");
        assertUtcRows("payments","created_at","updated_at");
        assertUtcRows("payment_events","received_at");
        assertThat(jdbc.queryForList("select occurred_at from payment_events",Timestamp.class)).containsOnly(Timestamp.from(billingAt));
        assertUtcRows("subscription_events","received_at");
        assertUtcRows("outbox_events","next_attempt_at","created_at","updated_at");
        assertThat(events.list(site,active.id(),100,0)).allSatisfy(e->assertThat(e.occurredAt()).isEqualTo(billingAt));
        assertThat(jdbc.queryForList("select source_kind from outbox_events",String.class)).containsExactlyInAnyOrder("PAYMENT","SUBSCRIPTION");
        var frozen=jdbc.queryForList("select id,payload::text,created_at from outbox_events order by id");
        apply(active.id(),paid,"duplicate-paid");
        assertThat(sub(active.id())).isEqualTo(active);assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForList("select id,payload::text,created_at from outbox_events order by id")).isEqualTo(frozen);

        var claims=outbox.claimAutomatic("utc-first",10,utc,Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        billing.at(active.paidThrough());
        assertThat(outbox.claimAutomatic("no-lease-steal",10,utc.plusSeconds(29),Duration.ofSeconds(30))).isEmpty();
        assertThat(sessions.resolveRequired(session.rawToken(),"https://facts.test").customerId()).isEqualTo(customer.id());
        assertThat(tx.required(()->actions.consume(site,customer.id(),account.id(),active.id(),r.operationId(),"FAKE_CONFIRM_PAYMENT","live-capability",utc))).isTrue();
        byte[] secret=Base64.getUrlDecoder().decode(reveal.secret());
        try {
            var delivery=new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{
                assertThat(headers.get("Payment-Timestamp")).isEqualTo(Long.toString(utc.getEpochSecond()));
                assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(secret,utc.getEpochSecond(),body));
                return CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK);
            },Clock.fixed(utc,ZoneOffset.UTC));
            claims.forEach(delivery::deliver);
        } finally {Arrays.fill(secret,(byte)0);}
        assertThat(jdbc.queryForList("select next_attempt_at from outbox_events",Timestamp.class)).containsOnly(Timestamp.from(utc.plusSeconds(60)));
        assertThat(outbox.claimAutomatic("no-early-retry",10,utc.plusSeconds(59),Duration.ofSeconds(30))).isEmpty();
        var retries=outbox.claimAutomatic("utc-retry",10,utc.plusSeconds(60),Duration.ofSeconds(30));assertThat(retries).hasSize(2);
        retries.forEach(c->assertThat(outbox.complete(c,CallbackHttpPort.Result.success(204),utc.plusSeconds(60))).isTrue());
        // Expired original lease fails even though its revision was advanced correctly.
        assertThatThrownBy(()->tx.required(()->{sweep.requireCurrent(advanced,utc.plusSeconds(30));return null;})).hasMessage("CLAIM_LOST");

        var renewalPeriod=new Subscription.Period(active.paidThrough(),active.paidThrough().plus(Duration.ofDays(30)));
        var invoice=new Invoice("unpaid-"+UUID.randomUUID(),BillingRecord.Kind.RENEWAL,active.billingPrice(),null,renewalPeriod,2000,"EUR",SettlementKind.OPEN,List.of());
        var unpaid=new Snapshot(active.providerReference(),Subscription.Status.PAST_DUE,active.billingPrice(),renewalPeriod,null,null,List.of(invoice),null,true,paid.providerRevision()+1,utc);
        apply(active.id(),unpaid,"failed-renewal");var failed=sub(active.id());
        assertThat(failed.updatedAt()).isEqualTo(utc);assertThat(failed.debt().graceDeadline()).isEqualTo(active.paidThrough().plus(Duration.ofDays(3)));
        assertThat(queries.access(actor,site,customer.id(),active.productId()).reason()).isEqualTo(AccessDecision.Reason.GRACE);
        var expired=new SubscriptionSweepPort.Claim(site,active.id(),"exact-deadline",null,failed.revision());
        billing.at(failed.debt().graceDeadline().minusSeconds(1));deadlines.expire(expired);
        assertThat(events.list(site,active.id(),100,0)).noneMatch(e->e.type()==SubscriptionEvent.Type.ACCESS_SUSPENDED);
        billing.at(failed.debt().graceDeadline());deadlines.expire(expired);deadlines.expire(expired);
        var suspended=events.list(site,active.id(),100,0).stream().filter(e->e.type()==SubscriptionEvent.Type.ACCESS_SUSPENDED).toList();
        assertThat(suspended).hasSize(1);assertThat(suspended.getFirst().occurredAt()).isEqualTo(failed.debt().graceDeadline());assertThat(suspended.getFirst().receivedAt()).isEqualTo(utc);
        assertThat(queries.access(actor,site,customer.id(),active.productId()).allowed()).isFalse();
        var revision=failed.revision();apply(active.id(),unpaid,"duplicate-renewal");assertThat(sub(active.id()).revision()).isEqualTo(revision);
        var remaining=outbox.claimAutomatic("utc-lifecycle",10,utc,Duration.ofSeconds(30));assertThat(remaining).hasSize(2);
        assertThat(remaining).allSatisfy(c->assertThat(c.createdAt()).isEqualTo(utc));
        assertThatThrownBy(deadlines::runOnce).hasMessage("ISOLATED_BILLING_UNSCOPED");
        assertThatThrownBy(()->queries.list(new QuerySubscriptionsUseCase.Query(actor,site,customer.id(),null,null,10,null))).hasMessage("ISOLATED_BILLING_UNSCOPED");
        assertThatThrownBy(()->queries.offers(actor,site,customer.id(),active.productId())).hasMessage("ISOLATED_BILLING_UNSCOPED");
        TestClock.set(utc.plusSeconds(120));assertThat(tx.required(()->actions.consume(site,customer.id(),account.id(),active.id(),r.operationId(),"FAKE_CONFIRM_PAYMENT","expired-capability",bean(Clock.class).instant()))).isFalse();
        TestClock.set(utc.plus(Duration.ofMinutes(30)));assertThatThrownBy(()->sessions.resolveRequired(session.rawToken(),"https://facts.test")).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
    }

    @Test void foreignCustomerUsesOrdinaryTimeAndEqualTimeIsolatedModeStillRefusesUnsupportedFacts() {
        var owned=start();var source=retrieve(owned);var ownedCustomer=customer;var ownedActor=actor;
        billing.own(sub(owned.subscriptionId()),utc);
        var before=sub(owned.subscriptionId());int bills=count("subscription_billing_records"),objects=count("subscription_provider_objects");
        // Fake retrieval also describes recovery authority; that unsupported shape must stay rejected.
        assertThat(source.recoveryObservations()).isNotEmpty();
        assertThatThrownBy(()->apply(owned.subscriptionId(),source,"unsupported-recovery")).hasMessage("ISOLATED_BILLING_UNSUPPORTED");
        var trial=new Snapshot(source.subscriptionReference(),Subscription.Status.TRIALING,source.billingPrice(),source.period(),source.period(),null,source.invoices(),source.operationKey(),true,source.providerRevision(),utc,source.operationObservation());
        assertThatThrownBy(()->apply(owned.subscriptionId(),trial,"unsupported")).hasMessage("ISOLATED_BILLING_UNSUPPORTED");
        assertThat(sub(owned.subscriptionId())).isEqualTo(before);assertThat(count("subscription_billing_records")).isEqualTo(bills);assertThat(count("subscription_provider_objects")).isEqualTo(objects);
        assertThat(count("payments")).isZero();assertThat(count("subscription_events")).isZero();
        apply(owned.subscriptionId(),paidSnapshot(source,utc),"supported-equal-time");
        assertThat(queries.get(actor,site,owned.subscriptionId()).permittedActions()).isEmpty();
        billing.at(utc.plus(Duration.ofDays(35)));
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,utc));freshActor();
        var foreign=start();apply(foreign.subscriptionId(),retrieve(foreign),"foreign");
        var view=queries.get(actor,site,foreign.subscriptionId());assertThat(view.access().evaluatedAt()).isEqualTo(utc);assertThat(view.access().allowed()).isTrue();assertThat(view.permittedActions()).isNotEmpty();
        assertThat(queries.list(new QuerySubscriptionsUseCase.Query(actor,site,customer.id(),null,null,10,null)).items()).hasSize(1);
        assertThatThrownBy(()->queries.get(actor,site,owned.subscriptionId())).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(queries.get(ownedActor,site,owned.subscriptionId()).access().evaluatedAt()).isEqualTo(utc.plus(Duration.ofDays(35)));
        assertThat(billing.sample(new PaymentAccountId(UUID.randomUUID()),site,ownedCustomer.id(),utc).mode()).isEqualTo(SubscriptionBillingTimePort.Mode.ORDINARY);
        assertThat(billing.sample(account.id(),new SiteId(UUID.randomUUID()),ownedCustomer.id(),utc).mode()).isEqualTo(SubscriptionBillingTimePort.Mode.ORDINARY);
    }

    private Snapshot paidSnapshot(Snapshot source,Instant at){
        var period=new Subscription.Period(at,at.plus(Duration.ofDays(30)));
        var invoices=source.invoices().stream().map(i->new Invoice(i.reference(),i.kind(),i.price(),i.previousPrice(),period,i.amountDueMinor(),i.currency(),i.settlement(),i.cashObjects().stream().map(c->new CashObject(c.reference(),c.amountMinor(),c.currency(),c.status(),at)).toList())).toList();
        return new Snapshot(source.subscriptionReference(),source.status(),source.billingPrice(),period,null,null,invoices,source.operationKey(),source.paymentMethodReady(),source.providerRevision(),utc,source.operationObservation());
    }
    private void assertUtcRows(String table,String... columns){for(var column:columns)assertThat(jdbc.queryForList("select "+column+" from "+table,Timestamp.class)).as(table+"."+column).isNotEmpty().allSatisfy(at->assertThat(at.toInstant()).isEqualTo(utc));}

    @TestConfiguration(proxyBeanMethods=false)
    static class BillingConfiguration { @Bean @Primary IsolatedBillingTime isolatedBillingTime(){return new IsolatedBillingTime();} }
    static final class IsolatedBillingTime implements SubscriptionBillingTimePort {
        private record Owner(PaymentAccountId account,SiteId site,CustomerId customer,Instant at) {}
        private Owner owner;
        void clear(){owner=null;}
        void own(Subscription s,Instant at){owner=new Owner(s.paymentAccountId(),s.siteId(),s.customerId(),at);}
        void at(Instant at){owner=new Owner(owner.account(),owner.site(),owner.customer(),at);}
        public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant fallback){return owner!=null&&owner.account().equals(account)&&owner.site().equals(site)&&owner.customer().equals(customer)?new Sample(owner.at(),Mode.ISOLATED):new Sample(fallback,Mode.ORDINARY);}
        public void requireUnscopedAllowed(SiteId site,CustomerId customer){if(owner!=null&&(site==null||owner.site().equals(site)&&(customer==null||owner.customer().equals(customer))))throw new IllegalStateException("ISOLATED_BILLING_UNSCOPED");}
    }
}
