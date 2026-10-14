package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import dev.genericpayments.platform.sites.application.port.out.*;
import dev.genericpayments.platform.sites.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerSessionService implements CustomerSessionResolver,GetCurrentCustomerUseCase,LogoutCustomerSessionUseCase,RevalidateCustomerSessionUseCase {
    private static final Duration ABSOLUTE_TTL=Duration.ofMinutes(30);
    private static final Set<CustomerSessionScope> DEFAULT_SCOPES=Set.of(
        CustomerSessionScope.CUSTOMER_READ,CustomerSessionScope.CHECKOUT_CREATE,CustomerSessionScope.IDENTITY_LINK);
    private final CustomerSessionStore sessions;
    private final CustomerStore customers;
    private final CustomerIdentityStore identities;
    private final LoadSitePort siteLoader;
    private final LockSitePort siteLocks;
    private final KeyedDigestPort digests;
    private final RandomTokenPort random;
    private final Clock clock;
    private final CustomerFeatureScopesPort features;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CustomerTelemetryPort telemetry=CustomerTelemetryPort.NOOP;

    public CustomerSessionService(CustomerSessionStore sessions,CustomerStore customers,CustomerIdentityStore identities,
            LoadSitePort siteLoader,LockSitePort siteLocks,KeyedDigestPort digests,RandomTokenPort random,Clock clock,
            CustomerFeatureScopesPort features) {
        this.sessions=sessions; this.customers=customers; this.identities=identities; this.siteLoader=siteLoader;
        this.siteLocks=siteLocks; this.digests=digests; this.random=random; this.clock=clock; this.features=features;
    }

    @Transactional
    public CustomerSessionCreation issue(SiteId siteId,CustomerId customerId,String origin) {
        try {
            String normalizedOrigin=CustomerAuthenticationSupport.origin(origin);
            Site site=siteLocks.findByIdForUpdate(siteId).orElseThrow(CustomerAuthenticationSupport::failed);
            CustomerAuthenticationSupport.active(site,normalizedOrigin);
            Customer customer=customers.findByIdForUpdate(site.id(),customerId).orElseThrow(CustomerAuthenticationSupport::failed);
            if(customer.status()!=CustomerStatus.ACTIVE) throw CustomerAuthenticationSupport.failed();
            Instant now=clock.instant();
            String rawToken=random.issue(32);
            KeyedDigestPort.DigestValue digest=digests.digest(KeyedDigestPort.Purpose.SDK_SESSION,rawToken);
            var issuedScopes=new HashSet<>(DEFAULT_SCOPES);
            issuedScopes.addAll(features.additionalScopes(site.id()));
            CustomerSession saved=sessions.save(CustomerSession.create(new CustomerSessionId(UUID.randomUUID()),site.id(),customer.id(),
                normalizedOrigin,digest.value(),digest.keyVersion(),Set.copyOf(issuedScopes),now,now.plus(ABSOLUTE_TTL)));
            telemetry.session(CustomerTelemetryPort.SessionOperation.ISSUE);
            return new CustomerSessionCreation(rawToken,context(saved));
        } catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    @Transactional
    public CustomerSessionCreation issue(String siteKey,CustomerId customerId,String origin) {
        try {
            Site site=siteLoader.findByKey(SiteKey.of(siteKey)).orElseThrow(CustomerAuthenticationSupport::failed);
            return issue(site.id(),customerId,origin);
        } catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    @Override @Transactional
    public CustomerSessionContext resolveRequired(String rawToken,String origin) {
        return resolve(rawToken,null,origin,null);
    }

    @Override @Transactional
    public Optional<CustomerSessionContext> resolveOptional(String authorizationHeader,SiteId expectedSiteId,String origin) {
        return resolveOptional(authorizationHeader,expectedSiteId,origin,null);
    }

    @Override @Transactional
    public Optional<CustomerSessionContext> resolveOptional(String authorizationHeader,SiteId expectedSiteId,String origin,
                                                              CustomerSessionScope requiredScope) {
        if(authorizationHeader==null) return Optional.empty();
        try {
            if(!authorizationHeader.startsWith("Bearer ") || authorizationHeader.length()<=7
                || authorizationHeader.indexOf(' ',7)>=0 || authorizationHeader.indexOf(',')>=0)
                throw CustomerAuthenticationSupport.failed();
            return Optional.of(resolve(authorizationHeader.substring(7),Objects.requireNonNull(expectedSiteId,"expected site"),origin,
                requiredScope));
        } catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    private CustomerSessionContext resolve(String rawToken,SiteId expectedSiteId,String origin,CustomerSessionScope requiredScope) {
        try {
            String normalizedOrigin=CustomerAuthenticationSupport.origin(origin);
            if(rawToken==null || !rawToken.matches("[A-Za-z0-9_-]{43}")) throw CustomerAuthenticationSupport.failed();
            List<KeyedDigestPort.DigestValue> candidates=digests.candidates(KeyedDigestPort.Purpose.SDK_SESSION,rawToken);
            List<CustomerSession> stored=sessions.findCandidates(candidates);
            CustomerSession selected=null;
            int matches=0;
            for(KeyedDigestPort.DigestValue candidate:candidates) {
                boolean valid=digests.matches(KeyedDigestPort.Purpose.SDK_SESSION,rawToken,candidate);
                for(CustomerSession session:stored) {
                    boolean same=expectedSiteId==null||expectedSiteId.equals(session.siteId());
                    if(valid&&same&&candidate.value().equals(session.tokenDigest())
                        &&candidate.keyVersion().equals(session.tokenDigestKeyVersion())) { selected=session; matches++; }
                }
            }
            if(matches!=1 || selected==null) throw CustomerAuthenticationSupport.failed();
            CustomerSession locked=lockedSession(context(selected),selected.siteId(),normalizedOrigin,requiredScope);
            if(!sameBinding(selected,locked) || !locked.origin().equals(normalizedOrigin)
                || !digests.matches(KeyedDigestPort.Purpose.SDK_SESSION,rawToken,
                    new KeyedDigestPort.DigestValue(locked.tokenDigest(),locked.tokenDigestKeyVersion())))
                throw CustomerAuthenticationSupport.failed();
            CustomerSession used=sessions.save(locked.use(clock.instant()));
            return context(used);
        } catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CustomerSessionContext revalidate(CustomerSessionContext context,SiteId site,String origin,CustomerSessionScope scope) {
        try { return context(lockedSession(context,site,CustomerAuthenticationSupport.origin(origin),scope)); }
        catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    private CustomerSession lockedSession(CustomerSessionContext supplied,SiteId expectedSite,String origin,CustomerSessionScope scope) {
        if(supplied==null || !supplied.siteId().equals(expectedSite)) throw CustomerAuthenticationSupport.failed();
        Site site=siteLocks.findByIdForUpdate(expectedSite).orElseThrow(CustomerAuthenticationSupport::failed);
        CustomerAuthenticationSupport.active(site,origin);
        Customer customer=customers.findByIdForUpdate(site.id(),supplied.customerId()).orElseThrow(CustomerAuthenticationSupport::failed);
        if(customer.status()!=CustomerStatus.ACTIVE) throw CustomerAuthenticationSupport.failed();
        CustomerSession locked=sessions.findByIdForUpdate(supplied.sessionId()).orElseThrow(CustomerAuthenticationSupport::failed);
        if(!context(locked).equals(supplied) || !locked.origin().equals(origin)) throw CustomerAuthenticationSupport.failed();
        if(scope!=null) context(locked).requireScope(scope);
        if(!clock.instant().isBefore(locked.expiresAt())) telemetry.session(CustomerTelemetryPort.SessionOperation.EXPIRE);
        if(!locked.activeAt(clock.instant())) throw CustomerAuthenticationSupport.failed();
        return locked;
    }

    @Override @Transactional(readOnly=true)
    public CurrentCustomer get(CustomerSessionContext context) {
        try {
            Customer customer=customers.findById(context.siteId(),context.customerId()).orElseThrow(CustomerAuthenticationSupport::failed);
            if(customer.status()!=CustomerStatus.ACTIVE) throw CustomerAuthenticationSupport.failed();
            Set<CustomerAuthenticationMode> modes=new LinkedHashSet<>();
            identities.list(context.siteId(),context.customerId()).forEach(identity -> modes.add(identity.mode()));
            return new CurrentCustomer(customer.id(),customer.status(),
                customer.encryptedEmailRef().isPresent()?Optional.of("***@***"):Optional.empty(),modes,context.expiresAt());
        } catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    @Override @Transactional
    public void logout(CustomerSessionContext context) {
        try {
            siteLocks.findByIdForUpdate(context.siteId()).orElseThrow(CustomerAuthenticationSupport::failed);
            customers.findByIdForUpdate(context.siteId(),context.customerId()).orElseThrow(CustomerAuthenticationSupport::failed);
            CustomerSession session=sessions.findByIdForUpdate(context.sessionId()).orElseThrow(CustomerAuthenticationSupport::failed);
            if(!session.siteId().equals(context.siteId()) || !session.customerId().equals(context.customerId()))
                throw CustomerAuthenticationSupport.failed();
            if(session.revokedAt().isEmpty()) {
                sessions.save(session.revoke(clock.instant(),CustomerSession.RevocationReason.LOGOUT));
                telemetry.session(CustomerTelemetryPort.SessionOperation.REVOKE);
            }
        } catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }

    private static boolean sameBinding(CustomerSession left,CustomerSession right) {
        return left.id().equals(right.id())&&left.siteId().equals(right.siteId())&&left.customerId().equals(right.customerId())
            &&left.origin().equals(right.origin())&&left.tokenDigest().equals(right.tokenDigest())
            &&left.tokenDigestKeyVersion().equals(right.tokenDigestKeyVersion())&&left.scopes().equals(right.scopes())
            &&left.issuedAt().equals(right.issuedAt())&&left.expiresAt().equals(right.expiresAt());
    }
    private static CustomerSessionContext context(CustomerSession session) {
        return new CustomerSessionContext(session.id(),session.siteId(),session.customerId(),session.origin(),session.scopes(),session.expiresAt());
    }
}
