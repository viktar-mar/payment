package dev.genericpayments.platform.provider.adapter.out.fake;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FakePaymentProviderAdapterTest extends ProviderTestFixture {
    @ParameterizedTest @EnumSource(value=ProviderState.Status.class,names={"SUCCEEDED","FAILED","CANCELLED"})
    void newConfirmationCannotReviveTerminalTruthOrEnqueueSuccess(ProviderState.Status truth) {
        var p=save.save(payment().requiresPaymentMethod(INITIAL_TIME));var req=request(p);
        fake.preparePayment(req);state.setPaymentState(req,truth);
        var before=jdbc.queryForMap("select * from fake_provider_objects");
        var id=new PaymentAttemptId(UUID.randomUUID());
        var attempt=PaymentAttempt.start(id,p.id(),1,"confirm_"+id.value(),"CARD",INITIAL_TIME);
        var confirmation=new ProviderConfirmationRequest(account,p,attempt,PaymentMethod.CARD,attempt.providerOperationKey());
        var enabled=new FakePaymentProviderAdapter(state,new FakePaymentProperties(true));
        var result=enabled.confirmPayment(confirmation);
        assertThat(result).isEqualTo(new ProviderResult.Unknown("fake_pi_"+p.id().value()));
        assertThat(enabled.confirmPayment(confirmation)).isEqualTo(result);
        assertThat(jdbc.queryForMap("select * from fake_provider_objects")).isEqualTo(before);
        assertThat(fake.retrievePayment(ProviderPaymentLookup.forPayment(account,p.id(),result.reference())).status()).isEqualTo(truth);
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(p);
        assertThat(count("provider_inbox")).isZero();
        assertThat(count("fake_provider_operations")).isEqualTo(2);
        var receipt=jdbc.queryForMap("select * from fake_provider_operations where operation_key=?",attempt.providerOperationKey());
        assertThat(receipt.get("result_kind")).isEqualTo("UNKNOWN");
        assertThat(receipt.get("result_reference")).isEqualTo("fake_pi_"+p.id().value());
        enabled.confirmPayment(confirmation);
        assertThat(jdbc.queryForMap("select * from fake_provider_operations where operation_key=?",attempt.providerOperationKey())).isEqualTo(receipt);
        assertThat(count("fake_provider_operations")).isEqualTo(2);assertThat(count("provider_inbox")).isZero();
    }
    @Test void referencesAndSequentialReplayRemainStableAfterProviderTruthChanges() {
        var p=save.save(payment()); var req=request(p);
        var first=fake.preparePayment(req);
        assertThat(first.reference()).isEqualTo("fake_pi_"+p.id().value());
        state.setPaymentState(req,ProviderState.Status.SUCCEEDED);
        assertThat(fake.preparePayment(req)).isEqualTo(first);
        assertThat(fake.retrievePayment(ProviderPaymentLookup.forPayment(account,p.id(),first.reference())).status()).isEqualTo(ProviderState.Status.SUCCEEDED);
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(count("fake_provider_objects")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void concurrentReplayCreatesExactlyOneObjectAndReceipt() throws Exception {
        var p=save.save(payment()); var req=request(p); var barrier=new CyclicBarrier(4);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<ProviderResult>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(() -> {barrier.await(10,TimeUnit.SECONDS);return fake.preparePayment(req);}));
            for(var f:futures) assertThat(f.get(15,TimeUnit.SECONDS).reference()).isEqualTo("fake_pi_"+p.id().value());
        }
        assertThat(count("fake_provider_objects")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(FakeProviderStatePersistenceAdapter.Scenario.class)
    void manualResultsAreDurableAndNeverSynchronousSuccess(FakeProviderStatePersistenceAdapter.Scenario scenario) {
        var p=save.save(payment()); var req=request(p); state.configure(req,scenario);
        var result=fake.preparePayment(req);
        assertThat(result.kind().name()).isEqualTo(scenario.name());
        state.configure(req,FakeProviderStatePersistenceAdapter.Scenario.PROCESSING);
        assertThat(fake.preparePayment(req)).isEqualTo(result);
    }
    @Test void changedFingerprintAndObjectFailClosedWithoutNewReceipt() {
        var p=save.save(payment()); fake.preparePayment(request(p));
        var changed=Payment.dynamic(p.id(),site,account.id(),p.externalOrderId(),dev.genericpayments.platform.catalog.domain.Money.of(2001,"EUR"),p.taxMode(),Map.of(),INITIAL_TIME);
        assertThatThrownBy(() -> fake.preparePayment(request(changed))).isInstanceOf(RuntimeException.class);
        var other=save.save(payment());
        assertThatThrownBy(() -> fake.preparePayment(new ProviderPaymentRequest(account,other,ProviderOperationKeys.prepare(p.id())))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void replayRejectsWrongProviderReferenceEvenWhenItIsSyntacticallySafe() {
        var p=save.save(payment());fake.preparePayment(request(p));
        assertThatThrownBy(() -> fake.preparePayment(request(p.withProviderPaymentReference("fake_pi_wrong",INITIAL_TIME)))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select provider_reference from fake_provider_objects",String.class)).isEqualTo("fake_pi_"+p.id().value());
    }
    @Test void eventsAreNormalizedIdempotentAndCanArriveOutOfOrder() {
        var p=save.save(payment()); var req=request(p); fake.preparePayment(req);
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(INITIAL_TIME.plusSeconds(2));
        state.enqueuePaymentEvent(req,2,ProviderState.Status.SUCCEEDED,INITIAL_TIME.plusSeconds(2));
        state.enqueuePaymentEvent(req,1,ProviderState.Status.PROCESSING,INITIAL_TIME);
        state.enqueuePaymentEvent(req,2,ProviderState.Status.SUCCEEDED,INITIAL_TIME.plusSeconds(2));
        assertThat(count("provider_inbox")).isEqualTo(2);
        var rows=jdbc.queryForList("select * from provider_inbox order by occurred_at");
        assertThat(rows.getFirst().get("event_type")).isEqualTo("PAYMENT_PROCESSING");
        assertThat(rows.getLast().get("event_type")).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(rows.getLast().get("provider_event_id")).isEqualTo("fake_evt_"+p.id().value()+"_2");
        assertThat(rows.getLast().get("payload_hash").toString()).matches("[0-9a-f]{64}");
        String payload="{\"payment_id\":\""+p.id().value()+"\",\"schema_version\":1,\"state\":\"SUCCEEDED\"}";
        assertThat(rows.getLast().get("payload_hash")).isEqualTo(new dev.genericpayments.platform.shared.adapter.out.security.Sha256DigestAdapter().sha256Hex(payload));
        assertThat(rows.getLast().get("provider_type")).isEqualTo("FAKE");
        assertThat(rows.getLast().get("payment_account_id")).isEqualTo(account.id().value());
        assertThat(rows.getLast().get("provider_object_id")).isEqualTo("fake_pi_"+p.id().value());
        assertThat(rows.getLast().get("safe_payload").toString()).contains(p.id().value().toString()).doesNotContain("config-ref");
    }
    @Test void defaultDoesNotEnqueueAndEnabledAutoSuccessOnlyEnqueues() {
        var p=save.save(payment()); fake.preparePayment(request(p));
        var a=PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"confirm-test", "CARD",INITIAL_TIME);
        a=PaymentAttempt.start(a.id(),p.id(),1,ProviderOperationKeys.confirm(a.id()),"CARD",INITIAL_TIME);
        var req=new ProviderConfirmationRequest(account,p,a,PaymentMethod.CARD,a.providerOperationKey());
        assertThat(fake.confirmPayment(req).kind()).isEqualTo(ProviderResult.Kind.PROCESSING);
        assertThat(count("provider_inbox")).isZero();
        var enabled=new FakePaymentProviderAdapter(state,new FakePaymentProperties(true));
        var b=PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),2,"unused","CARD",INITIAL_TIME);
        b=PaymentAttempt.start(b.id(),p.id(),2,ProviderOperationKeys.confirm(b.id()),"CARD",INITIAL_TIME);
        var second=new ProviderConfirmationRequest(account,p,b,PaymentMethod.CARD,b.providerOperationKey());
        assertThat(enabled.confirmPayment(second).kind()).isEqualTo(ProviderResult.Kind.PROCESSING);
        enabled.confirmPayment(second);
        assertThat(count("provider_inbox")).isEqualTo(1);
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CREATED);
    }
    @org.springframework.beans.factory.annotation.Autowired dev.genericpayments.platform.payments.application.port.out.SaveRefundPort refunds;
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void firstRefundRejectsPersistedParentMismatchBeforeAnyWrite(boolean crossSite) {
        var original=save.save(payment().succeeded(INITIAL_TIME));
        var otherSite=site;
        if(crossSite) {
            otherSite=new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID());
            jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,'other-site','Other','ACTIVE',?,'AUTOMATIC',?,?,0)",otherSite.value(),account.id().value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        }
        var other=save.save(Payment.dynamic(new PaymentId(UUID.randomUUID()),otherSite,account.id(),"other-order",original.money(),original.taxMode(),Map.of(),INITIAL_TIME).succeeded(INITIAL_TIME));
        var id=new RefundId(UUID.randomUUID());
        var refund=Refund.request(id,original,dev.genericpayments.platform.catalog.domain.Money.of(500,"EUR"),"refund_"+id.value(),"CUSTOMER_REQUEST",null,INITIAL_TIME);
        refunds.insert(refund);
        var forged=Refund.request(id,other,refund.money(),refund.providerOperationKey(),refund.reasonCode(),null,INITIAL_TIME);
        assertThatThrownBy(() -> fake.requestRefund(new ProviderRefundRequest(account,other,forged,forged.providerOperationKey()))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_provider_objects")).isZero();assertThat(count("fake_provider_operations")).isZero();assertThat(count("provider_inbox")).isZero();
        var correct=new ProviderRefundRequest(account,original,refund,refund.providerOperationKey());
        var result=fake.requestRefund(correct);
        assertThat(result).isEqualTo(new ProviderResult.Accepted("fake_re_"+id.value(),true));
        assertThat(fake.requestRefund(correct)).isEqualTo(result);
        assertThat(fake.retrieveRefund(ProviderRefundLookup.forRefund(account,original.id(),id,result.reference())).status()).isEqualTo(ProviderState.Status.PROCESSING);
        assertThat(count("fake_provider_objects")).isEqualTo(1);assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void refundReferenceReplayAndLookupAreBoundToExactPaymentIdentity() {
        var p=save.save(payment().succeeded(INITIAL_TIME));var id=new RefundId(UUID.randomUUID());
        var r=Refund.request(id,p,dev.genericpayments.platform.catalog.domain.Money.of(500,"EUR"),ProviderOperationKeys.refund(id),"CUSTOMER_REQUEST",null,INITIAL_TIME);
        refunds.insert(r);
        var request=new ProviderRefundRequest(account,p,r,r.providerOperationKey());var result=fake.requestRefund(request);
        assertThat(result.reference()).isEqualTo("fake_re_"+id.value());assertThat(fake.requestRefund(request)).isEqualTo(result);
        assertThat(fake.retrieveRefund(ProviderRefundLookup.forRefund(account,p.id(),id,result.reference())).status()).isEqualTo(ProviderState.Status.PROCESSING);
        assertThatThrownBy(() -> fake.retrieveRefund(ProviderRefundLookup.forRefund(account,payment().id(),id,result.reference()))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        var wrongAccount=dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount.create(new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()),"other-account",account.provider(),account.mode(),"other-external","other-config");
        assertThatThrownBy(() -> fake.retrieveRefund(ProviderRefundLookup.forRefund(wrongAccount,p.id(),id,result.reference()))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(count("fake_provider_objects")).isEqualTo(1);assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void firstRefundRejectsForgedSiteForPersistedParent() {
        var p=save.save(payment().succeeded(INITIAL_TIME));var id=new RefundId(UUID.randomUUID());
        var r=Refund.request(id,p,dev.genericpayments.platform.catalog.domain.Money.of(500,"EUR"),"refund_"+id.value(),"CUSTOMER_REQUEST",null,INITIAL_TIME);
        refunds.insert(r);
        var wrongSite=new Payment(p.id(),new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),p.customerId(),p.paymentAccountId(),p.source(),p.priceId(),p.priceSnapshot(),p.externalOrderId(),p.money(),p.taxMode(),p.taxAmountMinor(),p.status(),p.providerPaymentReference(),p.refundedAmountMinor(),p.version(),p.createdAt(),p.updatedAt(),p.technicalMetadata());
        assertThatThrownBy(() -> fake.requestRefund(new ProviderRefundRequest(account,wrongSite,r,r.providerOperationKey()))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_provider_objects")).isZero();assertThat(count("fake_provider_operations")).isZero();
        assertThat(fake.requestRefund(new ProviderRefundRequest(account,p,r,r.providerOperationKey())).reference()).isEqualTo("fake_re_"+id.value());
    }
    @ParameterizedTest @EnumSource(value=ProviderState.Status.class,names={"SUCCEEDED","FAILED","CANCELLED"})
    void manualTerminalTruthIsLookedUpWithoutChangingPayment(ProviderState.Status truth) {
        var p=save.save(payment());state.setPaymentState(request(p),truth);
        assertThat(fake.retrievePayment(ProviderPaymentLookup.forPayment(account,p.id(),"fake_pi_"+p.id().value())).status()).isEqualTo(truth);
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(p);
    }
    @Test void replayRejectsChangedCustomerAndAccountRoutingFacts() {
        var p=save.save(payment());fake.preparePayment(request(p));
        var changed=new Payment(p.id(),p.siteId(),UUID.randomUUID(),p.paymentAccountId(),p.source(),p.priceId(),p.priceSnapshot(),p.externalOrderId(),p.money(),p.taxMode(),p.taxAmountMinor(),p.status(),p.providerPaymentReference(),p.refundedAmountMinor(),p.version(),p.createdAt(),p.updatedAt(),p.technicalMetadata());
        assertThatThrownBy(() -> fake.preparePayment(request(changed))).isInstanceOf(RuntimeException.class);
        var altered=new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount(account.id(),account.key(),account.provider(),account.mode(),account.status(),"other-external",account.credentialsRef());
        assertThatThrownBy(() -> fake.preparePayment(new ProviderPaymentRequest(altered,p,ProviderOperationKeys.prepare(p.id())))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
}
