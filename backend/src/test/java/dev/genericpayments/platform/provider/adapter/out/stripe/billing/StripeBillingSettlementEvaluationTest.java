package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.Invoice;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.ApiResource;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Exact mapper expression with official SDK values and two inert GET responses only. */
class StripeBillingSettlementEvaluationTest {
    // Splitting zeros into short-circuited getter calls would skip five original argument reads.
    @Test void allSixZeroArgumentsAreReadBeforeFirstNonzeroRefusal() throws Exception {
        var f=new StripeBillingOwnedSubscriptionFixture();f.invoice.addProperty("amount_paid_off_stripe",1);
        var parsed=ApiResource.GSON.fromJson(f.invoice.toString(),Invoice.class);
        var invoice=new CountingInvoice();
        for(var field:Invoice.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())){field.setAccessible(true);field.set(invoice,field.get(parsed));}
        var remote=ApiResource.GSON.fromJson(f.remote.toString(),com.stripe.model.Subscription.class);
        f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.line));f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page());
        var type=Class.forName(StripeBillingSnapshotMapper.class.getName()+"$Read");
        var constructor=type.getDeclaredConstructor(com.stripe.StripeClient.class,StripeBillingSnapshotMapper.Ownership.class,java.time.Instant.class,StripeBillingSnapshotMapper.OwnedInitial.class,StripeBillingSnapshotMapper.OwnedActionFailure.class);
        constructor.setAccessible(true);
        var failure=new StripeBillingSnapshotMapper.OwnedActionFailure();
        var read=constructor.newInstance(f.f.clients.create(null),f.ordinary(),f.utc,new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),failure);
        var prices=type.getDeclaredField("prices");prices.setAccessible(true);
        @SuppressWarnings("unchecked") var mapped=(java.util.Map<String,StripeBillingSnapshotMapper.PublishedPrice>)prices.get(read);
        mapped.put("price_owned",new StripeBillingSnapshotMapper.PublishedPrice(f.terms,"price_owned","prod_owned"));
        var normalize=type.getDeclaredMethod("invoice",Invoice.class,com.stripe.model.Subscription.class,SubscriptionItem.class,dev.genericpayments.platform.subscriptions.domain.Subscription.Period.class);
        normalize.setAccessible(true);
        var result=(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Invoice)normalize.invoke(read,invoice,remote,remote.getItems().getData().getFirst(),null);
        assertThat(result.settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.UNSUPPORTED);
        assertThat(invoice.reads).containsExactly("paidOffStripe","overpaid","starting","ending","preCredit","postCredit");
        assertThat(failure.guard()).as("tentative normalization cannot commit a boundary failure").isNull();
        assertThat(f.f.http.calls).hasSize(2).allMatch(c->c.method().name().equals("GET"));
    }
    static final class CountingInvoice extends Invoice {
        final List<String> reads=new ArrayList<>();
        @Override public Long getAmountPaidOffStripe(){reads.add("paidOffStripe");return super.getAmountPaidOffStripe();}
        @Override public Long getAmountOverpaid(){reads.add("overpaid");return super.getAmountOverpaid();}
        @Override public Long getStartingBalance(){reads.add("starting");return super.getStartingBalance();}
        @Override public Long getEndingBalance(){reads.add("ending");return super.getEndingBalance();}
        @Override public Long getPrePaymentCreditNotesAmount(){reads.add("preCredit");return super.getPrePaymentCreditNotesAmount();}
        @Override public Long getPostPaymentCreditNotesAmount(){reads.add("postCredit");return super.getPostPaymentCreditNotesAmount();}
    }
}
