package dev.genericpayments.platform.payments.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort.Position;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentPort;
import dev.genericpayments.platform.payments.adapter.in.configuration.CustomerPaymentHistoryConfiguration;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HmacCustomerPaymentHistoryCursorAdapterTest {
    private static final String KEY_V1="QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String KEY_V2="QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";
    private static final Position POSITION=new Position(
        new SiteId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
        UUID.fromString("20000000-0000-0000-0000-000000000002"),"customer-payments-v1",20,
        Instant.parse("2026-10-10T12:13:14.123456Z"),
        new PaymentId(UUID.fromString("30000000-0000-0000-0000-000000000003")));

    @Test
    void emitsActiveVersionAndAcceptsKnownPreviousVersionDuringRotation() {
        var oldAdapter=adapter("v1",Map.of("v1",KEY_V1));
        String inFlight=oldAdapter.encode(POSITION);
        var rotated=adapter("v2",Map.of("v1",KEY_V1,"v2",KEY_V2));

        assertThat(rotated.decode(inFlight)).isEqualTo(POSITION);
        assertThat(rotated.encode(POSITION)).startsWith("c1.v2.").doesNotContain("=");
    }

    @Test
    void rejectsMalformedTamperedUnknownVersionAndTrailingDataUniformly() {
        var adapter=adapter("v2",Map.of("v1",KEY_V1,"v2",KEY_V2));
        String valid=adapter.encode(POSITION);
        char last=valid.charAt(valid.length()-1);
        for(String invalid:java.util.List.of("garbage",valid+("x".repeat(4097)),valid+".tail",
                valid.replaceFirst("c1\\.","c9."),valid.replaceFirst("v2","v9"),
                valid.substring(0,valid.length()-1)+(last=='A'?"B":"A"))) {
            assertThatThrownBy(() -> adapter.decode(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Customer history cursor");
        }
    }

    @Test
    void configurationRejectsShortMissingAndMalformedKeyMaterialAndRedactsDiagnostics() {
        assertThatThrownBy(() -> new CustomerPaymentHistoryCursorProperties("v1",Map.of("v1","c2hvcnQ=")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Customer history cursor configuration");
        assertThatThrownBy(() -> new CustomerPaymentHistoryCursorProperties("v2",Map.of("v1",KEY_V1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Customer history cursor configuration");
        assertThatThrownBy(() -> new CustomerPaymentHistoryCursorProperties("v1",Map.of("v1","not-base64")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Customer history cursor configuration");
        var properties=new CustomerPaymentHistoryCursorProperties("v2",Map.of("v1",KEY_V1,"v2",KEY_V2));
        assertThat(properties.toString()).isEqualTo("CustomerPaymentHistoryCursorProperties[redacted]")
            .doesNotContain(KEY_V1,KEY_V2);
        assertThat(adapter("v2",Map.of("v2",KEY_V2)).toString())
            .isEqualTo("HmacCustomerPaymentHistoryCursorAdapter[redacted]").doesNotContain(KEY_V2);
    }

    @Test
    void applicationConfigurationFailsWithoutExplicitMaterialAndBindsValidRotatingKeys() {
        var runner=new ApplicationContextRunner().withUserConfiguration(CustomerPaymentHistoryConfiguration.class)
            .withBean(LoadPaymentPort.class,()->org.mockito.Mockito.mock(LoadPaymentPort.class));
        runner.run(context->assertThat(context).hasFailed());
        runner.withPropertyValues("payment.customer-history.cursor.active-key-version=v2",
                "payment.customer-history.cursor.known-keys.v1="+KEY_V1,
                "payment.customer-history.cursor.known-keys.v2="+KEY_V2)
            .run(context->assertThat(context).hasNotFailed()
                .hasSingleBean(dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort.class)
                .hasSingleBean(dev.genericpayments.platform.payments.application.port.in.ListCustomerPaymentsUseCase.class));
    }

    private static HmacCustomerPaymentHistoryCursorAdapter adapter(String active,Map<String,String> keys) {
        return new HmacCustomerPaymentHistoryCursorAdapter(new CustomerPaymentHistoryCursorProperties(active,keys));
    }
}
