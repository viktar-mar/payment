package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.customers.application.port.out.PublicCustomerAuthenticationRateLimitPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

/** Applies public customer-auth CORS and the 16 KiB limit before MVC parses credentials. */
public final class CustomerAuthenticationRequestPolicyFilter extends OncePerRequestFilter {
    public static final String BODY_TRACING_DISABLED="payment.customer-auth.body-tracing-disabled";
    private static final int MAX_BODY_BYTES=16*1024;
    private static final String AUTHENTICATION_PATH_PREFIX="/api/v1/public/customer-auth";
    private static final UUID INVALID_SITE_BUCKET=new UUID(0,0);
    private final LoadSitePort sites;
    private final PublicCustomerAuthenticationRateLimitPort limiter;
    private final ObjectMapper mapper;
    public CustomerAuthenticationRequestPolicyFilter(LoadSitePort sites,PublicCustomerAuthenticationRateLimitPort limiter,ObjectMapper mapper){
        this.sites=sites;this.limiter=limiter;this.mapper=mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !authenticationRouteFamily(applicationPath(request));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store"); request.setAttribute(BODY_TRACING_DISABLED,Boolean.TRUE);
        HttpServletRequest bounded;
        try {
            Route route=route(applicationPath(request));
            if(route==null) throw failed();
            String origin=CustomerSessionAuthenticationFilterOrigin.origin(request);
            if(!allowed(origin)) throw failed();
            response.setHeader("Access-Control-Allow-Origin",origin); response.addHeader("Vary","Origin");
            response.addHeader("Vary","Access-Control-Request-Method"); response.addHeader("Vary","Access-Control-Request-Headers");
            if("OPTIONS".equals(request.getMethod())) {
                if(!"POST".equals(request.getHeader("Access-Control-Request-Method"))) throw failed();
                response.setHeader("Access-Control-Allow-Methods","POST, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers","Content-Type"); response.setStatus(200); return;
            }
            if(!"POST".equals(request.getMethod())) throw failed();
            if(request.getContentLengthLong()>MAX_BODY_BYTES) throw tooLarge();
            byte[] body=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
            if(body.length>MAX_BODY_BYTES) throw tooLarge();
            if(route.operation()!=null) {
                var decision=limiter.acquire(new PublicCustomerAuthenticationRateLimitPort.Key(siteId(body),route.operation()));
                if(!decision.allowed()) {
                    response.setHeader("Access-Control-Expose-Headers","Retry-After");
                    response.setHeader("Retry-After",Integer.toString(decision.retryAfterSeconds()));
                    ApiProblem.write(request,response,mapper,429,"RATE_LIMIT_EXCEEDED","Rate limit exceeded"); return;
                }
            }
            bounded=new BodyRequest(request,body);
        } catch(PaymentApplicationException error) {
            ApiProblem.write(request,response,mapper,error.status(),error.code(),error.title()); return;
        } catch(RuntimeException failure) {
            PaymentApplicationException error=failed(); ApiProblem.write(request,response,mapper,error.status(),error.code(),error.title()); return;
        }
        chain.doFilter(bounded,response);
    }
    private UUID siteId(byte[] body) {
        try {
            var node=mapper.readTree(body);
            String siteKey=node==null||!node.isObject()||!node.hasNonNull("site_key")?null:node.get("site_key").asText();
            if(siteKey==null) return INVALID_SITE_BUCKET;
            return sites.findByKey(SiteKey.of(siteKey)).map(site -> site.id().value()).orElse(INVALID_SITE_BUCKET);
        } catch(RuntimeException invalid) { return INVALID_SITE_BUCKET; }
    }
    private static Route route(String requestUri) {
        return switch(requestUri) {
            case "/api/v1/public/customer-auth/oidc/attempts" -> new Route(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_BEGIN);
            case "/api/v1/public/customer-auth/oidc/exchanges" -> new Route(PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_EXCHANGE);
            case "/api/v1/public/customer-auth/site-token/exchanges" -> new Route(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_EXCHANGE);
            case "/api/v1/public/customer-auth/site-token/link-proofs" -> new Route(PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_LINK_PROOF);
            case "/api/v1/public/customer-auth/email-otp/challenges" -> new Route(null);
            default -> requestUri.matches("/api/v1/public/customer-auth/email-otp/challenges/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/verify")
                ?new Route(null):null;
        };
    }
    private record Route(PublicCustomerAuthenticationRateLimitPort.Operation operation) {}
    private static String applicationPath(HttpServletRequest request) {
        String requestUri=request.getRequestURI(),contextPath=request.getContextPath();
        if(contextPath==null||contextPath.isEmpty()) return requestUri;
        if(!requestUri.startsWith(contextPath)) return requestUri;
        int boundary=contextPath.length();
        if(requestUri.length()>boundary&&requestUri.charAt(boundary)!='/') return requestUri;
        return requestUri.substring(boundary);
    }
    private static boolean authenticationRouteFamily(String rawApplicationPath) {
        // Detection is deliberately broader than the exact operation allowlist below: an alias may
        // activate this policy, but it can never be normalized into an accepted authentication route.
        String candidate=rawApplicationPath;
        for(int remaining=rawApplicationPath.length()+1;remaining>0;remaining--) {
            if(hasAuthenticationPrefix(routingComparable(candidate))) return true;
            String decoded;
            try { decoded=UriUtils.decode(candidate,StandardCharsets.UTF_8); }
            catch(IllegalArgumentException invalidEncoding) { return false; }
            if(decoded.equals(candidate)) return false;
            candidate=decoded;
        }
        return false;
    }
    private static boolean hasAuthenticationPrefix(String path) {
        return path.equals(AUTHENTICATION_PATH_PREFIX)||path.startsWith(AUTHENTICATION_PATH_PREFIX+"/");
    }
    private static String routingComparable(String path) {
        Deque<String> segments=new ArrayDeque<>();
        for(String rawSegment:path.split("/",-1)) {
            String segment=rawSegment;
            int matrixStart=segment.indexOf(';');
            if(matrixStart>=0) segment=segment.substring(0,matrixStart);
            if(segment.isEmpty()||".".equals(segment)) continue;
            if("..".equals(segment)) {
                if(!segments.isEmpty()) segments.removeLast();
                continue;
            }
            segments.addLast(segment.toLowerCase(Locale.ROOT));
        }
        return "/"+String.join("/",segments);
    }
    private boolean allowed(String origin) {
        return sites.findAll().stream().filter(site -> site.status()==SiteStatus.ACTIVE)
            .flatMap(site -> site.allowedOrigins().stream()).map(URI::toString)
            .map(CustomerSessionAuthenticationFilterOrigin::normalize).anyMatch(origin::equals);
    }
    private static PaymentApplicationException failed(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
    private static PaymentApplicationException tooLarge(){return new PaymentApplicationException("AUTHENTICATION_REQUEST_TOO_LARGE",413,"Authentication request too large");}

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body.clone();}
        @Override public ServletInputStream getInputStream(){
            ByteArrayInputStream input=new ByteArrayInputStream(body);
            return new ServletInputStream(){
                @Override public int read(){return input.read();}
                @Override public boolean isFinished(){return input.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        @Override public int getContentLength(){return body.length;}
        @Override public long getContentLengthLong(){return body.length;}
    }
}

final class CustomerSessionAuthenticationFilterOrigin {
    private CustomerSessionAuthenticationFilterOrigin(){}
    static String origin(HttpServletRequest request) {
        List<String> values=Collections.list(request.getHeaders("Origin"));
        if(values.size()!=1) throw new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
        return normalize(values.getFirst());
    }
    static String normalize(String value) {
        try {
            URI uri=URI.create(value);
            if(uri.getScheme()==null||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                ||uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()) throw new IllegalArgumentException();
            String scheme=uri.getScheme().toLowerCase(Locale.ROOT),host=uri.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equals(scheme)&&!("http".equals(scheme)&&Set.of("localhost","127.0.0.1","::1").contains(host))) throw new IllegalArgumentException();
            int port=uri.getPort(); if(("https".equals(scheme)&&port==443)||("http".equals(scheme)&&port==80)) port=-1;
            return new URI(scheme,null,host,port,null,null,null).toString();
        } catch(RuntimeException|java.net.URISyntaxException failure){throw new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
    }
}
