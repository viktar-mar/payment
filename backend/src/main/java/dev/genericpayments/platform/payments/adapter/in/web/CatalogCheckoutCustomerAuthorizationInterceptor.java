package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Authenticates an optional Customer session after handler selection and before MVC argument binding. */
public final class CatalogCheckoutCustomerAuthorizationInterceptor implements HandlerInterceptor {
    private static final String ATTRIBUTE = CatalogCheckoutCustomerAuthorizationInterceptor.class.getName() + ".customer";
    private final CustomerSessionResolver sessions;
    private final LoadSitePort sites;

    public CatalogCheckoutCustomerAuthorizationInterceptor(CustomerSessionResolver sessions,LoadSitePort sites) {
        this.sessions=sessions;
        this.sites=sites;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if(!(handler instanceof HandlerMethod method) || method.getBeanType()!=PublicCheckoutController.class
            || !method.getMethod().getName().equals("create")) return true;
        response.setHeader("Cache-Control","no-store");
        try {
            var authorization=Collections.list(request.getHeaders("Authorization"));
            UUID customerId=null;
            if(!authorization.isEmpty()) {
                if(authorization.size()!=1) throw PublicCheckoutController.customerAuthenticationFailed();
                @SuppressWarnings("unchecked")
                var variables=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                String siteKey=variables==null?null:variables.get("siteKey");
                var site=sites.findByKey(SiteKey.of(siteKey)).filter(candidate -> candidate.status()==SiteStatus.ACTIVE)
                    .orElseThrow(PublicCheckoutController::customerAuthenticationFailed);
                var context=sessions.resolveOptional(authorization.getFirst(),site.id(),
                        DynamicSiteCorsConfigurationSource.origin(request).toString(),CustomerSessionScope.CHECKOUT_CREATE)
                    .orElseThrow(PublicCheckoutController::customerAuthenticationFailed);
                customerId=context.customerId().value();
            }
            request.setAttribute(ATTRIBUTE,new Resolution(customerId));
            return true;
        } catch(RuntimeException invalid) {
            throw PublicCheckoutController.customerAuthenticationFailed();
        }
    }

    static UUID customerId(HttpServletRequest request) {
        Object value=request.getAttribute(ATTRIBUTE);
        if(!(value instanceof Resolution resolution)) throw PublicCheckoutController.customerAuthenticationFailed();
        return resolution.customerId();
    }

    private record Resolution(UUID customerId) { }
}
