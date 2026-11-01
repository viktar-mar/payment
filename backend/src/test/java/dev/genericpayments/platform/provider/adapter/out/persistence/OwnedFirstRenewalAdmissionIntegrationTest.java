package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalAdmissionService;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Step;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** Hosted PostgreSQL only. Every successful predecessor comes through confirmation and FactService. */
class OwnedFirstRenewalAdmissionIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    private static final String SOURCE="a".repeat(40);
    @Autowired OwnedFirstRenewalStore renewals;
    @Autowired SubscriptionLifecycleLockPort lifecycleLocks;
    @Autowired dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort accountLocks;

    private OwnedFirstRenewalAdmissionService service() {
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,Clock.systemUTC());
    }
    private void genuinePaid() {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var captured=fence();apply(paidObservation(),captured);assertPaid();
    }
    private dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission admitGenuinePaid() {
        var result=new java.util.concurrent.atomic.AtomicReference<dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission>();
        assertThatCode(()->result.set(service().admit(receipt,SOURCE)))
            .as("A genuine consumed confirmation followed by fresh CASH and FactService must admit the exact paid predecessor")
            .doesNotThrowAnyException();
        return result.get();
    }
    @ParameterizedTest @ValueSource(strings={"succeeded","requires_action"})
    void rejectsAssociationWithoutPaidInitial(String result) {
        beginConfirmation();issueAndSelect();confirm(result);
        int calls=http.calls.size();var before=financialState();
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewals")).isZero();
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @Test void admitsOnlyExactPaidPredecessor() {
        genuinePaid();var before=financialState();int calls=http.calls.size();
        var admitted=admitGenuinePaid();
        assertThat(admitted.receipt()).isEqualTo(receipt);
        assertThat(admitted.sourceSha()).isEqualTo(SOURCE);
        assertThat(admitted.subscriptionRevision()).isEqualTo(current().revision());
        assertThat(admitted.bill()).isEqualTo(jdbc.queryForObject("select id from subscription_billing_records",java.util.UUID.class));
        assertThat(admitted.payment()).isEqualTo(jdbc.queryForObject("select id from payments",java.util.UUID.class));
        assertThat(admitted.root()).isEqualTo(jdbc.queryForObject("select id from subscription_action_capabilities",java.util.UUID.class));
        assertThat(service().admit(receipt,SOURCE)).isEqualTo(admitted);
        assertThatThrownBy(()->service().admit(receipt,"b".repeat(40))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewals")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"create","revision","policy","credentials","refunded","period","trial","price"})
    void refusesPaidPredecessorDriftWithoutDispatch(String drift) {
        genuinePaid();
        // Persist genuine admission first: subsequent calls must revalidate, never trust the record.
        var admitted=admitGenuinePaid();
        switch(drift) {
            case "create"->jdbc.update("update subscription_operations set status='UNKNOWN' where id=?",receipt.intent().createOperation());
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "policy"->jdbc.update("update site_subscription_policies set enabled=false where site_id=?",site.value());
            case "credentials"->jdbc.update("update payment_accounts set credentials_ref='changed' where id=?",billing.id().value());
            case "refunded"->jdbc.update("update payments set status='REFUNDED',refunded_amount_minor=amount_minor where id=?",admitted.payment());
            case "period"->jdbc.update("update subscriptions set current_period_end=current_period_end+interval '1 second' where id=?",current().id());
            case "trial"->jdbc.update("update subscriptions set trial_start=current_period_start,trial_end=current_period_end where id=?",current().id());
            case "price"->jdbc.update("update subscriptions set entitled_amount_minor=entitled_amount_minor+1 where id=?",current().id());
        }
        int calls=http.calls.size();var before=financialState();
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @Test void rejectsEvidenceTamper() {
        genuinePaid();var admitted=admitGenuinePaid();
        var step=renewals.prepare(admitted,Step.BOUNDARY,null,java.time.Instant.now());
        for(var sql:java.util.List.of(
                "update stripe_billing_first_renewals set source_sha='"+"b".repeat(40)+"'",
                "update stripe_billing_first_renewals set original_root=gen_random_uuid()",
                "update stripe_billing_first_renewals set fingerprint='"+"b".repeat(64)+"'",
                "delete from stripe_billing_first_renewals",
                "update stripe_billing_first_renewal_steps set target=target+1",
                "update stripe_billing_first_renewal_steps set ordinal=2",
                "update stripe_billing_first_renewal_steps set fingerprint='"+"b".repeat(64)+"'",
                "delete from stripe_billing_first_renewal_steps"))
            assertThatThrownBy(()->jdbc.update(sql)).as(sql).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into stripe_billing_first_renewals select gen_random_uuid(),owned_intent,payment_account_id,site_id,customer_id,subscription_id,source_sha,original_root,original_attempt,initial_bill,bill_version,initial_payment,payment_version,invoice_reference,allocation_reference,payment_intent_reference,subscription_revision,policy_version,fingerprint,admitted_at,stopped_at,stop_reason from stripe_billing_first_renewals"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(renewals.prepare(admitted,Step.BOUNDARY,null,java.time.Instant.now())).isEqualTo(step);
        renewals.stop(admitted,"AUTHORITY_DRIFT",java.time.Instant.now());
        assertThatThrownBy(()->jdbc.update("update stripe_billing_first_renewals set stopped_at=null,stop_reason=null"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
    }
}
