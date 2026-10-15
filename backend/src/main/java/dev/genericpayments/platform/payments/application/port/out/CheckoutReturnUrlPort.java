package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.util.Optional;
public interface CheckoutReturnUrlPort {
    Optional<URI> find(SiteId siteId, URI origin);
}
