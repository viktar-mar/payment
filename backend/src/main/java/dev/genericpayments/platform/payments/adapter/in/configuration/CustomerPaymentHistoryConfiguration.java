package dev.genericpayments.platform.payments.adapter.in.configuration;

import dev.genericpayments.platform.payments.adapter.out.security.*;
import dev.genericpayments.platform.payments.application.port.in.ListCustomerPaymentsUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.CustomerPaymentHistoryService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(CustomerPaymentHistoryCursorProperties.class)
public class CustomerPaymentHistoryConfiguration {
    @Bean CustomerPaymentHistoryCursorPort customerPaymentHistoryCursorPort(CustomerPaymentHistoryCursorProperties properties) {
        return new HmacCustomerPaymentHistoryCursorAdapter(properties);
    }
    @Bean ListCustomerPaymentsUseCase customerPaymentHistoryService(LoadPaymentPort payments,
            CustomerPaymentHistoryCursorPort cursors) {
        return new CustomerPaymentHistoryService(payments,cursors);
    }
}
