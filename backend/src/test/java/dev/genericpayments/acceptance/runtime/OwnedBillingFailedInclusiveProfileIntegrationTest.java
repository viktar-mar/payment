package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonParser;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.support.StripeBillingFirstRenewalCompositionFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

/** Hosted only. Real CREATE/confirmation/fact/BOUNDARY/READY2/admission/failed producers.
 * Local preparation may compile this class, never initialize its context or execute it. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
@Import(StripeBillingFirstRenewalCompositionFixture.RenewalTimeConfiguration.class)
class OwnedBillingFailedInclusiveProfileIntegrationTest extends OwnedBillingFailedRenewalGraphFixture {
    @Test void genuineOriginalInclusiveProfilePublishesOneDebtAndRetainsOngoingAuthority(){
        profile(2000,"EUR","MONTH","INCLUSIVE",3,null);
        var path=originalToReady2();var originalPayment=jdbc.queryForList("select row_to_json(p)::text as row from payments p");
        var initialBill=jdbc.queryForList("select row_to_json(b)::text as row from subscription_billing_records b");
        var admission=components.failures.admit(path.upstream(),path.finalReady(),path.pending().boundary());
        assertThat(jdbc.queryForObject("select owned_failed_renewal_authority(?)",Boolean.class,admission.id())).isTrue();
        makeRenewal(true);queueRenewal(path.finalReady().observation().frozenTime(),true);
        var failure=components.failureObserver().observe(admission);
        assertThat(http.replies).isEmpty();assertThat(transaction.active()).isFalse();
        assertThat(current().billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);
        assertThat(current().debt().billingRecordId()).isEqualTo(failure.billId());
        assertThat(failure.graceDeadline()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+259200));
        assertThat(failure.period()).isEqualTo(path.pending().boundary().period());
        assertThat(failure.invoice()).isEqualTo("in_renewal");assertThat(failure.paymentIntent()).isEqualTo("pi_renewal");assertThat(failure.allocation()).isEqualTo("inpay_renewal");
        assertThat(jdbc.queryForObject("select owned_failed_renewal_authority(?)",Boolean.class,admission.id()))
            .as("Ongoing V34 authority must survive the real debt projection").isTrue();
        assertThat(jdbc.queryForList("select row_to_json(p)::text as row from payments p")).isEqualTo(originalPayment);
        assertThat(jdbc.queryForList("select row_to_json(b)::text as row from subscription_billing_records b where kind='INITIAL'")).isEqualTo(initialBill);
        assertThat(jdbc.queryForMap("select billed_amount_minor,currency,billed_tax_mode,settlement,provider_invoice_reference from subscription_billing_records where kind='RENEWAL'"))
            .containsEntry("billed_amount_minor",2000L).containsEntry("currency","EUR").containsEntry("billed_tax_mode","INCLUSIVE")
            .containsEntry("settlement","OPEN").containsEntry("provider_invoice_reference","in_renewal");
        assertThat(count("stripe_billing_failed_renewals")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_failures")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("subscription_billing_records")).isEqualTo(2);
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactlyInAnyOrder("PERIOD_PAID","RENEWAL_FAILED");
        assertThat(count("outbox_events")).isEqualTo(3);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(count("stripe_billing_failed_renewal_recoveries")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"amount","currency","interval","grace","tax-code"})
    void otherwiseGenuineMatchingOriginalTermsReachTheActualClosedV34AdmissionGuard(String mismatch){
        profile(mismatch.equals("amount")?2100:2000,mismatch.equals("currency")?"USD":"EUR",mismatch.equals("interval")?"YEAR":"MONTH","INCLUSIVE",mismatch.equals("grace")?2:3,mismatch.equals("tax-code")?"txcd_10000000":null);
        var path=originalToReady2();var before=moneyRows();var subscription=current();int calls=http.calls.size();
        assertThat(receipt.intent().terms()).isEqualTo(subscription.billingPrice());
        assertThat(jdbc.queryForObject("select owned_first_renewal_original(?)",Boolean.class,path.upstream().id())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from owned_first_renewal_chain(?) where ordinal=2",Long.class,path.upstream().id())).isEqualTo(1);
        assertThatThrownBy(()->components.failures.admit(path.upstream(),path.finalReady(),path.pending().boundary()))
            .hasStackTraceContaining("Failed renewal requires genuine unclaimed paid/READY2 lineage");
        assertThat(moneyRows()).isEqualTo(before);assertThat(current()).isEqualTo(subscription);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_failed_renewals")).isZero();assertThat(count("stripe_billing_failed_renewal_failures")).isZero();
        assertThat(count("stripe_billing_failed_renewal_reads")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"initial-invoice","initial-line","renewal-invoice","renewal-line"})
    void sameGrossInclusiveTaxCannotPublishFailureDebtCashEventsOrOutbox(String assessedTax){
        profile(2000,"EUR","MONTH","INCLUSIVE",3,null);var path=originalToReady2();
        var admission=components.failures.admit(path.upstream(),path.finalReady(),path.pending().boundary());
        makeRenewal(true);
        var target=switch(assessedTax){case "initial-invoice"->graph.invoice;case "initial-line"->graph.line;case "renewal-invoice"->renewalInvoice;default->renewalLine;};
        target.add(assessedTax.endsWith("invoice")?"total_taxes":"taxes",JsonParser.parseString("[{\"amount\":5,\"tax_behavior\":\"inclusive\",\"taxable_amount\":1995}]"));
        for(var invoice:java.util.List.of(graph.invoice,renewalInvoice)){
            assertThat(invoice.get("amount_due").getAsLong()).isEqualTo(2000);assertThat(invoice.get("total").getAsLong()).isEqualTo(2000);
        }
        assertThat(current().billingPrice().taxMode().name()).isEqualTo("INCLUSIVE");assertThat(receipt.intent().terms()).isEqualTo(current().billingPrice());
        var before=moneyRows();var subscription=current();int calls=http.calls.size();
        queueRenewal(path.finalReady().observation().frozenTime(),true);
        assertThatThrownBy(()->components.failureObserver().observe(admission))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("FAILED_RENEWAL_UNAVAILABLE"));
        assertThat(moneyRows()).isEqualTo(before);assertThat(current()).isEqualTo(subscription);
        assertThat(count("stripe_billing_failed_renewal_failures")).isZero();assertThat(count("stripe_billing_failed_renewal_publications")).isZero();
        assertThat(count("stripe_billing_failed_renewal_recoveries")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_billing_records where kind='RENEWAL'",Long.class)).isZero();
        assertThat(http.calls.subList(calls,http.calls.size())).hasSize(assessedTax.startsWith("initial")?5:10)
            .allSatisfy(request->assertThat(request.method().name()).isEqualTo("GET"));
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
    }
    @Test void exclusiveTermsRemainClosedAtTheRealOriginalOwnershipGuardBeforePositiveFinancialFacts(){
        profile(2000,"EUR","MONTH","EXCLUSIVE",3,null);
        beginConfirmation();
        assertThat(owned.requireReceipt(billing.id(),current().id())).isEqualTo(receipt);
        int before = http.calls.size();
        openGraph();
        assertThatThrownBy(() -> action.nextAction(scope))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));
        assertThat(action.selectionFailureStage()).isEqualTo("ACTION_GRAPH");
        // The original clock bookend precedes the mapper's local EXCLUSIVE guard.
        assertThat(http.calls.subList(before,http.calls.size())).singleElement().satisfies(request -> {
            assertThat(request.method().name()).isEqualTo("GET");
            assertThat(request.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned");
        });
        assertThat(count("stripe_billing_owned_subscription_receipts")).isOne();
        for(var table:java.util.List.of("subscription_action_capabilities","subscription_confirmation_chains",
                "subscription_confirmation_attempts","stripe_billing_first_renewals","stripe_billing_failed_renewals",
                "stripe_billing_failed_renewal_failures","subscription_billing_records","payments","payment_events","subscription_events","outbox_events","outbox_deliveries"))
            assertThat(count(table)).as("No positive fact from unsupported EXCLUSIVE: %s",table).isZero();
    }
}
