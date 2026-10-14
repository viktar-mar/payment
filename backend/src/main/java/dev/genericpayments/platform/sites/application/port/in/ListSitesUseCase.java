package dev.genericpayments.platform.sites.application.port.in;

import dev.genericpayments.platform.sites.domain.Site;
import java.util.List;

public interface ListSitesUseCase {
    List<Site> list();
}
