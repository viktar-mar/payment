package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.OidcAuthenticationAttempt;
import java.util.*;

public interface AuthenticationAttemptStore {
    Optional<OidcAuthenticationAttempt> find(UUID id);
    Optional<OidcAuthenticationAttempt> findForUpdate(UUID id);
    OidcAuthenticationAttempt save(OidcAuthenticationAttempt attempt);
}
