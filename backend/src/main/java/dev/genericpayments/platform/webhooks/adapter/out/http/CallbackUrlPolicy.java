package dev.genericpayments.platform.webhooks.adapter.out.http;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.net.*;
import java.util.*;
public final class CallbackUrlPolicy {
    @FunctionalInterface public interface Resolver { List<InetAddress> resolve(String host) throws UnknownHostException; }
    private final boolean local;private final Resolver resolver;
    private static final Set<String> LOCAL=Set.of("localhost","127.0.0.1","callback-receiver");
    // Reviewed against the IANA IPv4/IPv6 special-purpose registries (2025-10-09).
    // Deny protocol allocations conservatively, including deprecated relay/transition space.
    // Globally reachable AS112/AMT IPv4 (192.31.196/24, 192.52.193/24,
    // 192.175.48/24) and IPv6 AS112 (2620:4f:8000/48) remain allowed:
    // these are global services, not private, documentation or translation destinations.
    private static final List<Prefix> IPV4_DENIED=List.of(
        Prefix.v4(8,0,0,0,0),Prefix.v4(8,10,0,0,0),Prefix.v4(10,100,64,0,0),
        Prefix.v4(8,127,0,0,0),Prefix.v4(16,169,254,0,0),Prefix.v4(12,172,16,0,0),
        Prefix.v4(24,192,0,0,0),Prefix.v4(24,192,0,2,0),Prefix.v4(24,192,88,99,0),
        Prefix.v4(16,192,168,0,0),Prefix.v4(15,198,18,0,0),Prefix.v4(24,198,51,100,0),
        Prefix.v4(24,203,0,113,0),Prefix.v4(4,224,0,0,0),Prefix.v4(4,240,0,0,0));
    private static final Prefix IPV6_GLOBAL=Prefix.v6(3,0x2000);
    private static final List<Prefix> IPV6_DENIED=List.of(
        Prefix.v6(23,0x2001),Prefix.v6(32,0x2001,0x0db8),
        Prefix.v6(16,0x2002),Prefix.v6(20,0x3fff));
    static final class Prefix {
        private final byte[] network;private final int bits;
        private Prefix(byte[] network,int bits){this.network=network;this.bits=bits;}
        static Prefix v4(int bits,int a,int b,int c,int d){return new Prefix(new byte[]{(byte)a,(byte)b,(byte)c,(byte)d},bits);}
        static Prefix v6(int bits,int... words){byte[] network=new byte[16];for(int i=0;i<words.length;i++){network[i*2]=(byte)(words[i]>>>8);network[i*2+1]=(byte)words[i];}return new Prefix(network,bits);}
        boolean matches(byte[] address){
            if(address.length!=network.length)return false;
            int whole=bits/8,remaining=bits%8;
            for(int i=0;i<whole;i++)if(address[i]!=network[i])return false;
            int mask=(0xff<<(8-remaining))&0xff;
            return remaining==0||((address[whole]&mask)==(network[whole]&mask));
        }
    }
    public CallbackUrlPolicy(boolean local,Resolver resolver){this.local=local;this.resolver=Objects.requireNonNull(resolver);}
    public URI validate(String value) {
        URI uri;
        try {
            if(value==null||value.length()>2048||value.chars().anyMatch(c->Character.isISOControl(c)||c==127))throw invalid();
            uri=new URI(value).normalize();String scheme=uri.getScheme();String host=uri.getHost();
            if(!uri.isAbsolute()||host==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||scheme==null||controls(uri.getPath())||controls(uri.getQuery()))throw invalid();
            scheme=scheme.toLowerCase(Locale.ROOT);host=host.toLowerCase(Locale.ROOT);
            boolean relaxed=local&&LOCAL.contains(host);int port=uri.getPort()==-1?(scheme.equals("https")?443:80):uri.getPort();
            if(!(scheme.equals("https")&&port==443)&&!(relaxed&&scheme.equals("http")&&Set.of(80,3000,8080,8081).contains(port)))throw invalid();
            var addresses=resolver.resolve(host);if(addresses==null||addresses.isEmpty())throw invalid();
            for(var address:addresses)if(!relaxed&&forbidden(address))throw invalid();
            return new URI(scheme+"://"+uri.getRawAuthority().toLowerCase(Locale.ROOT)+(uri.getRawPath()==null?"":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
        }catch(UnknownHostException failure){throw new ResolutionFailure();}
        catch(URISyntaxException failure){throw invalid();}
    }
    private static boolean controls(String value){return value!=null&&value.chars().anyMatch(Character::isISOControl);}
    public static final class ResolutionFailure extends RuntimeException {public ResolutionFailure(){super("Callback DNS resolution failed");}}
    static boolean forbidden(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())return true;
        byte[] b=address.getAddress();
        if(b.length==4)return IPV4_DENIED.stream().anyMatch(prefix->prefix.matches(b));
        if(b.length==16){
            boolean mapped=true;for(int i=0;i<10;i++)mapped&=b[i]==0;
            if(mapped&&b[10]==(byte)255&&b[11]==(byte)255){try{return forbidden(InetAddress.getByAddress(Arrays.copyOfRange(b,12,16)));}catch(UnknownHostException ignored){return true;}}
            // Everything outside global unicast is denied (including translation-only,
            // discard, segment-routing, unique-local, link-local and reserved space).
            return !IPV6_GLOBAL.matches(b)||IPV6_DENIED.stream().anyMatch(prefix->prefix.matches(b));
        }return true;
    }
    public static PaymentApplicationException invalid(){return new PaymentApplicationException("CALLBACK_CONFIG_INVALID",400,"Invalid callback configuration");}
}
