package dev.genericpayments.platform.partners.adapter.in.web;
import dev.genericpayments.platform.partners.application.service.SiteApiKeyService;
import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
public final class PartnerAuthenticationFilter extends OncePerRequestFilter {
    private final SiteApiKeyService keys; private final RateLimitPort limits; private final ObjectMapper mapper;
    public PartnerAuthenticationFilter(SiteApiKeyService keys,RateLimitPort limits,ObjectMapper mapper) { this.keys=keys; this.limits=limits; this.mapper=mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/v1/partner/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        try {
            var headers=Collections.list(request.getHeaders("Authorization"));
            if (headers.size()!=1 || !headers.getFirst().startsWith("Bearer ") || headers.getFirst().substring(7).contains(" ") || headers.getFirst().contains(",")) throw SiteApiKeyService.invalid();
            var verified=keys.authenticate(headers.getFirst().substring(7));
            var operation="GET".equals(request.getMethod()) ? RateLimitPort.Operation.READ
                : request.getRequestURI().endsWith("/handoffs") ? RateLimitPort.Operation.HANDOFF : RateLimitPort.Operation.CREATE;
            var decision=limits.acquire(new RateLimitPort.Key(verified.siteId().value(),verified.keyId().value().toString(),operation));
            if (!decision.allowed()) { response.setHeader("Retry-After",Integer.toString(decision.retryAfterSeconds())); ApiProblem.write(request,response,mapper,429,"RATE_LIMIT_EXCEEDED","Rate limit exceeded"); return; }
            keys.touch(verified.keyId());
            var principal=new AuthenticatedSite(verified.siteId(),verified.keyId(),verified.scopes());
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_PARTNER"))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request,response);
        } catch (PaymentApplicationException error) { ApiProblem.write(request,response,mapper,error.status(),error.code(),error.title()); }
    }
}
