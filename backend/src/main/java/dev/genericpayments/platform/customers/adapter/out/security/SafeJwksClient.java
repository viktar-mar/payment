package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore.PublicJwkSet;
import dev.genericpayments.platform.customers.application.port.out.JwksDiagnosticsPort;
import dev.genericpayments.platform.customers.domain.*;
import java.net.URI;
import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

public final class SafeJwksClient implements JwksDiagnosticsPort {
    private static final ExecutorService NETWORK=Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore NETWORK_SLOTS=new Semaphore(16);
    private final IdentityProviderConfigurationStore store;
    private final Clock clock;
    private final PinnedJwksHttpsTransport transport;
    private final ExecutorService network;
    private final Semaphore networkSlots;
    private final Map<URI,CacheEntry> cache=new HashMap<>();

    public SafeJwksClient(IdentityProviderConfigurationStore store,JwksAddressPolicy policy,Clock clock) {
        this(store,policy,clock,defaultTls());
    }
    public SafeJwksClient(IdentityProviderConfigurationStore store,JwksAddressPolicy policy,Clock clock,SSLContext tls) {
        this(store,policy,clock,tls,NETWORK,NETWORK_SLOTS);
    }
    SafeJwksClient(IdentityProviderConfigurationStore store,JwksAddressPolicy policy,Clock clock,SSLContext tls,
        ExecutorService network,Semaphore networkSlots) {
        this.store=Objects.requireNonNull(store); this.clock=Objects.requireNonNull(clock);
        this.transport=new PinnedJwksHttpsTransport(Objects.requireNonNull(policy),Objects.requireNonNull(tls));
        this.network=Objects.requireNonNull(network); this.networkSlots=Objects.requireNonNull(networkSlots);
    }
    private static SSLContext defaultTls() {
        try { return SSLContext.getDefault(); } catch(Exception unavailable) { throw CustomerAuthenticationFailure.failed(); }
    }
    public static ValidatedPublicJwkSet validateStatic(PublicJwkSet input) { return ValidatedPublicJwkSet.parse(input); }
    @Override public Diagnostics observe(IdentityProviderConfiguration configuration) {
        if(configuration.status()!=IdentityProviderStatus.ACTIVE)
            return new Diagnostics(null,RefreshState.NOT_APPLICABLE,false);
        JwksSource source=switch(configuration.configuration()) {
            case IdentityProviderConfiguration.OidcTokenConfiguration oidc -> oidc.jwksSource();
            case IdentityProviderConfiguration.SiteTokenConfiguration site -> site.jwksSource();
            default -> null;
        };
        if(!(source instanceof JwksSource.Remote remote))
            return new Diagnostics(null,RefreshState.NOT_APPLICABLE,false);
        CacheEntry entry;
        synchronized(cache) { entry=cache.get(remote.uri()); }
        if(entry==null) return new Diagnostics(null,RefreshState.NEVER_ATTEMPTED,false);
        // Refresh owns the entry monitor across network I/O. Admin reads only this immutable publication.
        CacheSnapshot snapshot=entry.diagnostics;
        Instant now=clock.instant();
        boolean expired=snapshot.success()!=null && now.isAfter(snapshot.success().plusSeconds(3600));
        RefreshState state=expired?RefreshState.EXPIRED:snapshot.refreshing()?RefreshState.REFRESHING:
            snapshot.failed()?RefreshState.REFRESH_FAILED:snapshot.success()==null?RefreshState.NEVER_ATTEMPTED:
            !now.isBefore(snapshot.success().plusSeconds(900))?RefreshState.STALE:RefreshState.CURRENT;
        Set<String> allowed=switch(configuration.configuration()) {
            case IdentityProviderConfiguration.OidcTokenConfiguration oidc -> oidc.allowedAlgorithms();
            case IdentityProviderConfiguration.SiteTokenConfiguration site -> site.allowedAlgorithms();
            default -> Set.of();
        };
        boolean compatible=snapshot.success()!=null && !expired &&
            snapshot.algorithms().stream().anyMatch(allowed::contains);
        return new Diagnostics(snapshot.success(),state,compatible);
    }
    public ValidatedPublicJwkSet resolve(IdentityProviderConfiguration configuration,String kid) {
        try {
            if(configuration==null || configuration.status()!=IdentityProviderStatus.ACTIVE || kid==null || kid.isBlank() || kid.length()>128)
                throw CustomerAuthenticationFailure.failed();
            JwksSource source=switch(configuration.configuration()) {
                case IdentityProviderConfiguration.OidcTokenConfiguration oidc -> oidc.jwksSource();
                case IdentityProviderConfiguration.SiteTokenConfiguration site -> site.jwksSource();
                default -> throw CustomerAuthenticationFailure.failed();
            };
            if(source instanceof JwksSource.StaticPublicJwkSet) {
                var keys=validateStatic(store.findPublicJwkSet(configuration.siteId(),configuration.providerKey()).orElseThrow(CustomerAuthenticationFailure::failed));
                if(!keys.contains(kid)) throw CustomerAuthenticationFailure.failed();
                return keys;
            }
            URI uri=((JwksSource.Remote)source).uri(); CacheEntry entry;
            synchronized(cache) {
                entry=cache.get(uri);
                if(entry==null) {
                    if(cache.size()>=256) throw CustomerAuthenticationFailure.failed();
                    entry=new CacheEntry(); cache.put(uri,entry);
                }
            }
            synchronized(entry) {
                Instant now=clock.instant(); boolean initial=entry.keys==null;
                boolean ttlExpired=entry.success==null || !now.isBefore(entry.success.plusSeconds(900));
                boolean recentlyAttempted=entry.attempt!=null && now.isBefore(entry.attempt.plusSeconds(60));
                if(ttlExpired&&!recentlyAttempted) {
                    entry.forced=false; refresh(entry,uri,now);
                }
                if(entry.keys==null || entry.success==null || now.isAfter(entry.success.plusSeconds(3600))) throw CustomerAuthenticationFailure.failed();
                if(!entry.keys.contains(kid)) {
                    // The initial load already fetched current keys; only subsequent cache misses force one refresh.
                    if(!initial&&!entry.forced) { entry.forced=true; refresh(entry,uri,now); }
                    if(!entry.keys.contains(kid)) throw CustomerAuthenticationFailure.failed();
                }
                return entry.keys;
            }
        } catch(RuntimeException failure) { throw CustomerAuthenticationFailure.failed(); }
    }
    private void refresh(CacheEntry entry,URI uri,Instant now) {
        entry.attempt=now;
        CacheSnapshot previous=entry.diagnostics;
        entry.diagnostics=new CacheSnapshot(previous.success(),true,false,previous.algorithms());
        try {
            entry.keys=fetch(uri); entry.success=now;
            Set<String> algorithms=new HashSet<>();
            for(var key:entry.keys.metadata()) algorithms.add(key.algorithm());
            entry.diagnostics=new CacheSnapshot(now,false,false,algorithms);
        } catch(RuntimeException unavailable) {
            entry.diagnostics=new CacheSnapshot(previous.success(),false,true,previous.algorithms());
            if(entry.keys==null) throw CustomerAuthenticationFailure.failed();
        }
    }
    private ValidatedPublicJwkSet fetch(URI uri) {
        if(!networkSlots.tryAcquire()) throw CustomerAuthenticationFailure.failed();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        var permit=new NetworkPermit(networkSlots);
        var operation=new FutureTask<ValidatedPublicJwkSet>(()->{
            if(!permit.start()) throw CustomerAuthenticationFailure.failed();
            try {
                byte[] bytes=transport.fetch(uri,deadline);
                String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return validateStatic(new PublicJwkSet(json));
            } finally { permit.complete(); }
        });
        try {
            network.execute(operation);
            return operation.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw CustomerAuthenticationFailure.failed(); }
        catch(ExecutionException|TimeoutException|RejectedExecutionException failure) { throw CustomerAuthenticationFailure.failed(); }
        finally { operation.cancel(true); permit.cancelBeforeStart(); }
    }
    /** Ownership is claimed inside the callable: Future cancellation alone does not mean running work has stopped. */
    private static final class NetworkPermit {
        private static final int QUEUED=0, STARTED=1, RELEASED=2;
        private final AtomicInteger state=new AtomicInteger(QUEUED);
        private final Semaphore slots;
        NetworkPermit(Semaphore slots) { this.slots=slots; }
        boolean start() { return state.compareAndSet(QUEUED,STARTED); }
        void cancelBeforeStart() { if(state.compareAndSet(QUEUED,RELEASED)) slots.release(); }
        void complete() { if(state.compareAndSet(STARTED,RELEASED)) slots.release(); }
    }
    private static final class CacheEntry {
        volatile CacheSnapshot diagnostics=new CacheSnapshot(null,false,false,Set.of());
        ValidatedPublicJwkSet keys;
        Instant success;
        Instant attempt;
        boolean forced;
    }
    private record CacheSnapshot(Instant success,boolean refreshing,boolean failed,Set<String> algorithms) {
        CacheSnapshot { algorithms=Set.copyOf(algorithms); }
    }
    @Override public String toString() { return "SafeJwksClient[redacted]"; }
}
