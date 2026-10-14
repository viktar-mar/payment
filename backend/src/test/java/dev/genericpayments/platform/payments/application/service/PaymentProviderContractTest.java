package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentProviderContractTest {
    final Instant now=Instant.parse("2026-10-04T00:00:00Z");
    final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"contract-account",PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"external","credentials-reference");
    final Payment p=Payment.dynamic(new PaymentId(UUID.randomUUID()),new SiteId(UUID.randomUUID()),account.id(),"order",Money.of(500,"EUR"),TaxMode.NONE,Map.of("channel","test"),now);
    final PaymentAttemptId attemptId=new PaymentAttemptId(UUID.randomUUID());
    final PaymentAttempt a=PaymentAttempt.start(attemptId,p.id(),1,ProviderOperationKeys.confirm(attemptId),"CARD",now);

    @Test void requestsRejectWrongAccountObjectMethodOperationAndReference() {
        var other=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"another-account",PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"external","ref");
        assertThatThrownBy(() -> new ProviderPaymentRequest(other,p,ProviderOperationKeys.prepare(p.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderPaymentRequest(account.disable(),p,ProviderOperationKeys.prepare(p.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderPaymentRequest(account,p,"prepare_wrong")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderPaymentRequest(account,p.withProviderPaymentReference("unsafe reference",now),ProviderOperationKeys.prepare(p.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderConfirmationRequest(account,p,a,PaymentMethod.PAYPAL,a.providerOperationKey())).isInstanceOf(IllegalArgumentException.class);
        var wrongAttempt=PaymentAttempt.start(attemptId,new PaymentId(UUID.randomUUID()),1,a.providerOperationKey(),"CARD",now);
        assertThatThrownBy(() -> new ProviderConfirmationRequest(account,p,wrongAttempt,PaymentMethod.CARD,a.providerOperationKey())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderConfirmationRequest(account,p.withProviderPaymentReference("ref1",now),a.withProviderIntentReference("ref2",now),PaymentMethod.CARD,a.providerOperationKey())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderPaymentLookup.forPayment(account,p.id(),"secret\nunsafe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderRefundLookup.forRefund(account,p.id(),new RefundId(UUID.randomUUID()),"a".repeat(256))).isInstanceOf(IllegalArgumentException.class);
        var tooLong=new PaymentAccount(account.id(),account.key(),account.provider(),account.mode(),account.status(),"x".repeat(256),account.credentialsRef());
        assertThatThrownBy(() -> new ProviderPaymentRequest(tooLong,p,ProviderOperationKeys.prepare(p.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ProviderPaymentRequest(account,p,ProviderOperationKeys.prepare(p.id())).toString()).doesNotContain("credentials-reference","external","order");
    }
    @Test void refundBoundaryChecksCurrencyAmountParentAndStableKey() {
        var id=new RefundId(UUID.randomUUID());var key=ProviderOperationKeys.refund(id);
        for(var money:List.of(Money.of(501,"EUR"),Money.of(10,"USD"))) {
            var r=new Refund(id,p.id(),money,RefundStatus.PENDING,null,key,"CUSTOMER_REQUEST",null,now,now);
            assertThatThrownBy(() -> new ProviderRefundRequest(account,p,r,key)).isInstanceOf(IllegalArgumentException.class);
        }
        var r=new Refund(id,new PaymentId(UUID.randomUUID()),Money.of(10,"EUR"),RefundStatus.PENDING,null,key,"CUSTOMER_REQUEST",null,now,now);
        assertThatThrownBy(() -> new ProviderRefundRequest(account,p,r,key)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProviderOperationKeys.prepare(p.id())).isEqualTo("prepare_"+p.id().value());
        assertThat(ProviderOperationKeys.confirm(a.id())).isEqualTo("confirm_"+a.id().value());
        assertThat(key).isEqualTo("refund_"+id.value());
    }
    @Test void safeResultsAreClosedAndBoundedWithNoSynchronousSuccess() {
        assertThat(ProviderResult.Kind.values()).extracting(Enum::name).doesNotContain("SUCCEEDED");
        assertThatThrownBy(() -> new ProviderResult.Action(ProviderResult.ActionKind.COMPLETE_VERIFICATION,"unsafe message")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderResult.Action(ProviderResult.ActionKind.COMPLETE_VERIFICATION,"x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderResult.Accepted("x".repeat(256),false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ProviderResult.RequiresAction("ref",new ProviderResult.Action(ProviderResult.ActionKind.COMPLETE_VERIFICATION,"verify")).code()).isEqualTo(ProviderResult.Code.ACTION_REQUIRED);
        assertThat(new ProviderResult.Unknown(null).kind()).isEqualTo(ProviderResult.Kind.UNKNOWN);
        assertThat(new ProviderResult.TemporarilyUnavailable().reference()).isNull();
    }
    @Test void capabilitiesAreImmutableAndStripeNeverFallsBackToFake() {
        var capabilities=PaymentProviderCapabilities.fake();
        assertThat(capabilities.taxModes()).containsExactlyInAnyOrder(TaxMode.NONE,TaxMode.INCLUSIVE);
        assertThat(capabilities.methods()).containsExactlyInAnyOrder(PaymentMethod.values());
        assertThat(capabilities.confirmationMode()).isEqualTo(PaymentProviderCapabilities.ConfirmationMode.SERVER);
        assertThatThrownBy(() -> capabilities.taxModes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> capabilities.require(TaxMode.EXCLUSIVE)).hasMessage("PAYMENT_CAPABILITY_UNAVAILABLE");
        var fake=org.mockito.Mockito.mock(PaymentProviderPort.class);var registry=new PaymentProviderRegistry(Map.of(PaymentProviderType.FAKE,fake));
        assertThat(registry.select(account)).isSameAs(fake);
        var stripe=new PaymentAccount(account.id(),account.key(),PaymentProviderType.STRIPE,account.mode(),account.status(),account.externalAccountId(),account.credentialsRef());
        assertThatThrownBy(() -> registry.select(stripe)).hasMessage("PAYMENT_CAPABILITY_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(fake);
    }
    @Test void readOnlyLookupsCarryValidatedStableRequestKeys() {
        var lookup=ProviderPaymentLookup.forPayment(account,p.id(),"ref");
        assertThat(lookup.operationKey()).isEqualTo("lookup_payment_"+p.id().value());
        assertThatThrownBy(() -> new ProviderPaymentLookup(account,p.id(),"ref","injected")).isInstanceOf(IllegalArgumentException.class);
        var id=new RefundId(UUID.randomUUID());var refund=ProviderRefundLookup.forRefund(account,p.id(),id,"ref");
        assertThat(refund.operationKey()).isEqualTo("lookup_refund_"+id.value());
        assertThatThrownBy(() -> new ProviderRefundLookup(account,p.id(),id,"ref","injected")).isInstanceOf(IllegalArgumentException.class);
    }
}
