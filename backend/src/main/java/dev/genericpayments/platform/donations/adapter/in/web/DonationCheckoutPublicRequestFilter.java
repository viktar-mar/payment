package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.donations.application.port.in.GetPublicDonationCampaignUseCase;
import dev.genericpayments.platform.donations.application.service.DonationCampaignService;
import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteKey;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Uses MVC's decoded route matcher for admission, then accepts only canonical public write URLs. */
public final class DonationCheckoutPublicRequestFilter extends OncePerRequestFilter {
    static final RequestMatcher ROUTE=PathPatternRequestMatcher.withDefaults().matcher("/api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}/checkouts");
    private static final Pattern CANONICAL=Pattern.compile("/api/v1/public/sites/[a-z0-9][a-z0-9-]{2,62}/donation-campaigns/[a-z0-9][a-z0-9-]{2,62}/checkouts");
    private static final int MAX_BODY=16*1024;
    private static final String ATTRIBUTE=DonationCheckoutPublicRequestFilter.class.getName();
    public record Admission(URI origin,CustomerSessionContext session) {}
    private final GetPublicDonationCampaignUseCase campaigns;
    private final CustomerSessionResolver sessions;
    private final LoadSitePort sites;
    private final RateLimitPort limits;
    private final ObjectMapper mapper;
    private final dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort telemetry;
    public DonationCheckoutPublicRequestFilter(GetPublicDonationCampaignUseCase campaigns,CustomerSessionResolver sessions,LoadSitePort sites,RateLimitPort limits,ObjectMapper mapper,dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort telemetry) {
        this.campaigns=campaigns;this.sessions=sessions;this.sites=sites;this.limits=limits;this.mapper=mapper;this.telemetry=telemetry;
    }
    static Admission admitted(HttpServletRequest request) {
        if(request.getAttribute(ATTRIBUTE) instanceof Admission admission) return admission;
        throw DonationCampaignService.originDenied();
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {return !ROUTE.matches(request);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        response.addHeader("Vary","Origin");response.addHeader("Vary","Access-Control-Request-Method");response.addHeader("Vary","Access-Control-Request-Headers");
        try {
            var route=ROUTE.matcher(request);
            var key=route.getVariables().get("siteKey");
            CustomerSessionContext session=null;
            var headers=Collections.list(request.getHeaders("Authorization"));
            if(!"OPTIONS".equals(request.getMethod()) && !headers.isEmpty()) {
                long started=System.nanoTime();
                // A supplied credential must be rejected before path, key, or body validation.
                try {
                    if(headers.size()!=1) throw failed();
                    var site=sites.findByKey(new SiteKey(key)).orElseThrow(DonationCheckoutPublicRequestFilter::failed);
                    session=sessions.resolveOptional(headers.getFirst(),site.id(),DonationCampaignPublicRequestFilter.origin(request).toString(),CustomerSessionScope.CHECKOUT_CREATE).orElseThrow(DonationCheckoutPublicRequestFilter::failed);
                } catch(RuntimeException invalid) {
                    telemetry.checkout(dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort.CheckoutOutcome.REJECTED_SESSION,java.time.Duration.ofNanos(System.nanoTime()-started));
                    allowTrustedAuthenticationError(request,response,key);
                    throw failed();
                }
            }
            var raw=request.getRequestURI().substring(request.getContextPath().length());
            if(!CANONICAL.matcher(raw).matches()) throw DonationCampaignService.originDenied();
            var origin=DonationCampaignPublicRequestFilter.origin(request);var site=campaigns.requirePublicSite(key,origin);
            if("OPTIONS".equals(request.getMethod())) {
                if(Collections.list(request.getHeaders("Access-Control-Request-Method")).size()!=1
                    || !"POST".equals(request.getHeader("Access-Control-Request-Method"))) throw DonationCampaignService.originDenied();
                for(var header:Collections.list(request.getHeaders("Access-Control-Request-Headers"))) for(var name:header.split(",",-1))
                    if(!Set.of("authorization","content-type","idempotency-key").contains(name.trim().toLowerCase(Locale.ROOT))) throw DonationCampaignService.originDenied();
                response.setHeader("Access-Control-Allow-Origin",origin.toString());response.setHeader("Access-Control-Allow-Methods","POST, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers","Authorization, Content-Type, Idempotency-Key");response.setStatus(200);return;
            }
            if(!"POST".equals(request.getMethod())) throw DonationCampaignService.originDenied();
            response.setHeader("Access-Control-Allow-Origin",origin.toString());response.setHeader("Access-Control-Expose-Headers","Retry-After");
            var decision=limits.acquire(new RateLimitPort.Key(site.id().value(),request.getRemoteAddr(),RateLimitPort.Operation.CREATE));
            if(!decision.allowed()) {
                response.setHeader("Retry-After",Integer.toString(decision.retryAfterSeconds()));
                ApiProblem.write(request,response,mapper,429,"RATE_LIMIT_EXCEEDED","Rate limit exceeded");return;
            }
            if(request.getContentLengthLong()>MAX_BODY) {tooLarge(request,response);return;}
            var body=request.getInputStream().readNBytes(MAX_BODY+1);
            if(body.length>MAX_BODY) {tooLarge(request,response);return;}
            request.setAttribute(ATTRIBUTE,new Admission(origin,session));
            chain.doFilter(new BoundedBodyRequest(request,body),response);
        } catch(PaymentApplicationException failure) {ApiProblem.write(request,response,mapper,failure.status(),failure.code(),failure.title());}
    }
    private void allowTrustedAuthenticationError(HttpServletRequest request,HttpServletResponse response,String key) {
        // CORS makes the existing rejection readable; it never admits a request or changes error precedence.
        if(!"POST".equals(request.getMethod()) || !CANONICAL.matcher(request.getRequestURI().substring(request.getContextPath().length())).matches()) return;
        try {
            var origin=DonationCampaignPublicRequestFilter.origin(request);
            campaigns.requirePublicSite(key,origin);
            response.setHeader("Access-Control-Allow-Origin",origin.toString());
        } catch(RuntimeException untrusted) { /* Never reflect an unverified Origin, including duplicate headers. */ }
    }
    private static PaymentApplicationException failed(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
    private void tooLarge(HttpServletRequest request,HttpServletResponse response) throws IOException {
        ApiProblem.write(request,response,mapper,413,"DONATION_CHECKOUT_REQUEST_TOO_LARGE","Donation checkout request too large");
    }
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BoundedBodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body.clone();}
        @Override public ServletInputStream getInputStream(){
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream(){
                @Override public int read(){return input.read();}
                @Override public boolean isFinished(){return input.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        @Override public int getContentLength(){return body.length;}
        @Override public long getContentLengthLong(){return body.length;}
    }
}
