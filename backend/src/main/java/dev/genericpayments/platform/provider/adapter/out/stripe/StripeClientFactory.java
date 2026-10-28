package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.StripeClient;
import java.util.Objects;

public class StripeClientFactory {
    public StripeClient create(StripeCredentials credentials) {
        return builder(Objects.requireNonNull(credentials)).build();
    }

    /** Test-source subclasses can select their owned loopback fixture; runtime has no URL setting. */
    protected StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
        return StripeClient.builder().setApiKey(credentials.secretKey())
            .setApiBase("https://api.stripe.com")
            .setConnectTimeout(5_000).setReadTimeout(20_000).setMaxNetworkRetries(0)
            .setHttpClient(new StripeSdkHttpClient());
    }
}
