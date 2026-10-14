package dev.genericpayments.platform.provider.adapter.out.fake;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("payment.fake")
public record FakePaymentProperties(boolean autoSuccess){}
