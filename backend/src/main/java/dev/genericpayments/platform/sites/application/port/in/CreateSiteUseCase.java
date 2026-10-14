package dev.genericpayments.platform.sites.application.port.in;

import dev.genericpayments.platform.sites.domain.Site;

public interface CreateSiteUseCase {
    Site create(CreateSiteCommand command);
}
