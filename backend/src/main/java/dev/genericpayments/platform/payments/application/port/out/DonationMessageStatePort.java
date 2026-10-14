package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
public interface DonationMessageStatePort { Map<UUID,String> states(SiteId site,Set<UUID> ids); }
