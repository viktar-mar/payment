package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionProviderContractTest {
    final Instant now=Instant.parse("2028-01-31T10:00:00Z");
    final SubscriptionPriceTerms price=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"monthly",1,new ProductId(UUID.randomUUID()),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
    final Subscription.Period period=new Subscription.Period(now,now.plusSeconds(86400));
    @Test void capabilitiesRedactSecretsAndNavigation() {
        assertThat(new NextAction(ActionKind.FAKE_CONFIRM_PAYMENT,"private-action-value",now).toString()).doesNotContain("private-action-value");
        assertThat(new PortalNavigation("https://fake.test/private-session",now,true).toString()).doesNotContain("private-session");
    }
    @Test void factsCopyTheirCollections() {
        var cash=new ArrayList<CashObject>();cash.add(new CashObject("cash",1000,"EUR",PaymentStatus.SUCCEEDED,now));
        var invoice=new Invoice("invoice",BillingRecord.Kind.INITIAL,price,null,period,1000,"EUR",SettlementKind.CASH,cash);
        cash.clear();assertThat(invoice.cashObjects()).hasSize(1);
        var invoices=new ArrayList<Invoice>();invoices.add(invoice);
        var snapshot=new Snapshot("sub",Subscription.Status.ACTIVE,price,period,null,null,invoices,"op",true,1,now);
        invoices.clear();assertThat(snapshot.invoices()).hasSize(1);
        assertThatThrownBy(()->snapshot.invoices().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void zeroCannotCarryCashAndCashRequiresMatchingSucceededObjects() {
        var failed=new CashObject("failed",1000,"EUR",PaymentStatus.FAILED,now);
        assertThatIllegalArgumentException().isThrownBy(()->new Invoice("invoice",BillingRecord.Kind.INITIAL,price,null,period,1000,"EUR",SettlementKind.CASH,List.of(failed)));
        assertThatIllegalArgumentException().isThrownBy(()->new Invoice("invoice",BillingRecord.Kind.INITIAL,price,null,period,0,"EUR",SettlementKind.ZERO,List.of(failed)));
        assertThatIllegalArgumentException().isThrownBy(()->new CashObject("cash",0,"EUR",PaymentStatus.SUCCEEDED,now));
        assertThatIllegalArgumentException().isThrownBy(()->new Invoice("invoice",BillingRecord.Kind.INITIAL,price,null,period,1000,"EUR",SettlementKind.CASH,List.of(new CashObject("cash",999,"EUR",PaymentStatus.SUCCEEDED,now))));
    }
    @Test void oneProviderCashObjectCannotBeCountedTwice() {
        var cash=new CashObject("same-cash",500,"EUR",PaymentStatus.SUCCEEDED,now);
        assertThatIllegalArgumentException().isThrownBy(()->new Invoice("invoice",BillingRecord.Kind.INITIAL,price,null,period,1000,"EUR",SettlementKind.CASH,List.of(cash,cash)));
    }
}
