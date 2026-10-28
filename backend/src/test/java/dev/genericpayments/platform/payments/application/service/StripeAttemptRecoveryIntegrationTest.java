package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.domain.*;
import org.junit.jupiter.api.Test;

class StripeAttemptRecoveryIntegrationTest extends StripeInboxFixture {
    @Test void consumedFailureCannotCloseNewClientAttempt(){
        assertThat(ClientAttemptPolicy.isNewFailure("ch_old","ch_old")).isFalse();
        assertThat(ClientAttemptPolicy.isNewFailure("ch_new","ch_old")).isTrue();
        assertThat(ClientAttemptPolicy.isNewFailure(null,"ch_old")).isFalse();
    }
    @Test void reconciliationUsesSameVerifiedFailureConsumptionPolicy(){
        mapped();var attempt=clientAttempt(1,null);original=save.save(original.processing(INITIAL_TIME));
        var lease=transitions.acquire(site,original.id(),"reconcile",java.time.Duration.ofSeconds(30));
        var evidence=new dev.genericpayments.platform.payments.application.model.ProviderEvidence(currentAccount().provider(),account.id(),account.mode(),"acct_expected",original.money(),"pi_owned","ch_decline","ch_decline",false);
        transitions.reconcile(lease,new dev.genericpayments.platform.payments.application.model.ProviderState("pi_owned",dev.genericpayments.platform.payments.application.model.ProviderState.Status.REQUIRES_PAYMENT_METHOD,evidence),"reconcile");
        assertThat(current().status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(attempts.findById(site,attempt.id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(count("provider_charge_receipts")).isEqualTo(1);
    }
    @Test void reconciliationRejectsImmutableRequestChangeAfterLease(){
        mapped();var lease=transitions.acquire(site,original.id(),"changed-tax",java.time.Duration.ofSeconds(30));
        var evidence=new dev.genericpayments.platform.payments.application.model.ProviderEvidence(currentAccount().provider(),account.id(),account.mode(),"acct_expected",original.money(),"pi_owned","ch_paid",null,true);
        jdbc.update("update payments set tax_mode='INCLUSIVE' where id=?",original.id().value());
        assertThatThrownBy(() -> transitions.reconcile(lease,new dev.genericpayments.platform.payments.application.model.ProviderState("pi_owned",dev.genericpayments.platform.payments.application.model.ProviderState.Status.SUCCEEDED,evidence),"changed-tax")).isInstanceOf(RuntimeException.class);
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings="ch_old")
    void consumedFailureCannotCloseNewClientAttemptFinancially(String watermark){
        mapped();var old=clientAttempt(1,null);
        snapshot("processing",null);deliver("evt_processing","payment_intent.processing");
        snapshot("requires_payment_method","ch_old");deliver("evt_failure","payment_intent.payment_failed");
        assertThat(attempts.findById(site,old.id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(current().status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);assertThat(count("provider_charge_receipts")).isEqualTo(1);
        var next=clientAttempt(2,watermark);int journal=count("payment_events");
        snapshot("requires_payment_method","ch_old");deliver("evt_oldrepeated","payment_intent.payment_failed");
        assertThat(attempts.findById(site,next.id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(count("payment_events")).isEqualTo(journal);assertThat(count("provider_charge_receipts")).isEqualTo(1);
        snapshot("requires_payment_method","ch_new");deliver("evt_newfailure","payment_intent.payment_failed");
        assertThat(attempts.findById(site,next.id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(count("provider_charge_receipts")).isEqualTo(2);assertThat(count("payment_attempts")).isEqualTo(2);
    }
    @Test void initialWatermarkAndMissingFailureDoNotCloseAttemptOrRegressAction(){
        mapped();var attempt=clientAttempt(1,"ch_old");
        snapshot("requires_action",null);deliver("evt_action","payment_intent.requires_action");
        snapshot("requires_payment_method","ch_old");deliver("evt_watermark","payment_intent.payment_failed");
        snapshot("requires_payment_method",null);deliver("evt_missingcharge","payment_intent.payment_failed");
        assertThat(current().status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(attempts.findById(site,attempt.id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.REQUIRES_ACTION);
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isEqualTo(1);
    }
}
