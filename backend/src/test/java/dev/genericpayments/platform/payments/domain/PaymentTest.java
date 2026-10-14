package dev.genericpayments.platform.payments.domain;

import static dev.genericpayments.platform.support.PostgresIntegrationTest.INITIAL_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {
    @Test void donationIsAnExplicitPaymentSource() {
        assertThat(PaymentSource.values()).extracting(Enum::name).contains("DONATION");
    }
    static final PaymentId PAYMENT_ID = new PaymentId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    static final SiteId SITE_ID = new SiteId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    static final PaymentAccountId ACCOUNT_ID = new PaymentAccountId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
    static final PriceId PRICE_ID = new PriceId(UUID.fromString("00000000-0000-0000-0000-000000000004"));

    DonationSnapshot donationSnapshot() {
        return new DonationSnapshot(new DonationCampaignId(UUID.randomUUID()),new DonationCampaignKey("winter-fund"),0,"EUR");
    }
    @Test void donationCreationAndFinancialTransitionsPreserveSnapshotAndNullableCustomer() {
        DonationId id=new DonationId(UUID.randomUUID());
        DonationSnapshot snapshot=donationSnapshot();
        for(UUID customer:new UUID[]{null,UUID.randomUUID()}) {
            Payment payment=Payment.donation(PAYMENT_ID,SITE_ID,customer,ACCOUNT_ID,id,snapshot,Money.of(1234,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME);
            assertThat(payment.source()).isEqualTo(PaymentSource.DONATION);
            assertThat(payment.customerId()).isEqualTo(customer);
            assertThat(payment.donationId()).isEqualTo(id);
            assertThat(payment.donationSnapshot()).isEqualTo(snapshot);
            assertThat(payment.priceId()).isNull(); assertThat(payment.priceSnapshot()).isNull(); assertThat(payment.externalOrderId()).isNull();
            Payment finalState=payment.processing(INITIAL_TIME).succeeded(INITIAL_TIME).refundSucceeded(1234,INITIAL_TIME);
            assertThat(finalState.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(finalState.donationSnapshot()).isEqualTo(snapshot);
            assertThat(finalState.donationId()).isEqualTo(id);
        }
    }
    @Test void donationRejectsMissingContextWrongCurrencyAndTax() {
        DonationId id=new DonationId(UUID.randomUUID()); DonationSnapshot snapshot=donationSnapshot();
        assertThatThrownBy(()->Payment.donation(PAYMENT_ID,SITE_ID,null,ACCOUNT_ID,null,snapshot,Money.of(1234,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->Payment.donation(PAYMENT_ID,SITE_ID,null,ACCOUNT_ID,id,null,Money.of(1234,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->Payment.donation(PAYMENT_ID,SITE_ID,null,ACCOUNT_ID,id,snapshot,Money.of(1234,"USD"),TaxMode.NONE,Map.of(),INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        for(TaxMode tax:TaxMode.values()) if(tax!=TaxMode.NONE)
            assertThatThrownBy(()->Payment.donation(PAYMENT_ID,SITE_ID,null,ACCOUNT_ID,id,snapshot,Money.of(1234,"EUR"),tax,Map.of(),INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
    }
    @Test void allSourceShapesRejectMixedAndMissingContextOnReconstruction() {
        DonationId donation=new DonationId(UUID.randomUUID()); DonationSnapshot snapshot=donationSnapshot();
        PriceSnapshot price=catalogPayment().priceSnapshot();
        for(PaymentSource source:PaymentSource.values()) {
            assertThatThrownBy(()->shape(source,null,null,null,null,null)).isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(()->shape(source,PRICE_ID,price,"order",donation,snapshot)).isInstanceOf(DomainValidationException.class);
        }
        for(PaymentSource source:new PaymentSource[]{PaymentSource.CATALOG,PaymentSource.DYNAMIC}) {
            assertThatThrownBy(()->shape(source,source==PaymentSource.CATALOG?PRICE_ID:null,source==PaymentSource.CATALOG?price:null,source==PaymentSource.DYNAMIC?"order":null,donation,null)).isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(()->shape(source,source==PaymentSource.CATALOG?PRICE_ID:null,source==PaymentSource.CATALOG?price:null,source==PaymentSource.DYNAMIC?"order":null,null,snapshot)).isInstanceOf(DomainValidationException.class);
        }
        assertThatThrownBy(()->shape(PaymentSource.DONATION,PRICE_ID,null,null,donation,snapshot)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->shape(PaymentSource.DONATION,null,price,null,donation,snapshot)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->shape(PaymentSource.DONATION,null,null,"order",donation,snapshot)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->shape(PaymentSource.DONATION,null,null,null,null,snapshot)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->shape(PaymentSource.DONATION,null,null,null,donation,null)).isInstanceOf(DomainValidationException.class);
    }
    Payment shape(PaymentSource source,PriceId priceId,PriceSnapshot price,String order,DonationId donation,DonationSnapshot snapshot) {
        return new Payment(PAYMENT_ID,SITE_ID,null,ACCOUNT_ID,source,priceId,price,order,donation,snapshot,Money.of(2000,"EUR"),TaxMode.NONE,null,PaymentStatus.CREATED,null,0,0,INITIAL_TIME,INITIAL_TIME,Map.of());
    }
    @Test void campaignSnapshotValidatesAllRequiredValuesAndSupportsInitialVersion() {
        DonationSnapshot snapshot=donationSnapshot();
        assertThat(snapshot.version()).isZero();
        assertThatThrownBy(()->new DonationSnapshot(null,snapshot.campaignKey(),0,"EUR")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->new DonationSnapshot(snapshot.campaignId(),null,0,"EUR")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->new DonationSnapshot(snapshot.campaignId(),snapshot.campaignKey(),-1,"EUR")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->new DonationSnapshot(snapshot.campaignId(),snapshot.campaignKey(),0,null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->new DonationSnapshot(snapshot.campaignId(),snapshot.campaignKey(),0,"XYZ")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void catalogCreationRetainsHistoricalPriceAndCopiesCallerMetadata() {
        Map<String, String> metadata = new HashMap<>(Map.of("channel", "demo"));
        PriceSnapshot snapshot = new PriceSnapshot(PRICE_ID, "admission", 2, Money.of(2000, "EUR"), TaxMode.INCLUSIVE);
        Payment payment = Payment.catalog(PAYMENT_ID, SITE_ID, ACCOUNT_ID, snapshot, metadata, INITIAL_TIME);
        metadata.put("channel", "changed");
        PriceSnapshot laterPrice = new PriceSnapshot(PRICE_ID, "admission", 3, Money.of(2500, "EUR"), TaxMode.NONE);

        assertThat(payment.priceSnapshot().version()).isEqualTo(2);
        assertThat(payment.money()).isEqualTo(Money.of(2000, "EUR"));
        assertThat(payment.taxMode()).isEqualTo(TaxMode.INCLUSIVE);
        assertThat(payment.priceSnapshot()).isNotEqualTo(laterPrice);
        assertThat(payment.technicalMetadata()).containsEntry("channel", "demo");
        assertThatThrownBy(() -> payment.technicalMetadata().put("other", "value")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dynamicCreationRequiresExternalOrderAndOmitsCatalogFields() {
        Payment payment = Payment.dynamic(PAYMENT_ID, SITE_ID, ACCOUNT_ID, "order-17", Money.of(1234, "EUR"),
            TaxMode.NONE, Map.of(), INITIAL_TIME);

        assertThat(payment.source()).isEqualTo(PaymentSource.DYNAMIC);
        assertThat(payment.externalOrderId()).isEqualTo("order-17");
        assertThat(payment.priceId()).isNull();
        assertThat(payment.priceSnapshot()).isNull();
        assertThatThrownBy(() -> Payment.dynamic(PAYMENT_ID, SITE_ID, ACCOUNT_ID, " ", Money.of(1234, "EUR"),
            TaxMode.NONE, Map.of(), INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void reconstitutionRejectsMismatchedCatalogMoney() {
        Payment payment = catalogPayment();
        assertThatThrownBy(() -> new Payment(payment.id(), payment.siteId(), null, payment.paymentAccountId(),
            PaymentSource.CATALOG, PRICE_ID, payment.priceSnapshot(), null, Money.of(999, "EUR"), TaxMode.NONE,
            null, PaymentStatus.CREATED, null, 0, 0, INITIAL_TIME, INITIAL_TIME, Map.of()))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void priceSnapshotRejectsAbsentIdentityAndNonpositiveVersion() {
        assertThatThrownBy(() -> new PriceSnapshot(null, "ticket", 1, Money.of(2000, "EUR"), TaxMode.NONE))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new PriceSnapshot(PRICE_ID, " ", 1, Money.of(2000, "EUR"), TaxMode.NONE))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new PriceSnapshot(PRICE_ID, "ticket", 0, Money.of(2000, "EUR"), TaxMode.NONE))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void processingAndSuccessReturnNewStateWithoutChangingOriginal() {
        Payment original = catalogPayment().requiresPaymentMethod(INITIAL_TIME);
        Payment processing = original.processing(INITIAL_TIME.plusSeconds(1));
        Payment succeeded = processing.succeeded(INITIAL_TIME.plusSeconds(2));

        assertThat(original.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(processing.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(succeeded.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(succeeded.updatedAt()).isEqualTo(INITIAL_TIME.plusSeconds(2));
        assertThat(succeeded.canStartAttempt()).isFalse();
        assertThat(succeeded.refundableAmountMinor()).isEqualTo(2000);
    }

    @Test
    void recoverableDeclineRequiresAnotherMethodWithoutTerminatingPayment() {
        Payment original = catalogPayment().processing(INITIAL_TIME.plusSeconds(1));
        Payment declined = original.recoverableDecline(INITIAL_TIME.plusSeconds(10));
        assertThat(declined.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(declined.canStartAttempt()).isTrue();
        assertThat(original.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void terminalDeclinePreventsAnotherAttemptOrLaterSuccess() {
        Payment failed = catalogPayment().terminalDecline(INITIAL_TIME.plusSeconds(1));
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.canStartAttempt()).isFalse();
        assertThatThrownBy(() -> failed.succeeded(INITIAL_TIME.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED"})
    void confirmedOrTerminalPaymentCannotRegressOrStartAnAttempt(PaymentStatus status) {
        Payment payment = switch (status) {
            case SUCCEEDED -> succeededPayment();
            case FAILED -> catalogPayment().terminalDecline(INITIAL_TIME);
            case CANCELLED -> catalogPayment().cancelled(INITIAL_TIME);
            case PARTIALLY_REFUNDED -> succeededPayment().refundSucceeded(500, INITIAL_TIME);
            case REFUNDED -> succeededPayment().refundSucceeded(2000, INITIAL_TIME);
            default -> throw new AssertionError(status);
        };
        assertThat(payment.canStartAttempt()).isFalse();
        assertThatThrownBy(() -> payment.processing(INITIAL_TIME.plusSeconds(3)))
            .isInstanceOf(DomainValidationException.class).hasMessageContaining("cannot transition");
        assertThatThrownBy(() -> payment.recoverableDecline(INITIAL_TIME.plusSeconds(3)))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void actionAndProcessingRejectOrdinaryRegressionsButAcceptConfirmedSuccess() {
        Payment action = catalogPayment().requiresAction(INITIAL_TIME);
        assertThat(action.canStartAttempt()).isFalse();
        assertThatThrownBy(() -> action.requiresPaymentMethod(INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        Payment processing = action.processing(INITIAL_TIME);
        assertThatThrownBy(() -> processing.requiresAction(INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThat(processing.succeeded(INITIAL_TIME).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void repeatedStateFactsAreNoOpsWithoutChangingTimestamp() {
        Payment succeeded = succeededPayment();
        assertThat(succeeded.succeeded(INITIAL_TIME.plusSeconds(10))).isSameAs(succeeded);
    }

    @Test
    void refundFactsApplyCumulativeConfirmedTotalsOnly() {
        Payment succeeded = succeededPayment();
        Payment partial = succeeded.refundSucceeded(500, INITIAL_TIME.plusSeconds(5));
        Payment full = partial.refundSucceeded(2000, INITIAL_TIME.plusSeconds(6));
        assertThat(partial.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(partial.refundedAmountMinor()).isEqualTo(500);
        assertThat(partial.refundableAmountMinor()).isEqualTo(1500);
        assertThat(full.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(full.refundableAmountMinor()).isZero();
        assertThat(full.canRefund()).isFalse();
        assertThat(partial.refundSucceeded(500, INITIAL_TIME.plusSeconds(7))).isSameAs(partial);
        assertThat(full.refundSucceeded(2000, INITIAL_TIME.plusSeconds(8))).isSameAs(full);
        assertThat(succeeded.refundedAmountMinor()).isZero();
    }

    @Test
    void refundFactsRejectUnpaidOverpaidZeroAndRegressiveTotals() {
        assertThatThrownBy(() -> catalogPayment().refundSucceeded(500, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> succeededPayment().refundSucceeded(0, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> succeededPayment().refundSucceeded(2001, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        Payment partial = succeededPayment().refundSucceeded(500, INITIAL_TIME);
        assertThatThrownBy(() -> partial.refundSucceeded(499, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void transitionsRejectMissingOrRegressiveTimes() {
        assertThatThrownBy(() -> catalogPayment().processing(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> catalogPayment().processing(INITIAL_TIME.minusSeconds(1))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void providerReferenceAttachmentPreservesMoneyAndRejectsReplacement() {
        Payment payment = catalogPayment().withProviderPaymentReference("fake_pi_1", INITIAL_TIME);
        assertThat(payment.providerPaymentReference()).isEqualTo("fake_pi_1");
        assertThat(payment.money()).isEqualTo(Money.of(2000, "EUR"));
        assertThatThrownBy(() -> payment.withProviderPaymentReference("fake_pi_2", INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
    }

    static Payment catalogPayment() {
        return Payment.catalog(PAYMENT_ID, SITE_ID, ACCOUNT_ID,
            new PriceSnapshot(PRICE_ID, "admission", 2, Money.of(2000, "EUR"), TaxMode.NONE), Map.of(), INITIAL_TIME);
    }

    static Payment succeededPayment() {
        return catalogPayment().requiresPaymentMethod(INITIAL_TIME).processing(INITIAL_TIME).succeeded(INITIAL_TIME);
    }
}
