package dev.genericpayments.platform.customers.adapter.out.security;

import java.net.*;
import java.util.*;

public final class JwksAddressPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String hostname) throws UnknownHostException; }
    private final Resolver resolver;
    private final Set<String> localHosts;
    public JwksAddressPolicy() { this(InetAddress::getAllByName,Set.of(),Set.of()); }
    public JwksAddressPolicy(Resolver resolver,Set<String> activeProfiles,Set<String> localHosts) {
        this.resolver=Objects.requireNonNull(resolver);
        // Any additional profile (including production) disables the local exception.
        this.localHosts=!activeProfiles.isEmpty() && Set.of("local","test").containsAll(activeProfiles)?Set.copyOf(localHosts):Set.of();
    }
    public InetAddress[] resolve(URI uri) {
        validateUri(uri);
        try {
            var addresses=resolver.resolve(uri.getHost());
            if(addresses==null || addresses.length==0 || addresses.length>32) throw CustomerAuthenticationFailure.failed();
            for(var address:addresses) validateAddress(uri,address);
            return addresses.clone();
        } catch(RuntimeException|UnknownHostException invalid) { throw CustomerAuthenticationFailure.failed(); }
    }
    void validateUri(URI uri) {
        if(uri==null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null
            || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535 || uri.toASCIIString().length()>2048)
            throw CustomerAuthenticationFailure.failed();
    }
    void validateAddress(URI uri,InetAddress address) {
        if(address==null) throw CustomerAuthenticationFailure.failed();
        if(localHosts.contains(uri.getHost()) && address.isLoopbackAddress()) return;
        byte[] bytes=address.getAddress();
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
            || address.isMulticastAddress()) throw CustomerAuthenticationFailure.failed();
        if(bytes.length==4) {
            int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255;
            if(a==0 || a==10 || a==127 || a>=224 || a==169&&b==254 || a==172&&b>=16&&b<=31
                || a==192&&b==168 || a==100&&b>=64&&b<=127 || a==192&&b==0 || a==198&&(b==18||b==19)
                || a==192&&b==0&&c==2 || a==198&&b==51&&c==100 || a==203&&b==0&&c==113)
                throw CustomerAuthenticationFailure.failed();
        } else if(bytes.length==16) {
            // Permit only global unicast. Exclude transition ranges capable of carrying an inner IPv4 target.
            int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255,d=bytes[3]&255;
            if((a&0xe0)!=0x20 || a==0x20&&b==0x02 || a==0x20&&b==0x01&&(c==0&&d==0 || c==0x0d&&d==0xb8))
                throw CustomerAuthenticationFailure.failed();
        } else throw CustomerAuthenticationFailure.failed();
    }
    @Override public String toString() { return "JwksAddressPolicy[redacted]"; }
}
