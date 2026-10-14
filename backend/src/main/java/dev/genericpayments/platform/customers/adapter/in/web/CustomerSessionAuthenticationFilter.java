package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Establishes ROLE_CUSTOMER only from a site/origin-bound SDK session. */
public final class CustomerSessionAuthenticationFilter extends OncePerRequestFilter {
    private final CustomerSessionResolver sessions;
    private final LoadSitePort sites;
    private final ObjectMapper mapper;
    public CustomerSessionAuthenticationFilter(CustomerSessionResolver sessions,LoadSitePort sites,ObjectMapper mapper) {
        this.sessions=sessions; this.sites=sites; this.mapper=mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        String context=request.getContextPath();
        if(!context.isEmpty() && path.startsWith(context+"/")) path=path.substring(context.length());
        return !path.startsWith("/api/v1/customer/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        String origin;
        CustomerSessionContext context;
        try {
            origin=origin(request);
            if("OPTIONS".equals(request.getMethod())) {
                if(!allowed(origin)) throw failed();
                cors(response,origin); response.setHeader("Access-Control-Allow-Methods","GET, POST, DELETE, OPTIONS");
                boolean subscription=dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionRequestPolicyFilter.matches(request.getRequestURI());
                String requested=request.getHeader("Access-Control-Request-Headers");
                if(subscription&&requested!=null&&Arrays.stream(requested.split(",")).map(String::trim).map(String::toLowerCase).anyMatch(h->!Set.of("authorization","content-type","idempotency-key").contains(h)))throw failed();
                response.setHeader("Access-Control-Allow-Headers",subscription?"Authorization, Content-Type, Idempotency-Key":"Authorization, Content-Type"); response.setStatus(200); return;
            }
            if(!allowed(origin)) throw failed();
            // Browser clients must be able to observe uniform session rejection and clear the exact token.
            // Admission still uses only a configured exact Origin, never a failed credential's claims.
            cors(response,origin);
            List<String> headers=Collections.list(request.getHeaders("Authorization"));
            if(headers.size()!=1 || !headers.getFirst().startsWith("Bearer ")) throw failed();
            String header=headers.getFirst();
            if(header.length()<=7 || header.indexOf(' ',7)>=0 || header.indexOf(',')>=0) throw failed();
            context=sessions.resolveRequired(header.substring(7),origin);
        } catch(RuntimeException failure) {
            SecurityContextHolder.clearContext();
            PaymentApplicationException error=failed();
            ApiProblem.write(request,response,mapper,error.status(),error.code(),error.title()); return;
        }
        cors(response,origin);
        var authentication=UsernamePasswordAuthenticationToken.authenticated(context,"N/A",
            AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request,response);
    }
    private boolean allowed(String origin) {
        return sites.findAll().stream().filter(site -> site.status()==SiteStatus.ACTIVE)
            .flatMap(site -> site.allowedOrigins().stream()).map(URI::toString)
            .map(CustomerSessionAuthenticationFilter::normalize).anyMatch(origin::equals);
    }
    private static String origin(HttpServletRequest request) {
        List<String> values=Collections.list(request.getHeaders("Origin"));
        if(values.size()!=1) throw failed();
        return normalize(values.getFirst());
    }
    private static String normalize(String value) {
        try {
            URI uri=URI.create(value);
            if(uri.getScheme()==null||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                || uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()) throw failed();
            String scheme=uri.getScheme().toLowerCase(Locale.ROOT),host=uri.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equals(scheme)&&!("http".equals(scheme)&&Set.of("localhost","127.0.0.1","::1").contains(host))) throw failed();
            int port=uri.getPort(); if(("https".equals(scheme)&&port==443)||("http".equals(scheme)&&port==80)) port=-1;
            return new URI(scheme,null,host,port,null,null,null).toString();
        } catch(PaymentApplicationException failure){throw failure;}
        catch(RuntimeException|java.net.URISyntaxException failure){throw failed();}
    }
    private static void cors(HttpServletResponse response,String origin) {
        response.setHeader("Access-Control-Allow-Origin",origin); response.addHeader("Vary","Origin");
    }
    private static PaymentApplicationException failed(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
}
