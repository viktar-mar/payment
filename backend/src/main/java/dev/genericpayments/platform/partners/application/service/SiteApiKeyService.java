package dev.genericpayments.platform.partners.application.service;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.partners.application.port.in.*;
import dev.genericpayments.platform.partners.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
public class SiteApiKeyService implements ManageSiteApiKeysUseCase {
    private static final Pattern FORMAT = Pattern.compile("\\Agpk_([A-Za-z0-9_-]{12})_([A-Za-z0-9_-]{43})\\z");
    private static final Pattern FULL_SHAPE = Pattern.compile("\\Agpk_.{12}_.{43}\\z", Pattern.DOTALL);
    private final SiteApiKeyPort keys;
    private final SiteApiKeyHashPort hashes;
    private final RandomTokenPort random;
    private final LoadSitePort sites;
    private final Clock clock;
    public SiteApiKeyService(SiteApiKeyPort keys, SiteApiKeyHashPort hashes, RandomTokenPort random, LoadSitePort sites, Clock clock) {
        this.keys=keys; this.hashes=hashes; this.random=random; this.sites=sites; this.clock=clock;
    }
    @Override @Transactional public Reveal create(SiteId site, Set<PartnerScope> scopes, Instant expiry) {
        if (sites.findById(site).isEmpty()) throw new PaymentApplicationException("SITE_NOT_AVAILABLE",404,"Site not available");
        if (scopes == null || scopes.isEmpty() || expiry != null && !expiry.isAfter(clock.instant())) throw new DomainValidationException("Invalid Site API key request");
        return issue(site,scopes,expiry,clock.instant());
    }
    private Reveal issue(SiteId site, Set<PartnerScope> scopes, Instant expiry, Instant now) {
        for (int attempt=0;attempt<5;attempt++) {
            var prefix=random.issue(9); var secret=random.issue(32);
            var key = new SiteApiKey(new SiteApiKeyId(UUID.randomUUID()),site,prefix,hashes.hash(secret),scopes,SiteApiKey.Status.ACTIVE,now,expiry,null,null);
            if (keys.insert(key)) return new Reveal(key,"gpk_"+prefix+"_"+secret);
        }
        throw new PaymentApplicationException("SITE_API_KEY_UNAVAILABLE",503,"Site API key unavailable");
    }
    @Override @Transactional public Reveal rotate(SiteId site, SiteApiKeyId id) {
        var old=keys.lock(site,id).orElseThrow(SiteApiKeyService::invalid);
        var now=clock.instant();
        if (!old.active(now)) throw invalid();
        var reveal=issue(site,old.scopes(),old.expiresAt(),now);
        keys.update(old.overlap(now)); return reveal;
    }
    @Override @Transactional public void revoke(SiteId site, SiteApiKeyId id) {
        var old=keys.lock(site,id).orElseThrow(SiteApiKeyService::invalid);
        if (old.status()==SiteApiKey.Status.ACTIVE) keys.update(old.revoke(clock.instant()));
    }
    public AuthenticatedSite authenticate(String credential) {
        if (credential == null) throw invalid();
        var match=FORMAT.matcher(credential);
        if (!match.matches()) {
            if (FULL_SHAPE.matcher(credential).matches()) hashes.matches("invalid-site-api-key",hashes.dummyHash());
            throw invalid();
        }
        var stored=keys.findByPrefix(match.group(1));
        boolean verified=hashes.matches(match.group(2),stored.map(SiteApiKey::keyHash).orElseGet(hashes::dummyHash));
        if (!verified || stored.isEmpty()) throw invalid();
        var key=stored.get();
        if (!key.active(clock.instant()) || sites.findById(key.siteId()).filter(site -> site.status()==SiteStatus.ACTIVE).isEmpty()) throw invalid();
        return new AuthenticatedSite(key.siteId(),key.id(),key.scopes());
    }
    public void touch(SiteApiKeyId id) { keys.touch(id,clock.instant()); }
    public static PaymentApplicationException invalid() { return new PaymentApplicationException("SITE_API_KEY_INVALID",401,"Invalid Site API key"); }
}
