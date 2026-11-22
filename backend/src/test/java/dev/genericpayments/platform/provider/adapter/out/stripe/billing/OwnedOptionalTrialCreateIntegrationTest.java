package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL and the real Stripe SDK with a fully in-memory transport. */
class OwnedOptionalTrialCreateIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired StripeCredentialResolver credentials;
    @Autowired StripeClientFactory clients;

    @Test void singleOriginalTrialReceiptIsDurableAcrossSeparateInvocations() {
        var r=reserveOptionalTrial();
        var scope=new OwnedOptionalTrialCreate.Scope(r.owner().run(),r.owner().intent(),
            r.reserved().subscription().id(),r.reserved().operation().id());
        var store=new JdbcOwnedOptionalTrialCreateStore(jdbc);
        var create=OwnedOptionalTrialCreate.withDatabase(jdbc,credentials,clients,verification);
        var a=store.admitted(scope);
        assertThat(a!=null).isTrue();
        http.calls.clear();
        OwnedOptionalTrialSdkReplies.queue(http,a,"");
        create.create(billing,scope);
        int first=http.calls.size();
        assertThat(http.calls.stream().filter(q->q.method().name().equals("POST")).count()).isOne();
        assertThatThrownBy(()->OwnedOptionalTrialCreate.withDatabase(jdbc,credentials,clients,verification).create(billing,scope)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls.size()).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
            select count(*) from stripe_billing_owned_optional_trial_creates r
            join provider_operations p on p.id=r.step_id
            join stripe_subscription_trial_proofs t on t.operation_id=r.create_operation and t.step_id=p.id
            where r.clock_intent=? and r.status='ACKNOWLEDGED' and p.status=r.status and p.provider_reference=r.subscription_reference
              and (t.invoice_reference,t.item_reference,t.subscription_reference,t.trial_start,t.requested_end)
                =(r.invoice_reference,r.item_reference,r.subscription_reference,r.trial_start,r.trial_end)
            """,Integer.class,scope.clockIntent())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from payments where site_id=?",Integer.class,site.value())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_billing_records where subscription_id=?",
            Integer.class,scope.subscription())).isZero();
    }
}
