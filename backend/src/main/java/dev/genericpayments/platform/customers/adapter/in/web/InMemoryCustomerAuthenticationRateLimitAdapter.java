package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.application.port.out.PublicCustomerAuthenticationRateLimitPort;
import java.time.*;
import java.util.*;

/** Per-process limiter whose key contains only a site UUID and a fixed operation enum. */
public final class InMemoryCustomerAuthenticationRateLimitAdapter implements PublicCustomerAuthenticationRateLimitPort {
    private final Clock clock;
    private final int oidcBegin,oidcExchange,siteExchange,siteLink,maxKeys;
    private final LinkedHashMap<Key,Bucket> buckets=new LinkedHashMap<>(16,0.75f,true);
    private record Bucket(double tokens,Instant lastRefill) {}

    public InMemoryCustomerAuthenticationRateLimitAdapter(Clock clock,int oidcBegin,int oidcExchange,int siteExchange,int siteLink,int maxKeys) {
        CustomerAuthenticationRateLimitProperties.requireValid(oidcBegin,oidcExchange,siteExchange,siteLink,maxKeys);
        this.clock=Objects.requireNonNull(clock,"clock"); this.oidcBegin=oidcBegin; this.oidcExchange=oidcExchange;
        this.siteExchange=siteExchange; this.siteLink=siteLink; this.maxKeys=maxKeys;
    }

    @Override public synchronized Decision acquire(Key key) {
        Objects.requireNonNull(key,"key");
        Instant now=clock.instant();
        int capacity=switch(key.operation()) {
            case OIDC_BEGIN -> oidcBegin;
            case OIDC_EXCHANGE -> oidcExchange;
            case SITE_TOKEN_EXCHANGE -> siteExchange;
            case SITE_TOKEN_LINK_PROOF, CUSTOMER_IDENTITY_LINK -> siteLink;
        };
        Bucket bucket=buckets.get(key);
        if(bucket==null) {
            if(buckets.size()==maxKeys) buckets.remove(buckets.keySet().iterator().next());
            bucket=new Bucket(capacity,now);
        }
        Instant timestamp=now.isAfter(bucket.lastRefill())?now:bucket.lastRefill();
        double seconds=Math.max(0,Duration.between(bucket.lastRefill(),timestamp).toNanos()/1_000_000_000.0);
        double tokens=Math.min(capacity,bucket.tokens()+seconds*capacity/60.0);
        boolean allowed=tokens>=1;
        buckets.put(key,new Bucket(allowed?tokens-1:tokens,timestamp));
        return new Decision(allowed,allowed?0:Math.max(1,(int)Math.ceil((1-tokens)*60.0/capacity)));
    }

    int keyCount() { return buckets.size(); }
}
