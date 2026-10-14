package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class EmailOtpProviderSelector {
    public Optional<IdentityProviderConfiguration> select(Collection<IdentityProviderConfiguration> providers) {
        if(providers==null) return Optional.empty();
        return providers.stream()
            .filter(provider -> provider.status()==IdentityProviderStatus.ACTIVE
                &&provider.mode()==CustomerAuthenticationMode.EMAIL_OTP)
            .min(Comparator.comparing(IdentityProviderConfiguration::providerKey));
    }
}
