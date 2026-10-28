package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.util.List;

/** Administrative provisioning only; runtime checkout reads retain CheckoutReturnUrlPort. */
public interface CheckoutReturnUrlAdminPort {
    record CheckoutReturnUrlPolicy(URI origin, URI returnUrl, long version) {}
    List<CheckoutReturnUrlPolicy> list(SiteId siteId);
    /** -1 creates only if absent; otherwise updates only the exact existing version. */
    boolean compareAndSet(SiteId siteId, URI origin, URI returnUrl, long expectedVersion);
}
