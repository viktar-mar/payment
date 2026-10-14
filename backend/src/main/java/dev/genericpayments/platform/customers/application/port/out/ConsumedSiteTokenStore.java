package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.ConsumedSiteTokenJti;
import java.util.List;

public interface ConsumedSiteTokenStore { void saveAll(List<ConsumedSiteTokenJti> consumed); }
