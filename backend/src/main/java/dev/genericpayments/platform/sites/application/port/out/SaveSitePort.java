package dev.genericpayments.platform.sites.application.port.out;

import dev.genericpayments.platform.sites.domain.Site;

public interface SaveSitePort {
    Site save(Site site);
}
