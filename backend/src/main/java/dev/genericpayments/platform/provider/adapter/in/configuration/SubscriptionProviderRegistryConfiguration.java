package dev.genericpayments.platform.provider.adapter.in.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionProviderAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;

@Configuration(proxyBeanMethods=false)
public class SubscriptionProviderRegistryConfiguration {
    @Bean SubscriptionProviderRegistry subscriptionProviderRegistry(ObjectProvider<FakeSubscriptionProviderAdapter> fake,
            ObjectProvider<StripeSubscriptionProviderAdapter> stripe,ObjectProvider<StripeSandboxProperties> properties){
        return account->{
            if(account==null)throw unavailable();
            return switch(account.provider()){
                case FAKE->{var provider=fake.getIfAvailable();if(provider==null)throw unavailable();yield provider;}
                case STRIPE->{var settings=properties.getIfAvailable();if(settings==null||!settings.enabled()||account.mode()!=PaymentAccountMode.SANDBOX)throw unavailable();var provider=stripe.getIfAvailable();if(provider==null)throw unavailable();yield provider;}
            };
        };
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("PROVIDER_CAPABILITY_UNAVAILABLE",409,"Provider capability unavailable");}
}
