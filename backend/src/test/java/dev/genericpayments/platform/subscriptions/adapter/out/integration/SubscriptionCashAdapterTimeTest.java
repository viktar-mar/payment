package dev.genericpayments.platform.subscriptions.adapter.out.integration;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Standalone inert-port test: no Spring, SQL, provider or Mockito runtime. */
class SubscriptionCashAdapterTimeTest {
    private static final Instant UTC=Instant.parse("2026-10-29T14:35:10Z");

    @ParameterizedTest @ValueSource(ints={-35,0,35})
    void bindingRecordsOperationalTimeWithoutChangingProviderOccurrence(int offsetDays) {
        var s=SubscriptionFixtures.active(UTC.minus(Duration.ofDays(1)),UTC.plus(Duration.ofDays(30)));
        var b=new BillingRecord(UUID.randomUUID(),s.siteId(),s.id(),s.paymentAccountId(),"invoice",BillingRecord.Kind.INITIAL,s.billingPrice(),null,s.currentPeriod(),1000,"EUR",BillingRecord.Settlement.CONFIRMED_CASH,0,UTC.minusSeconds(60),UTC.minusSeconds(60));
        var context=new SubscriptionBillingContext(s.id(),b.id(),SubscriptionBillingContext.Kind.INITIAL,s.productId(),s.billingPrice().priceId(),s.billingPrice().version(),null,null,b.period().start(),b.period().end(),b.providerInvoiceReference());
        var occurred=UTC.plus(Duration.ofDays(offsetDays));
        var command=new RecordSubscriptionCashUseCase.Command(s.siteId(),s.customerId().value(),s.paymentAccountId(),PaymentProviderType.FAKE,context,"cash",s.billingPrice().regularAmount(),s.billingPrice().taxMode(),PaymentStatus.SUCCEEDED,occurred,"fact");
        var imported=new ArrayList<RecordSubscriptionCashUseCase.Command>();var recorded=new ArrayList<Instant>();
        var paymentId=new PaymentId(UUID.randomUUID());
        var adapter=new SubscriptionCashAdapter(port(SubscriptionStore.class,(m,a)->{assertThat(m).isEqualTo("lock");return s;}),port(BillingRecordStore.class,(m,a)->{assertThat(m).isEqualTo("lock");return b;}),c->{imported.add(c);return new RecordSubscriptionCashUseCase.Result(paymentId,imported.size()==1);},
            port(SubscriptionObjectPort.class,(m,a)->{assertThat(m).isEqualTo("bindPayment");assertThat(a[0]).isEqualTo(PaymentProviderType.FAKE);assertThat(a[1]).isSameAs(s);assertThat(a[2]).isEqualTo("cash");assertThat(a[3]).isEqualTo(b.id());assertThat(a[4]).isEqualTo(paymentId);recorded.add((Instant)a[5]);return null;}),Clock.fixed(UTC,ZoneOffset.UTC));
        assertThat(adapter.record(command).newFact()).isTrue();
        assertThat(adapter.record(command).newFact()).isFalse();
        assertThat(imported).containsExactly(command,command);
        assertThat(imported).allSatisfy(c->assertThat(c.occurredAt()).isEqualTo(occurred));
        assertThat(recorded).containsExactly(UTC,UTC);
        var foreign=new RecordSubscriptionCashUseCase.Command(s.siteId(),UUID.randomUUID(),s.paymentAccountId(),PaymentProviderType.FAKE,context,"cash",s.billingPrice().regularAmount(),s.billingPrice().taxMode(),PaymentStatus.SUCCEEDED,occurred,"fact");
        assertThatThrownBy(()->adapter.record(foreign)).isInstanceOf(IllegalArgumentException.class).hasMessage("Cash ownership or billing terms mismatch");
        assertThat(imported).hasSize(2);assertThat(recorded).hasSize(2);
    }
    private static <T>T port(Class<T> type,BiFunction<String,Object[],Object> call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.apply(m.getName(),a)));}
}
