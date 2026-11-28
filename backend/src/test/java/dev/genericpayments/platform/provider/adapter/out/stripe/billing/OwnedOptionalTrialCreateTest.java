package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class OwnedOptionalTrialCreateTest {
    final StripeBillingOwnedSubscriptionFixture f=new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE,2000);
    final PaymentAccount approved=PaymentAccount.create(f.f.account.id(),"stripe-sandbox-default",f.f.account.provider(),f.f.account.mode(),
        OwnedOptionalTrialCreate.APPROVED_ACCOUNT,f.f.account.credentialsRef());
    OwnedOptionalTrialCreate.Authority a=authority(approved);
    private OwnedOptionalTrialCreate.Authority authority(PaymentAccount account) {
        return new OwnedOptionalTrialCreate.Authority(f.f.owner.run(),f.f.owner.intent(),f.f.account.id().value(),
        f.f.site.value(),f.f.customer.value(),f.subscriptionId,f.operationId,f.terms.priceId().value(),f.terms.productId().value(),f.terms.priceKey(),
        account.externalAccountId(),account.credentialsRef(),f.f.ready.reference(),f.f.owner.name(),
        "cus_owned","price_owned","prod_owned",f.operation.providerOperationKey(),f.f.ready.frozenTime(),
        f.f.ready.providerCreated(),f.f.ready.providerDeletesAfter(),f.f.ready.frozenTime()+604800,"a".repeat(64));
    }
    boolean consumed,ack,unknown,revoke;
    final OwnedOptionalTrialCreate.Store store=new OwnedOptionalTrialCreate.Store(){
        public OwnedOptionalTrialCreate.Authority admitted(OwnedOptionalTrialCreate.Scope scope){return a;}
        public boolean claim(OwnedOptionalTrialCreate.Authority authority,UUID claim){
            if(consumed||revoke)return false;
            consumed=true;return true;
        }
        public boolean dispatchAuthorized(OwnedOptionalTrialCreate.Authority authority, UUID claim){return consumed;}
        public boolean finish(UUID id,OwnedOptionalTrialCreate.Created created){
            if(created==null)unknown=true;else ack=true;
            return true;
        }
    };
    OwnedOptionalTrialCreate create(){return new OwnedOptionalTrialCreate(store,f.f.credentials,f.f.clients,account->assertThat(account.equals(approved)).isTrue());}
    void run(){create().create(approved,new OwnedOptionalTrialCreate.Scope(a.run(),a.clockIntent(),a.subscription(),a.createOperation()));}
    long posts(){return f.f.http.calls.stream().filter(req->req.method().name().equals("POST")).count();}
    @Test void anotherSandboxAccountCannotReachProviderVerification(){
        var other=PaymentAccount.create(approved.id(),approved.key(),approved.provider(),approved.mode(),
            "acct_otherSandbox",approved.credentialsRef());
        a=authority(other);
        assertThatThrownBy(()->create().create(other,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(f.f.http.calls).isEmpty();assertThat(consumed).isFalse();
    }
    @Test void originalClockAndPriceProduceOneFixedEndAndNoMethod(){
        OwnedOptionalTrialSdkReplies.queue(f.f.http,a,"");run();assertThat(ack).isTrue();assertThat(posts()).isOne();
        var post=f.f.http.calls.stream().filter(req->req.method().name().equals("POST")).findFirst().orElseThrow();var params=post.params();
        assertThat(params.get("trial_end")).isEqualTo(a.trialEnd());
        assertThat(a.customerReference().equals(params.get("customer"))).isTrue();
        assertThat(params).doesNotContainKeys("default_payment_method","trial_period_days");
        assertThat(params.get("trial_settings").toString()).contains("missing_payment_method=pause");
        assertThat(a.operationKey().equals(post.options().getIdempotencyKey())).isTrue();
        assertThat(post.options().getMaxNetworkRetries()).isZero();
    }
    @ParameterizedTest
    @CsvSource({"foreign customer,false","different price,false","attached method,false","prior subscription,false","lost claim,false",
        "changed trial end,true","wrong behavior,true","lost ack,true","paid invoice,true","allocation,true","foreign line,true"})
    void refusesUnexpectedBindingOrReceipt(String fault, boolean dispatched){
        revoke=fault.equals("lost claim");OwnedOptionalTrialSdkReplies.queue(f.f.http,a,fault);assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
        assertThat(posts()).isEqualTo(dispatched?1:0);assertThat(unknown).isEqualTo(dispatched);assertThat(ack).isFalse();
    }
}
