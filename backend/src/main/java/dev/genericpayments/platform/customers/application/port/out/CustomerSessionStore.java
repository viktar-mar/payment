package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.*;
import java.util.*;

public interface CustomerSessionStore {
    List<CustomerSession> findCandidates(List<KeyedDigestPort.DigestValue> digests);
    Optional<CustomerSession> findByIdForUpdate(CustomerSessionId id);
    CustomerSession save(CustomerSession session);
}
