package dev.genericpayments.platform.provider.adapter.out.fake;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class FakeSubscriptionProviderIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SubscriptionProviderRegistry registry;
    @Autowired FakeSubscriptionControlPort controls;
    PaymentAccount account;
    SubscriptionProviderPort provider;
    UUID id;
    final SiteId site=new SiteId(UUID.randomUUID());
    final CustomerId customer=new CustomerId(UUID.randomUUID());
    final ProductId product=new ProductId(UUID.randomUUID());
    @BeforeEach void prepare() {
        clearDatabase();account=account("subscription-fake");id=UUID.randomUUID();provider=registry.forAccount(account);
    }
    PaymentAccount account(String name) {
        var a=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),name,PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"fake-external","fake-ref");
        jdbc.update("insert into payment_accounts values (?,?,?,?,?,?,?, ?,0)",a.id().value(),a.key(),a.provider().name(),a.mode().name(),a.status().name(),a.externalAccountId(),a.credentialsRef(),java.sql.Timestamp.from(INITIAL_TIME));
        return a;
    }
    SubscriptionPriceTerms price(long amount,int days) {return new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"price-"+amount,1,product,Money.of(amount,"EUR"),BillingInterval.MONTH,days,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);}
    Command command(SubscriptionOperation.Kind kind,SubscriptionPriceTerms current,SubscriptionPriceTerms target,String quote) {
        var op=new SubscriptionOperation(UUID.randomUUID(),site,customer,id,kind,SubscriptionOperation.Status.RESERVED,"customer","digest","fingerprint","op-"+UUID.randomUUID(),0,1,null,null,INITIAL_TIME,INITIAL_TIME,0);
        return new Command(account,op,current,target,kind==SubscriptionOperation.Kind.CREATE?null:snapshot().subscriptionReference(),null,quote,true);
    }
    Snapshot snapshot(){return provider.retrieve(new Lookup(account,id,null,null));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"http://localhost:4173/manage","http://127.0.0.1:4173/manage","http://[::1]:4173/manage","http://external.test/manage","https://user@shop.test/manage","https://shop.test/manage#x","https://shop.test:0/manage","https://shop.test/manage%0a","https://shop.test/manage?subscriptionPortal=simulated","https://shop.test/manage?operationId=1"})
    void defaultProviderRejectsHttpAndMalformedOrMarkedReturns(String url) {
        var terms=price(1000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        var portal=command(SubscriptionOperation.Kind.PORTAL,terms,null,null);
        assertThatIllegalArgumentException().isThrownBy(()->provider.portal(portal,url));
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(1);
    }
    @Test void explicitPaidDecisionOnTrialPriceSurvivesActionRestartAndRejectsChangedDecision() {
        var terms=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"repeat-required",1,product,Money.of(1000,"EUR"),BillingInterval.MONTH,3,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,TaxMode.NONE);
        var original=command(SubscriptionOperation.Kind.CREATE,terms,null,null);
        var paid=new Command(account,original.operation(),terms,null,null,null,null,true,false);
        controls.scenario(account.id(),id,Scenario.REQUIRES_ACTION);
        var reply=provider.create(paid);
        assertThat(reply.action().kind()).isEqualTo(ActionKind.FAKE_CONFIRM_PAYMENT);
        var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        assertThat(fresh.create(paid)).isEqualTo(reply);
        assertThatIllegalStateException().isThrownBy(()->fresh.create(original));
        assertThatIllegalArgumentException().isThrownBy(()->fresh.confirmAction(original,reply.action().secret()));
        fresh.confirmAction(paid,reply.action().secret());
        assertThat(snapshot().trial()).isNull();
        assertThat(snapshot().billingPrice()).isEqualTo(terms);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.ACTIVE);
    }
    @Test void explicitTrialDecisionContinuesOriginalCreateSetupAfterRestart() {
        var terms=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"first-required",1,product,Money.of(1000,"EUR"),BillingInterval.MONTH,3,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,TaxMode.NONE);
        var create=command(SubscriptionOperation.Kind.CREATE,terms,null,null);
        var reply=provider.create(create);
        var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        fresh.confirmAction(create,reply.action().secret());
        var facts=fresh.retrieve(new Lookup(account,id,null,create.operation().providerOperationKey()));
        assertThat(facts.operationObservation().state()).isEqualTo(OperationState.APPLIED);
        assertThat(facts.status()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(facts.invoices()).isEmpty();
    }
    @Test void successIsIndependentPersistentAndDoesNotGrantPlatformAccess() {
        var command=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);
        assertThat(provider.create(command).outcome()).isEqualTo(Outcome.ACCEPTED);
        var facts=snapshot();assertThat(facts.status()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(facts.invoices()).singleElement().satisfies(i->{assertThat(i.amountDueMinor()).isEqualTo(1000);assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);assertThat(i.cashObjects()).singleElement().satisfies(c->assertThat(c.status()).isEqualTo(PaymentStatus.SUCCEEDED));});
        var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        assertThat(fresh.retrieve(new Lookup(account,id,null,null))).isEqualTo(facts);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class)).isPositive();
    }
    @Test void identicalReplayIsImmutableAndChangedBusinessInputsConflict() {
        var command=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);var receipt=provider.create(command);
        controls.scenario(account.id(),id,Scenario.DECLINE);
        assertThat(provider.create(command)).isEqualTo(receipt);
        var changed=new Command(account,command.operation(),price(2000,0),null,null,null,null,true);
        assertThatIllegalStateException().isThrownBy(()->provider.create(changed));
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_invoices",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("update fake_subscription_operations set fingerprint='changed'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void unknownReceiptSurvivesRestartWithoutInventingSettlement() {
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var command=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);
        var receipt=provider.create(command);assertThat(receipt.outcome()).isEqualTo(Outcome.UNKNOWN);
        var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        assertThat(fresh.create(command)).isEqualTo(receipt);
        assertThat(snapshot().invoices().getFirst().settlement()).isEqualTo(SettlementKind.OPEN);
    }
    @Test void declineProcessingAndUnsupportedRetainActualAttemptMeaning() {
        for(var scenario:List.of(Scenario.DECLINE,Scenario.PROCESSING,Scenario.UNSUPPORTED_SETTLEMENT)) {
            id=UUID.randomUUID();controls.scenario(account.id(),id,scenario);provider.create(command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null));
            var invoice=snapshot().invoices().getFirst();assertThat(invoice.settlement()).isNotEqualTo(SettlementKind.CASH);
            if(scenario==Scenario.DECLINE)assertThat(invoice.cashObjects()).extracting(CashObject::status).containsExactly(PaymentStatus.FAILED);
            if(scenario==Scenario.PROCESSING)assertThat(invoice.cashObjects()).extracting(CashObject::status).containsExactly(PaymentStatus.PROCESSING);
            if(scenario==Scenario.UNSUPPORTED_SETTLEMENT)assertThat(invoice.settlement()).isEqualTo(SettlementKind.UNSUPPORTED);
        }
    }
    @Test void actionTokenValidationAndCompletionAreProviderFactsOnly() {
        controls.scenario(account.id(),id,Scenario.REQUIRES_ACTION);var command=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);
        var receipt=provider.create(command);assertThat(receipt.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(snapshot().invoices().getFirst().cashObjects()).extracting(CashObject::status).containsExactly(PaymentStatus.REQUIRES_ACTION);
        assertThatIllegalArgumentException().isThrownBy(()->provider.confirmAction(command,"wrong"));
        assertThat(provider.nextAction(new Lookup(account,id,receipt.reference(),null))).isEqualTo(receipt.action());
        assertThat(provider.confirmAction(command,receipt.action().secret()).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(snapshot().invoices().getFirst().settlement()).isEqualTo(SettlementKind.CASH);
        assertThat(provider.create(command)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions",Integer.class)).isZero();
    }
    @Test void trialEndsPausedWithoutMethodAndBillsAfterSetup() {
        var terms=price(1000,3);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.TRIALING);assertThat(snapshot().invoices()).isEmpty();
        controls.advance(account.id(),INITIAL_TIME.plus(Duration.ofDays(3)));
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.PAUSED);assertThat(snapshot().invoices()).isEmpty();
        var setup=command(SubscriptionOperation.Kind.SETUP,terms,null,null);assertThat(provider.setup(setup).action().kind()).isEqualTo(ActionKind.FAKE_CONFIRM_SETUP);
        controls.completeAction(account.id(),id);assertThat(snapshot().paymentMethodReady()).isTrue();
        provider.resume(command(SubscriptionOperation.Kind.RESUME,terms,null,null));assertThat(snapshot().status()).isEqualTo(Subscription.Status.ACTIVE);
    }
    @Test void trialWithMethodBillsAtExactTrialBoundary() {
        var terms=price(1000,3);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        provider.setup(command(SubscriptionOperation.Kind.SETUP,terms,null,null));controls.completeAction(account.id(),id);
        controls.advance(account.id(),INITIAL_TIME.plus(Duration.ofDays(3)));
        assertThat(snapshot().invoices()).singleElement().satisfies(i->assertThat(i.period().start()).isEqualTo(INITIAL_TIME.plus(Duration.ofDays(3))));
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(clock.instant()).isEqualTo(INITIAL_TIME);
    }
    @Test void scheduledQuoteSurvivesRestartAndUsesOriginalMonthlyAnchor() {
        var jan=Instant.parse("2028-01-31T10:00:00Z");controls.advance(account.id(),jan);
        var current=price(1000,0);var target=price(2000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.NEXT_PERIOD));
        assertThat(quote.effectiveAt()).isEqualTo(Instant.parse("2028-02-29T10:00:00Z"));
        provider.change(command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference()));
        controls.advance(account.id(),Instant.parse("2028-03-31T10:00:00Z"));
        assertThat(snapshot().billingPrice()).isEqualTo(target);
        assertThat(snapshot().invoices()).extracting(i->i.period().start()).containsExactly(jan,Instant.parse("2028-02-29T10:00:00Z"),Instant.parse("2028-03-31T10:00:00Z"));
    }
    @Test void zeroProrationHasNoCashAndNegativeImmediateQuoteIsRejected() {
        var current=price(1000,0);var target=price(2000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        controls.scenario(account.id(),id,Scenario.ZERO_PRORATION);
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.IMMEDIATE));
        assertThat(quote.dueNowMinor()).isZero();provider.change(command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference()));
        assertThat(snapshot().invoices().getLast()).satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.ZERO);assertThat(i.cashObjects()).isEmpty();});
        controls.scenario(account.id(),id,Scenario.SUCCESS);
        var negative=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,target,current,null),current,ChangeTiming.IMMEDIATE));
        assertThat(negative.dueNowMinor()).isNegative();
        assertThatIllegalArgumentException().isThrownBy(()->provider.change(command(SubscriptionOperation.Kind.CHANGE,target,current,negative.reference())));
    }
    @Test void zeroScenarioNeverMakesPositiveRegularInvoiceFree() {
        controls.scenario(account.id(),id,Scenario.ZERO_PRORATION);provider.create(command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null));
        assertThat(snapshot().invoices().getFirst().amountDueMinor()).isEqualTo(1000);
        assertThat(snapshot().invoices().getFirst().settlement()).isEqualTo(SettlementKind.CASH);
    }
    @Test void accountIsolationAppliesToLookupControlsAndQuotes() {
        var current=price(1000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));var first=snapshot();
        var other=account("other-fake");
        assertThatIllegalArgumentException().isThrownBy(()->provider.retrieve(new Lookup(other,id,first.subscriptionReference(),null)));
        controls.advance(other.id(),INITIAL_TIME.plus(Duration.ofDays(100)));assertThat(snapshot()).isEqualTo(first);
        assertThatIllegalArgumentException().isThrownBy(()->controls.completeAction(other.id(),id));
    }
    @Test void clearChangeCancellationWithdrawalAndPortalAreDurable() {
        var current=price(1000,0);var target=price(2000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.NEXT_PERIOD));
        provider.change(command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference()));provider.clearChange(command(SubscriptionOperation.Kind.CLEAR_CHANGE,current,null,null));
        provider.cancel(command(SubscriptionOperation.Kind.CANCEL,current,null,null),false);assertThat(snapshot().cancellationAt()).isEqualTo(snapshot().period().end());
        provider.withdrawCancel(command(SubscriptionOperation.Kind.WITHDRAW_CANCEL,current,null,null));assertThat(snapshot().cancellationAt()).isNull();
        assertThat(provider.portal(command(SubscriptionOperation.Kind.PORTAL,current,null,null),"https://example.test/account").simulated()).isTrue();
        provider.cancel(command(SubscriptionOperation.Kind.CANCEL,current,null,null),true);assertThat(snapshot().status()).isEqualTo(Subscription.Status.CANCELLED);
    }
    @Test void recoverSettlesExistingInvoiceWithoutDuplicateInvoice() {
        var terms=price(1000,0);controls.scenario(account.id(),id,Scenario.DECLINE);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        controls.scenario(account.id(),id,Scenario.SUCCESS);provider.recover(command(SubscriptionOperation.Kind.RECOVER,terms,null,null));
        assertThat(snapshot().invoices()).singleElement().satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);assertThat(i.cashObjects()).extracting(CashObject::status).containsExactly(PaymentStatus.FAILED,PaymentStatus.SUCCEEDED);});
    }
    @Test void declinedProrationPreservesFailedAttemptAndCurrentBillingTerms() {
        var current=price(1000,0);var target=price(2000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.IMMEDIATE));
        controls.scenario(account.id(),id,Scenario.DECLINE);
        assertThat(provider.change(command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference())).outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(snapshot().billingPrice()).isEqualTo(current);
        assertThat(snapshot().invoices()).hasSize(2);
        assertThat(snapshot().invoices().getLast()).satisfies(i->{assertThat(i.kind()).isEqualTo(BillingRecord.Kind.PRORATION);assertThat(i.previousPrice()).isEqualTo(current);assertThat(i.cashObjects()).extracting(CashObject::status).containsExactly(PaymentStatus.FAILED);});
    }
    @Test void immediateIntervalChangeStartsYearFromNextExistingBoundary() {
        controls.advance(account.id(),Instant.parse("2028-01-31T10:00:00Z"));
        var current=price(1000,0);var target=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"yearly",1,product,Money.of(10000,"EUR"),BillingInterval.YEAR,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.IMMEDIATE));
        provider.change(command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference()));
        controls.advance(account.id(),Instant.parse("2028-02-29T10:00:00Z"));
        assertThat(snapshot().period()).isEqualTo(new Subscription.Period(Instant.parse("2028-02-29T10:00:00Z"),Instant.parse("2029-02-28T10:00:00Z")));
    }
    @Test void requiredMethodTrialStartsOnlyAfterSetupAndProviderCreateRejectsUnattachedId() {
        var terms=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"required-trial",1,product,Money.of(1000,"EUR"),BillingInterval.MONTH,3,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,TaxMode.NONE);
        var create=command(SubscriptionOperation.Kind.CREATE,terms,null,null);
        assertThat(provider.create(create).action().kind()).isEqualTo(ActionKind.FAKE_CONFIRM_SETUP);
        assertThat(snapshot().trial()).isNull();controls.completeAction(account.id(),id);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.TRIALING);assertThat(snapshot().invoices()).isEmpty();
        var op=create.operation();var unattached=new SubscriptionOperation(op.id(),op.siteId(),op.customerId(),null,op.kind(),op.status(),op.authorityScope(),op.keyDigest(),op.fingerprint(),op.providerOperationKey(),0,1,null,null,INITIAL_TIME,INITIAL_TIME,0);
        assertThatThrownBy(()->provider.create(new Command(account,unattached,terms,null,null,null,null,true))).isInstanceOf(NullPointerException.class);
    }
    @Test void quoteRejectsChangedTargetAndExpiryButSurvivesNewAdapter() {
        var current=price(1000,0);var target=price(2000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.NEXT_PERIOD));
        assertThatIllegalStateException().isThrownBy(()->provider.change(command(SubscriptionOperation.Kind.CHANGE,current,price(3000,0),quote.reference())));
        var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        assertThat(fresh.change(command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference())).outcome()).isEqualTo(Outcome.ACCEPTED);
        provider.clearChange(command(SubscriptionOperation.Kind.CLEAR_CHANGE,current,null,null));
        var expires=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.NEXT_PERIOD));
        controls.advance(account.id(),INITIAL_TIME.plusSeconds(300));
        assertThatIllegalStateException().isThrownBy(()->provider.change(command(SubscriptionOperation.Kind.CHANGE,current,target,expires.reference())));
    }
    @Test void providerAdvancePreservesLeapAnchorAndScheduledCancellationStopsRenewal() {
        var leap=Instant.parse("2028-02-29T10:00:00Z");controls.advance(account.id(),leap);
        var terms=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"yearly",1,product,Money.of(10000,"EUR"),BillingInterval.YEAR,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        controls.advance(account.id(),Instant.parse("2031-02-28T10:00:00Z"));
        assertThat(snapshot().period().end()).isEqualTo(Instant.parse("2032-02-29T10:00:00Z"));
        provider.cancel(command(SubscriptionOperation.Kind.CANCEL,terms,null,null),false);
        controls.advance(account.id(),Instant.parse("2032-03-01T10:00:00Z"));
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.CANCELLED);assertThat(snapshot().invoices()).hasSize(4);
    }
    @Test void concurrentIdenticalCreateHasOneReceiptAndOneInvoice() throws Exception {
        var create=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);var futures=new ArrayList<java.util.concurrent.Future<Reply>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return provider.create(create);}));start.countDown();
            var first=futures.getFirst().get(15,java.util.concurrent.TimeUnit.SECONDS);
            for(var future:futures)assertThat(future.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(1);
        assertThat(snapshot().invoices()).hasSize(1);
    }
    @Test void freeTrialNeverCreatesPositiveInvoiceUnderAnyScenario() {
        for(var scenario:Scenario.values()) {
            id=UUID.randomUUID();controls.scenario(account.id(),id,scenario);
            provider.create(command(SubscriptionOperation.Kind.CREATE,price(1000,3),null,null));
            assertThat(snapshot().invoices()).as("trial scenario %s",scenario).isEmpty();
        }
    }
    @Test void unknownAndProcessingAdvanceToFactsWhileOriginalReceiptsRemainImmutable() {
        for(var scenario:List.of(Scenario.UNKNOWN,Scenario.PROCESSING)) {
            id=UUID.randomUUID();controls.scenario(account.id(),id,scenario);var create=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);
            var receipt=provider.create(create);var before=snapshot().invoices().getFirst();
            controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),INITIAL_TIME);
            var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
            var facts=fresh.retrieve(new Lookup(account,id,null,create.operation().providerOperationKey()));
            assertThat(facts.status()).isEqualTo(Subscription.Status.ACTIVE);
            assertThat(facts.invoices()).singleElement().satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);assertThat(i.cashObjects()).hasSize(1);if(!before.cashObjects().isEmpty())assertThat(i.cashObjects().getFirst().reference()).isEqualTo(before.cashObjects().getFirst().reference());});
            assertThat(fresh.create(create)).isEqualTo(receipt);
            int events=jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class);
            controls.advance(account.id(),INITIAL_TIME);assertThat(provider.retrieve(new Lookup(account,id,null,create.operation().providerOperationKey()))).isEqualTo(facts);
            assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class)).isEqualTo(events);
        }
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from payments",Integer.class)).isZero();
    }
    @Test void unknownSetupAndCancellationProgressWithoutNewFinancialOperation() {
        var terms=price(1000,3);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var setup=command(SubscriptionOperation.Kind.SETUP,terms,null,null);
        assertThat(provider.setup(setup).outcome()).isEqualTo(Outcome.UNKNOWN);
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),INITIAL_TIME);
        assertThat(provider.nextAction(new Lookup(account,id,null,null))).isNotNull();controls.completeAction(account.id(),id);
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var cancel=command(SubscriptionOperation.Kind.CANCEL,terms,null,null);
        assertThat(provider.cancel(cancel,true).outcome()).isEqualTo(Outcome.UNKNOWN);
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),INITIAL_TIME);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(provider.cancel(cancel,true).outcome()).isEqualTo(Outcome.UNKNOWN);
    }
    @Test void unknownChangeKeepsAuthorizedQuoteAcrossExpiryAndResolvesOnce() {
        var current=price(1000,0);var target=price(2000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.IMMEDIATE));
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var change=command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference());
        assertThat(provider.change(change).outcome()).isEqualTo(Outcome.UNKNOWN);
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),INITIAL_TIME.plusSeconds(600));
        assertThat(snapshot().billingPrice()).isEqualTo(target);assertThat(snapshot().invoices()).hasSize(2);
        assertThat(provider.change(change).outcome()).isEqualTo(Outcome.UNKNOWN);
    }
    @Test void unknownRenewalResolvesThroughAdvanceWithoutAnotherPlatformCommand() {
        var terms=price(1000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        var boundary=snapshot().period().end();controls.scenario(account.id(),id,Scenario.UNKNOWN);controls.advance(account.id(),boundary);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.PAST_DUE);
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),boundary);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(snapshot().invoices()).hasSize(2);assertThat(snapshot().invoices().getLast().cashObjects()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations",Integer.class)).isEqualTo(1);
    }
    @Test void processingRenewalKeepsReferenceAndTerminalFailureNeedsNewAttempt() {
        var terms=price(1000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        var boundary=snapshot().period().end();controls.scenario(account.id(),id,Scenario.PROCESSING);controls.advance(account.id(),boundary);
        var ref=snapshot().invoices().getLast().cashObjects().getFirst().reference();
        controls.scenario(account.id(),id,Scenario.DECLINE);controls.advance(account.id(),boundary);
        assertThat(snapshot().invoices().getLast().cashObjects()).singleElement().satisfies(c->{assertThat(c.status()).isEqualTo(PaymentStatus.FAILED);assertThat(c.reference()).isEqualTo(ref);});
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),boundary);
        assertThat(snapshot().invoices().getLast().cashObjects().getFirst().status()).isEqualTo(PaymentStatus.FAILED);
        provider.recover(command(SubscriptionOperation.Kind.RECOVER,terms,null,null));
        assertThat(snapshot().invoices().getLast().cashObjects()).extracting(CashObject::status).containsExactly(PaymentStatus.FAILED,PaymentStatus.SUCCEEDED);
        assertThat(snapshot().invoices().getLast().cashObjects().getLast().reference()).isNotEqualTo(ref);
    }
    @Test void deferredScheduledChangeMarksOriginalOperationOnlyWhenApplied() {
        for(var scenario:List.of(Scenario.UNKNOWN,Scenario.PROCESSING)) {
            id=UUID.randomUUID();var current=price(1000,0);var target=price(2000,0);
            var create=command(SubscriptionOperation.Kind.CREATE,current,null,null);provider.create(create);
            var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.NEXT_PERIOD));
            controls.scenario(account.id(),id,scenario);var change=command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference());
            var receipt=provider.change(change);
            var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
            assertThat(fresh.retrieve(new Lookup(account,id,null,null)).operationKey()).isEqualTo(create.operation().providerOperationKey());
            controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),snapshot().observedAt());
            assertThat(fresh.retrieve(new Lookup(account,id,null,null)).operationKey()).isEqualTo(change.operation().providerOperationKey());
            assertThat(fresh.change(change)).isEqualTo(receipt);
            assertThat(snapshot().billingPrice()).isEqualTo(current);
            controls.advance(account.id(),quote.effectiveAt());
            assertThat(snapshot().invoices().getLast().price()).isEqualTo(target);
            assertThat(snapshot().invoices().getLast().period().start()).isEqualTo(quote.effectiveAt());
        }
    }
    @Test void deferredClearMarksOriginalOperationOnlyWhenTargetIsActuallyCleared() {
        for(var scenario:List.of(Scenario.UNKNOWN,Scenario.PROCESSING)) {
            id=UUID.randomUUID();var current=price(1000,0);var target=price(2000,0);
            provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
            var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.NEXT_PERIOD));
            var change=command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference());provider.change(change);
            controls.scenario(account.id(),id,scenario);var clear=command(SubscriptionOperation.Kind.CLEAR_CHANGE,current,null,null);
            var receipt=provider.clearChange(clear);
            var fresh=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
            assertThat(fresh.retrieve(new Lookup(account,id,null,null)).operationKey()).isEqualTo(change.operation().providerOperationKey());
            controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),snapshot().observedAt());
            assertThat(fresh.retrieve(new Lookup(account,id,null,null)).operationKey()).isEqualTo(clear.operation().providerOperationKey());
            assertThat(fresh.clearChange(clear)).isEqualTo(receipt);
            provider.portal(command(SubscriptionOperation.Kind.PORTAL,current,null,null),"https://example.test/account");
            assertThat(snapshot().operationKey()).isEqualTo(clear.operation().providerOperationKey());
            controls.advance(account.id(),quote.effectiveAt());
            assertThat(snapshot().invoices().getLast().price()).isEqualTo(current);
        }
    }
    @Test void declinedInvoiceLessCommandDoesNotReplaceAppliedMarker() {
        var current=price(1000,0);var create=command(SubscriptionOperation.Kind.CREATE,current,null,null);provider.create(create);
        controls.scenario(account.id(),id,Scenario.DECLINE);
        provider.cancel(command(SubscriptionOperation.Kind.CANCEL,current,null,null),true);
        assertThat(snapshot().operationKey()).isEqualTo(create.operation().providerOperationKey());
    }
    @Test void immutableReplayIgnoresAccountLifecycleAndCredentialRotation() {
        var create=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);var receipt=provider.create(create);
        var changed=new PaymentAccount(account.id(),account.key(),account.provider(),account.mode(),PaymentAccountStatus.DISABLED,account.externalAccountId(),"rotated-reference");
        assertThat(provider.create(new Command(changed,create.operation(),create.current(),null,null,null,null,true))).isEqualTo(receipt);
    }
    Snapshot observation(Command command) {return provider.retrieve(new Lookup(account,id,null,command.operation().providerOperationKey()));}
    @Test void unknownInvoiceLessDeclineHasDurableOriginalObservation() {
        var terms=price(1000,0);var create=command(SubscriptionOperation.Kind.CREATE,terms,null,null);provider.create(create);
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var cancel=command(SubscriptionOperation.Kind.CANCEL,terms,null,null);
        var receipt=provider.cancel(cancel,true);
        assertThat(observation(cancel).operationObservation().state()).isEqualTo(OperationState.PENDING);
        controls.scenario(account.id(),id,Scenario.DECLINE);controls.advance(account.id(),INITIAL_TIME);
        provider=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        var facts=observation(cancel);
        assertThat(facts.operationObservation()).isEqualTo(new OperationObservation(cancel.operation().providerOperationKey(),OperationState.DECLINED));
        assertThat(facts.operationKey()).isEqualTo(create.operation().providerOperationKey());
        assertThat(provider.cancel(cancel,true)).isEqualTo(receipt);
        int events=jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class);
        controls.advance(account.id(),INITIAL_TIME);assertThat(observation(cancel)).isEqualTo(facts);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class)).isEqualTo(events);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_cash_objects",Integer.class)).isEqualTo(1);
    }
    @Test void originalObservationRequiresPositiveMoneyOrSetupCompletion() {
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var create=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);
        var receipt=provider.create(create);assertThat(observation(create).operationObservation().state()).isEqualTo(OperationState.PENDING);
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),INITIAL_TIME);
        assertThat(observation(create).operationObservation().state()).isEqualTo(OperationState.APPLIED);
        assertThat(provider.create(create)).isEqualTo(receipt);
        var setup=command(SubscriptionOperation.Kind.SETUP,create.current(),null,null);var action=provider.setup(setup);
        assertThat(observation(setup).operationObservation().state()).isEqualTo(OperationState.PENDING);
        provider.confirmAction(setup,action.action().secret());
        assertThat(observation(setup).operationObservation().state()).isEqualTo(OperationState.APPLIED);
        assertThat(observation(setup).operationObservation().operationKey()).isEqualTo(setup.operation().providerOperationKey());
        assertThat(observation(create).operationObservation().state()).isEqualTo(OperationState.APPLIED);
    }
    @Test void observationIsExplicitlyScopedAndNeverLeaksOtherOriginalKeys() {
        var create=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);provider.create(create);
        assertThat(snapshot().operationObservation()).isNull();
        assertThatIllegalArgumentException().isThrownBy(()->provider.retrieve(new Lookup(account,id,null,"missing")));
        var firstId=id;id=UUID.randomUUID();provider.create(command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null));
        assertThatIllegalArgumentException().isThrownBy(()->provider.retrieve(new Lookup(account,id,null,create.operation().providerOperationKey())));
        var other=account("observation-other");
        assertThatIllegalArgumentException().isThrownBy(()->provider.retrieve(new Lookup(other,firstId,null,create.operation().providerOperationKey())));
    }
    @Test void unsupportedSettlementAndRequiredCreateActionRemainPending() {
        controls.scenario(account.id(),id,Scenario.UNSUPPORTED_SETTLEMENT);var create=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);provider.create(create);
        assertThat(observation(create).operationObservation().state()).isEqualTo(OperationState.PENDING);
        id=UUID.randomUUID();var terms=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"required",1,product,Money.of(1000,"EUR"),BillingInterval.MONTH,3,TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,TaxMode.NONE);
        var trial=command(SubscriptionOperation.Kind.CREATE,terms,null,null);provider.create(trial);
        assertThat(observation(trial).operationObservation().state()).isEqualTo(OperationState.PENDING);
        controls.completeAction(account.id(),id);
        assertThat(observation(trial).operationObservation().state()).isEqualTo(OperationState.APPLIED);
    }
    @Test void declinedDeferredPaymentPreservesPriorAppliedMarker() {
        var current=price(1000,0);var target=price(2000,0);var create=command(SubscriptionOperation.Kind.CREATE,current,null,null);provider.create(create);
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.IMMEDIATE));
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var change=command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference());provider.change(change);
        controls.scenario(account.id(),id,Scenario.DECLINE);controls.advance(account.id(),INITIAL_TIME);
        assertThat(observation(change).operationObservation().state()).isEqualTo(OperationState.DECLINED);
        assertThat(snapshot().operationKey()).isEqualTo(create.operation().providerOperationKey());
    }
    @Test void prorationActionSurvivesSuccessfulRenewalBoundaryWithoutPeriodRegression() {
        prorationActionAcrossBoundary(Scenario.SUCCESS);
    }
    @Test void prorationActionSurvivesActionRequiredRenewalBoundaryWithoutReplacement() {
        prorationActionAcrossBoundary(Scenario.REQUIRES_ACTION);
    }
    void prorationActionAcrossBoundary(Scenario renewalScenario) {
        var start=Instant.parse("2028-01-31T10:00:00Z");var boundary=Instant.parse("2028-02-29T10:00:00Z");
        controls.advance(account.id(),start);var current=price(1000,0);var target=price(10000,0);
        provider.create(command(SubscriptionOperation.Kind.CREATE,current,null,null));
        // Cross renewal while this capability is still valid; expiry/rotation is a separate contract.
        controls.advance(account.id(),boundary.minusSeconds(599));
        var quote=provider.preview(new QuoteRequest(command(SubscriptionOperation.Kind.CHANGE,current,target,null),target,ChangeTiming.IMMEDIATE));
        assertThat(quote.dueNowMinor()).isPositive();controls.scenario(account.id(),id,Scenario.REQUIRES_ACTION);
        var change=command(SubscriptionOperation.Kind.CHANGE,current,target,quote.reference());var receipt=provider.change(change);
        var proration=snapshot().invoices().getLast();var originalLookup=new Lookup(account,id,null,change.operation().providerOperationKey());
        controls.scenario(account.id(),id,renewalScenario);controls.advance(account.id(),boundary);
        provider=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        assertThat(provider.nextAction(originalLookup)).isEqualTo(receipt.action());
        var beforeConfirmation=snapshot().period();provider.confirmAction(change,receipt.action().secret());
        assertThat(snapshot().period().start()).isAfterOrEqualTo(beforeConfirmation.start());
        assertThat(snapshot().period().end()).isAfterOrEqualTo(beforeConfirmation.end());
        assertThat(observation(change).operationObservation().state()).isEqualTo(OperationState.APPLIED);
        assertThat(snapshot().invoices().stream().filter(i->i.reference().equals(proration.reference())).findFirst().orElseThrow().cashObjects())
            .singleElement().satisfies(c->{assertThat(c.reference()).isEqualTo(proration.cashObjects().getFirst().reference());assertThat(c.status()).isEqualTo(PaymentStatus.SUCCEEDED);});
        controls.advance(account.id(),boundary);
        assertThat(snapshot().period()).isEqualTo(new Subscription.Period(boundary,Instant.parse("2028-03-31T10:00:00Z")));
        assertThat(snapshot().invoices().stream().filter(i->i.kind()==BillingRecord.Kind.RENEWAL).toList()).singleElement().satisfies(i->{
            assertThat(i.price()).isEqualTo(target);assertThat(i.amountDueMinor()).isEqualTo(10000);
        });
        var after=snapshot();int events=jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class);
        controls.advance(account.id(),boundary);assertThat(snapshot()).isEqualTo(after);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class)).isEqualTo(events);
        assertThat(provider.change(change)).isEqualTo(receipt);
    }
    @Test void autonomousRenewalActionCanBeRecoveredAndConfirmedWithoutAnotherAttempt() {
        autonomousActionRecovery(0,Scenario.REQUIRES_ACTION);
    }
    @Test void firstPostTrialActionCanBeRecoveredWithoutAnotherAttempt() {
        autonomousActionRecovery(3,Scenario.REQUIRES_ACTION);
    }
    @Test void processingRenewalActionKeepsOriginalAttemptThroughRecovery() {
        autonomousActionRecovery(0,Scenario.PROCESSING);
    }
    void autonomousActionRecovery(int trialDays,Scenario boundaryScenario) {
        var terms=price(1000,trialDays);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        if(trialDays>0) {
            var setup=command(SubscriptionOperation.Kind.SETUP,terms,null,null);var setupReply=provider.setup(setup);
            provider.confirmAction(setup,setupReply.action().secret());
        }
        var boundary=snapshot().period().end();controls.scenario(account.id(),id,boundaryScenario);controls.advance(account.id(),boundary);
        if(boundaryScenario==Scenario.PROCESSING) {
            var processing=snapshot().invoices().getLast();
            controls.scenario(account.id(),id,Scenario.REQUIRES_ACTION);controls.advance(account.id(),boundary);
            assertThat(snapshot().invoices().getLast().reference()).isEqualTo(processing.reference());
            assertThat(snapshot().invoices().getLast().cashObjects()).singleElement().satisfies(c->assertThat(c.reference()).isEqualTo(processing.cashObjects().getFirst().reference()));
        }
        var invoice=snapshot().invoices().getLast();var originalAction=provider.nextAction(new Lookup(account,id,null,null));
        var origin=jdbc.queryForObject("select state->>'actionOperationKey' from fake_subscription_objects where subscription_id=?",String.class,id);
        var raw=command(SubscriptionOperation.Kind.RECOVER,terms,null,null);
        var recover=new Command(account,raw.operation(),terms,null,raw.providerSubscriptionReference(),invoice.reference(),null,true);
        var wrongInvoice=new Command(account,raw.operation(),terms,null,raw.providerSubscriptionReference(),"wrong-invoice",null,true);
        assertThatIllegalStateException().isThrownBy(()->provider.recover(wrongInvoice));
        controls.scenario(account.id(),id,Scenario.UNKNOWN);
        var receipt=provider.recover(recover);assertThat(receipt.action()).isEqualTo(originalAction);
        assertThat(observation(recover).operationObservation().state()).isEqualTo(OperationState.PENDING);
        provider=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        assertThat(jdbc.queryForObject("select state->>'actionOperationKey' from fake_subscription_objects where subscription_id=?",String.class,id)).isEqualTo(origin);
        var other=command(SubscriptionOperation.Kind.RECOVER,terms,null,null);
        var otherRecover=new Command(account,other.operation(),terms,null,other.providerSubscriptionReference(),invoice.reference(),null,true);
        assertThatIllegalStateException().isThrownBy(()->provider.recover(otherRecover));
        var lookup=new Lookup(account,id,null,recover.operation().providerOperationKey());
        assertThat(provider.nextAction(lookup)).isEqualTo(originalAction);
        assertThat(provider.confirmAction(recover,originalAction.secret()).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(observation(recover).operationObservation().state()).isEqualTo(OperationState.APPLIED);
        assertThat(snapshot().invoices()).hasSize(trialDays==0?2:1);
        assertThat(snapshot().invoices().getLast().cashObjects()).singleElement().satisfies(c->{assertThat(c.reference()).isEqualTo(invoice.cashObjects().getFirst().reference());assertThat(c.status()).isEqualTo(PaymentStatus.SUCCEEDED);});
        assertThat(provider.recover(recover)).isEqualTo(receipt);
        var after=snapshot();int events=jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class);
        controls.advance(account.id(),boundary);assertThat(snapshot()).isEqualTo(after);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue",Integer.class)).isEqualTo(events);
    }
    @Test void recoveryCannotAdoptAnOriginalPlatformActionResolvedFromUnknown() {
        var terms=price(1000,0);controls.scenario(account.id(),id,Scenario.UNKNOWN);
        var create=command(SubscriptionOperation.Kind.CREATE,terms,null,null);provider.create(create);
        controls.scenario(account.id(),id,Scenario.REQUIRES_ACTION);controls.advance(account.id(),INITIAL_TIME);
        var original=provider.nextAction(new Lookup(account,id,null,create.operation().providerOperationKey()));
        var raw=command(SubscriptionOperation.Kind.RECOVER,terms,null,null);
        var recover=new Command(account,raw.operation(),terms,null,raw.providerSubscriptionReference(),snapshot().invoices().getFirst().reference(),null,true);
        assertThatIllegalStateException().isThrownBy(()->provider.recover(recover));
        assertThat(provider.nextAction(new Lookup(account,id,null,create.operation().providerOperationKey()))).isEqualTo(original);
        provider.confirmAction(create,original.secret());
        assertThat(observation(create).operationObservation().state()).isEqualTo(OperationState.APPLIED);
    }
    @Test void outstandingSetupDoesNotPreventScheduledCancellationAtBoundary() {
        var terms=price(1000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        var boundary=snapshot().period().end();provider.cancel(command(SubscriptionOperation.Kind.CANCEL,terms,null,null),false);
        controls.advance(account.id(),boundary.minusSeconds(600));
        provider.setup(command(SubscriptionOperation.Kind.SETUP,terms,null,null));
        controls.advance(account.id(),boundary);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(snapshot().invoices()).hasSize(1);
        assertThat(provider.nextAction(new Lookup(account,id,null,null))).isNull();
        var after=snapshot();controls.advance(account.id(),boundary);assertThat(snapshot()).isEqualTo(after);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Scenario.class,names={"UNKNOWN","PROCESSING"})
    void unresolvedSetupCannotPostponeConfirmedCancellationAcrossReconstruction(Scenario scenario) {
        var terms=price(1000,0);provider.create(command(SubscriptionOperation.Kind.CREATE,terms,null,null));
        var boundary=snapshot().period().end();var invoices=snapshot().invoices();
        provider.cancel(command(SubscriptionOperation.Kind.CANCEL,terms,null,null),false);
        controls.scenario(account.id(),id,scenario);
        var setup=command(SubscriptionOperation.Kind.SETUP,terms,null,null);var receipt=provider.setup(setup);
        String stored=jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,setup.operation().providerOperationKey());
        controls.advance(account.id(),boundary);
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(observation(setup).operationObservation().state()).isEqualTo(OperationState.DECLINED);
        assertThat(snapshot().invoices()).isEqualTo(invoices);
        var after=snapshot();controls.advance(account.id(),boundary);assertThat(snapshot()).isEqualTo(after);
        provider=new FakeSubscriptionProviderAdapter(new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions));
        controls.scenario(account.id(),id,Scenario.SUCCESS);controls.advance(account.id(),boundary.plusSeconds(1));
        assertThat(snapshot().status()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(observation(setup).operationObservation().state()).isEqualTo(OperationState.DECLINED);
        assertThat(snapshot().invoices()).isEqualTo(invoices);
        assertThat(provider.setup(setup)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,setup.operation().providerOperationKey())).isEqualTo(stored);
    }
    @Test void confirmationSupportsMaximumLengthOriginalOperationKey() {
        controls.scenario(account.id(),id,Scenario.REQUIRES_ACTION);
        var original=command(SubscriptionOperation.Kind.CREATE,price(1000,0),null,null);var o=original.operation();
        var maximum=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),"x".repeat(255),o.expectedRevision(),o.policyVersion(),null,null,o.createdAt(),o.updatedAt(),o.version());
        var create=new Command(account,maximum,original.current(),null,null,null,null,true);
        var receipt=provider.create(create);
        var confirmed=provider.confirmAction(create,receipt.action().secret());
        assertThat(confirmed.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(provider.confirmAction(create,receipt.action().secret())).isEqualTo(confirmed);
    }
}
