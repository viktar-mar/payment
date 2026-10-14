package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.util.Locale;

final class CustomerAuthenticationSupport {
    private CustomerAuthenticationSupport() {}
    static PaymentApplicationException failed() { return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed"); }
    static AuthenticationIntent intent(AuthenticationIntent value) {
        if(value==null) throw failed();
        return value;
    }
    static String origin(String value) {
        try {
            URI uri=URI.create(value);
            if(uri.getScheme()==null || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()) throw failed();
            String scheme=uri.getScheme().toLowerCase(Locale.ROOT),host=uri.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equals(scheme) && !("http".equals(scheme)&&("localhost".equals(host)||"127.0.0.1".equals(host)||"::1".equals(host)))) throw failed();
            int port=uri.getPort();
            if(("https".equals(scheme)&&port==443)||("http".equals(scheme)&&port==80)) port=-1;
            return new URI(scheme,null,host,port,null,null,null).toString();
        } catch(PaymentApplicationException failure) { throw failure; }
        catch(RuntimeException|java.net.URISyntaxException invalid) { throw failed(); }
    }
    static void active(Site site,CustomerAuthenticationMode mode,String normalizedOrigin) {
        active(site,normalizedOrigin);
        if(!site.authenticationModes().contains(AuthenticationMode.valueOf(mode.name()))) throw failed();
    }
    static void active(Site site,String normalizedOrigin) {
        if(site.status()!=SiteStatus.ACTIVE
            || site.allowedOrigins().stream().map(URI::toString).map(CustomerAuthenticationSupport::origin).noneMatch(normalizedOrigin::equals)) throw failed();
    }
    static String reference(dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort.DigestValue value) {
        return value.keyVersion()+":"+value.value();
    }
}
