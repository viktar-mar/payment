package dev.genericpayments.platform.subscriptions.adapter.in.configuration;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.application.service.*;
import org.springframework.context.annotation.*;
@Configuration
public class SubscriptionApplicationConfiguration {
    @Bean CreateSubscriptionUseCase createSubscription(SubscriptionCommandTransaction transactions,SubscriptionProviderRegistry providers){return new CreateSubscriptionService(transactions,providers);}
}
