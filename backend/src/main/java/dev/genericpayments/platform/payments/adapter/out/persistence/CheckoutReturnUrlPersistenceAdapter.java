package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.out.CheckoutReturnUrlPort;
import dev.genericpayments.platform.payments.application.port.out.CheckoutReturnUrlAdminPort;
import dev.genericpayments.platform.payments.application.service.PartnerPaymentRules;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CheckoutReturnUrlPersistenceAdapter implements CheckoutReturnUrlPort, CheckoutReturnUrlAdminPort {
    private final JdbcTemplate jdbc;
    public CheckoutReturnUrlPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public List<CheckoutReturnUrlPolicy> list(SiteId site) {
        return jdbc.query("select origin,return_url,version from checkout_return_urls where site_id=? order by origin",
            (rs,n) -> new CheckoutReturnUrlPolicy(URI.create(rs.getString("origin")),validated(URI.create(rs.getString("origin")),rs.getString("return_url")),rs.getLong("version")),site.value());
    }
    @Override @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean compareAndSet(SiteId site,URI origin,URI returnUrl,long expectedVersion) {
        validated(origin,returnUrl.toString());
        if(expectedVersion == -1) return jdbc.update("insert into checkout_return_urls(site_id,origin,return_url) values (?,?,?) on conflict do nothing",
            site.value(),origin.toString(),returnUrl.toString())==1;
        if(expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) throw unavailable();
        return jdbc.update("update checkout_return_urls set return_url=?,version=version+1 where site_id=? and origin=? and version=?",
            returnUrl.toString(),site.value(),origin.toString(),expectedVersion)==1;
    }
    @Override public Optional<URI> find(SiteId site, URI origin) {
        return jdbc.query("select return_url from checkout_return_urls where site_id=? and origin=?",
            (rs,n) -> validated(origin,rs.getString(1)),site.value(),origin.toString()).stream().findFirst();
    }
    /** Provisioning also uses this validation; never derive authority from forwarding headers. */
    public void save(SiteId site, URI origin, URI returnUrl) {
        validated(origin,returnUrl.toString());
        jdbc.update("""
            insert into checkout_return_urls(site_id,origin,return_url) values (?,?,?)
            on conflict(site_id,origin) do update set return_url=excluded.return_url,version=checkout_return_urls.version+1
            """,site.value(),origin.toString(),returnUrl.toString());
    }
    private static URI validated(URI origin,String value) {
        try {
            var canonical=PartnerPaymentRules.normalizeOrigin(origin,true);
            if(!canonical.toString().equals(origin.toString()))throw unavailable();
            var url=URI.create(value);
            if(value.isBlank()||value.length()>2048||value.codePoints().anyMatch(Character::isISOControl)
                    ||java.net.URLDecoder.decode(value,java.nio.charset.StandardCharsets.UTF_8).codePoints().anyMatch(Character::isISOControl)
                    ||value.toLowerCase(Locale.ROOT).matches(".*%(?:0[0-9a-f]|1[0-9a-f]|7f|25).*"))throw unavailable();
            PartnerPaymentRules.returnUrl(value,canonical);
            if(!"https".equals(url.getScheme())&&!("http".equals(url.getScheme())
                    && Set.of("localhost","127.0.0.1","[::1]").contains(url.getHost())))throw unavailable();
            return url;
        } catch(RuntimeException invalid) { throw unavailable(); }
    }
    private static PaymentApplicationException unavailable() {
        return new PaymentApplicationException("PAYMENT_CAPABILITY_UNAVAILABLE",422,"Payment capability unavailable");
    }
}
