package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalMappingTest.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFailedRenewalMappingTest.*;
import static org.assertj.core.api.Assertions.*;

/** Pure actual mapper with the official SDK's in-memory transport; no Spring/SQL/provider access. */
class StripeBillingFailedInclusiveHistoryTest {
    private static StripeBillingOwnedSubscriptionFixture original(TaxMode taxMode){
        var f=new StripeBillingOwnedSubscriptionFixture(taxMode,2000);
        var period=new Subscription.Period(java.time.Instant.ofEpochSecond(1699999900),java.time.Instant.ofEpochSecond(1702678400));
        f.local=new Subscription(f.subscriptionId,f.f.site,f.f.customer,f.terms.productId(),f.f.account.id(),f.terms,f.terms,
            Subscription.Status.ACTIVE,"sub_owned",period,period.end(),null,null,null,null,null,2,f.utc,f.utc.plusSeconds(2),f.utc.plusSeconds(2));
        return f;
    }
    private static void amounts(StripeBillingOwnedSubscriptionFixture f){
        // Existing wire builder's legacy constants, never local terms or observed tax arrays.
        for(int index=0;index<f.f.http.replies.size();index++)replace(f,index,StripeBillingFailedInclusiveHistoryTest::amounts);
    }
    private static void amounts(JsonElement value){
        if(value.isJsonArray()){for(var child:value.getAsJsonArray())amounts(child);}
        else if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()){
            var child=entry.getValue();
            if(child.isJsonPrimitive()&&child.getAsJsonPrimitive().isNumber()&&child.getAsLong()==1000)entry.setValue(new JsonPrimitive(2000));
            else amounts(child);
        }
    }
    @Test void originalInclusiveUnadjustedHistoryHasExactlyTheExistingThirteenGets(){
        for(boolean explicitLineTaxes:new boolean[]{false,true}){
            var f=original(TaxMode.INCLUSIVE);var a=admission(f);var b=boundary(a,f);graph(f);amounts(f);
            if(explicitLineTaxes)replace(f,3,j->first(j).add("taxes",new JsonArray()));
            var result=map(f,a,b);
            assertThat(result.snapshot().invoices()).allSatisfy(i->{assertThat(i.price().taxMode()).isEqualTo(TaxMode.INCLUSIVE);assertThat(i.amountDueMinor()).isEqualTo(2000);});
            assertThat(result.snapshot().invoices().getFirst().cashObjects().getFirst().reference()).isEqualTo("pi_owned");
            assertThat(result.snapshot().invoices().getLast().cashObjects()).isEmpty();
            assertThat(f.f.http.replies).isEmpty();assertThat(f.f.http.calls).hasSize(13)
                .allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"initial-invoice","initial-line","renewal-invoice","renewal-line","shipping","automatic","default-tax","excluding-total"})
    void unchangedInclusiveGrossCannotHideAssessedTaxOrOtherNonAmountAdjustments(String drift){
        var f=original(TaxMode.INCLUSIVE);var a=admission(f);var b=boundary(a,f);graph(f);amounts(f);
        int index=drift.equals("initial-line")?3:drift.equals("renewal-line")?8:2;
        replace(f,index,j->{var target=index==2?j.getAsJsonArray("data").get(drift.equals("renewal-invoice")?1:0).getAsJsonObject():first(j);
            switch(drift){
                case "initial-invoice","renewal-invoice"->target.add("total_taxes",JsonParser.parseString("[{\"amount\":5,\"tax_behavior\":\"inclusive\"}]"));
                case "initial-line","renewal-line"->target.add("taxes",JsonParser.parseString("[{\"amount\":5,\"tax_behavior\":\"inclusive\"}]"));
                case "shipping"->target.addProperty("amount_shipping",5);
                case "automatic"->target.add("automatic_tax",JsonParser.parseString("{\"enabled\":true}"));
                case "default-tax"->target.add("default_tax_rates",JsonParser.parseString("[{\"id\":\"txr_owned\",\"object\":\"tax_rate\"}]"));
                case "excluding-total"->target.addProperty("total_excluding_tax",1995);
                default->throw new AssertionError(drift);
            }
        });
        assertThatThrownBy(()->map(f,a,b)).isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
            e->assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));
        assertThat(f.f.http.calls).hasSize(index==3||index==2&&!drift.equals("renewal-invoice")?4:9)
            .allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
    }
    @Test void inclusiveInitialRetainsItsExistingUnknownOffStripeAndAbsentFlatHistoryTolerance(){
        var f=original(TaxMode.INCLUSIVE);var a=admission(f);var b=boundary(a,f);graph(f);amounts(f);
        replace(f,2,j->j.getAsJsonArray("data").get(0).getAsJsonObject().remove("amount_paid_off_stripe"));
        replace(f,3,j->first(j).remove("subscription"));
        assertThat(map(f,a,b).snapshot().invoices().getFirst().cashObjects().getFirst().reference()).isEqualTo("pi_owned");
        assertThat(f.f.http.calls).hasSize(13);
    }
    @Test void historicalTaxGuardDoesNotChangeLegacyNoneFailureOrInclusiveSuccessfulRenewal(){
        var none=original(TaxMode.NONE);var a=admission(none);var b=boundary(a,none);graph(none);amounts(none);
        replace(none,2,j->j.getAsJsonArray("data").get(0).getAsJsonObject().add("total_taxes",JsonParser.parseString("[{\"amount\":5}]")));
        replace(none,3,j->first(j).add("taxes",JsonParser.parseString("[{\"amount\":5}]")));
        assertThat(map(none,a,b).snapshot().status()).isEqualTo(Subscription.Status.PAST_DUE);
        var success=original(TaxMode.INCLUSIVE);var sa=admission(success);var sb=boundary(sa,success);queueGraph(success,true);amounts(success);
        replace(success,2,j->j.getAsJsonArray("data").get(0).getAsJsonObject().add("total_taxes",JsonParser.parseString("[{\"amount\":5}]")));
        replace(success,3,j->first(j).add("taxes",JsonParser.parseString("[{\"amount\":5}]")));
        assertThat(StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(success.f.clients.create(success.f.credentials.resolve(success.f.account)),success.ordinary(),sa,finalReady(sa,sb),success.utc.plusSeconds(10),sb)).isInstanceOf(dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.MappedPaid.class);
    }
}
