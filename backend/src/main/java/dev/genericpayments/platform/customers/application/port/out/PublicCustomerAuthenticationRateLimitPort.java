package dev.genericpayments.platform.customers.application.port.out;

import java.util.*;

public interface PublicCustomerAuthenticationRateLimitPort {
    enum Operation { OIDC_BEGIN, OIDC_EXCHANGE, SITE_TOKEN_EXCHANGE, SITE_TOKEN_LINK_PROOF, CUSTOMER_IDENTITY_LINK }
    record Key(UUID siteId,Operation operation) {
        public Key { Objects.requireNonNull(siteId,"site id"); Objects.requireNonNull(operation,"operation"); }
    }
    record Decision(boolean allowed,int retryAfterSeconds) {}
    Decision acquire(Key key);
}
