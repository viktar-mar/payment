package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.donations.application.port.in.GetPublicDonationCampaignUseCase;
import dev.genericpayments.platform.donations.application.service.DonationCampaignService;
import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

/** Donation-owned admission; reject routing aliases instead of normalizing them into public reads. */
public final class DonationCampaignPublicRequestFilter extends OncePerRequestFilter {
    private static final org.springframework.security.web.util.matcher.RequestMatcher MVC_READ =
        org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults()
            .matcher("/api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}");
    private static final Pattern READ=Pattern.compile("^/api/v1/public/sites/([a-z0-9][a-z0-9-]{2,62})/donation-campaigns/([a-z0-9][a-z0-9-]{2,62})$");
    private final GetPublicDonationCampaignUseCase campaigns;
    private final RateLimitPort limits;
    private final ObjectMapper json;
    public DonationCampaignPublicRequestFilter(GetPublicDonationCampaignUseCase campaigns,RateLimitPort limits,ObjectMapper json) {
        this.campaigns=campaigns; this.limits=limits; this.json=json;
    }
    private static String path(HttpServletRequest request) {
        var raw=request.getRequestURI(); var context=request.getContextPath();
        return !context.isEmpty() && raw.startsWith(context+"/") ? raw.substring(context.length()) : raw;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if(DonationCheckoutPublicRequestFilter.ROUTE.matches(request)) return true;
        var candidate=path(request);
        for(int remaining=candidate.length()+1;remaining>0;remaining--) {
            if(candidate.toLowerCase(Locale.ROOT).contains("donation-campaigns")) return false;
            try { var decoded=UriUtils.decode(candidate,StandardCharsets.UTF_8); if(decoded.equals(candidate)) break; candidate=decoded; }
            catch(IllegalArgumentException malformed) { break; }
        }
        return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        // Restrict this policy to public URLs; Admin owns its session and CSRF boundary.
        // MVC decodes segments; an encoded public prefix must still reach raw-URL validation.
        if(!path(request).startsWith("/api/v1/public/")&&!MVC_READ.matches(request)) { chain.doFilter(request,response); return; }
        response.setHeader("Cache-Control","no-store");
        response.addHeader("Vary","Origin"); response.addHeader("Vary","Access-Control-Request-Method"); response.addHeader("Vary","Access-Control-Request-Headers");
        try {
            var route=READ.matcher(path(request)); if(!route.matches()) throw DonationCampaignService.originDenied();
            var origin=origin(request); var site=campaigns.requirePublicSite(route.group(1),origin);
            if("OPTIONS".equals(request.getMethod())) {
                if(!"GET".equals(request.getHeader("Access-Control-Request-Method"))) throw DonationCampaignService.originDenied();
                var requested=Collections.list(request.getHeaders("Access-Control-Request-Headers"));
                for(var header:requested) for(var name:header.split(",",-1))
                    if(!Set.of("authorization","content-type","idempotency-key").contains(name.trim().toLowerCase(Locale.ROOT))) throw DonationCampaignService.originDenied();
                response.setHeader("Access-Control-Allow-Origin",origin.toString()); response.setHeader("Access-Control-Allow-Methods","GET, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers","Authorization, Content-Type, Idempotency-Key"); response.setStatus(200); return;
            }
            if(!"GET".equals(request.getMethod())) throw DonationCampaignService.originDenied();
            response.setHeader("Access-Control-Allow-Origin",origin.toString()); response.setHeader("Access-Control-Expose-Headers","Retry-After");
            var decision=limits.acquire(new RateLimitPort.Key(site.id().value(),request.getRemoteAddr(),RateLimitPort.Operation.READ));
            if(!decision.allowed()) {
                response.setHeader("Retry-After",Integer.toString(decision.retryAfterSeconds()));
                ApiProblem.write(request,response,json,429,"RATE_LIMIT_EXCEEDED","Rate limit exceeded"); return;
            }
            chain.doFilter(request,response);
        } catch(PaymentApplicationException safe) { ApiProblem.write(request,response,json,safe.status(),safe.code(),safe.title()); }
    }
    static URI origin(HttpServletRequest request) {
        var values=Collections.list(request.getHeaders("Origin")); if(values.size()!=1) throw DonationCampaignService.originDenied();
        try { return URI.create(values.getFirst()); } catch(IllegalArgumentException malformed) { throw DonationCampaignService.originDenied(); }
    }
}
