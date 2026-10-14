package dev.genericpayments.platform.admin.adapter.in.web;

import dev.genericpayments.platform.admin.adapter.out.security.Argon2AdminPasswordAdapter;
import dev.genericpayments.platform.admin.application.port.out.*;
import dev.genericpayments.platform.admin.application.service.AdminSessionService;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AdminSecurityProperties.class, AdminBootstrapProperties.class})
public class SecurityConfiguration {
    @Bean AdminPasswordPort adminPasswords() { return new Argon2AdminPasswordAdapter(); }

    @Bean
    AdminSessionService adminSessions(AdminUserPort users, AdminSessionPort sessions, AuditLogPort audits,
                                      AdminPasswordPort passwords, Clock clock, AdminSecurityProperties properties) {
        return new AdminSessionService(users, sessions, audits, passwords, clock, new SecureRandom(),
            properties.idleTimeout(), properties.absoluteTimeout());
    }

    @Bean(initMethod = "initialize")
    AdminBootstrap adminBootstrap(AdminBootstrapProperties properties, AdminUserPort users,
                                   AdminPasswordPort passwords, Clock clock) {
        return new AdminBootstrap(properties, users, passwords, clock);
    }

    // Suppress Boot's fallback generated-password account; this API authenticates only opaque sessions.
    @Bean
    UserDetailsService noPasswordAuthentication() {
        return username -> { throw new UsernameNotFoundException("Password authentication is unavailable"); };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AdminSessionService sessions,
                                             AdminSecurityProperties properties, ObjectMapper mapper,
                                             dev.genericpayments.platform.payments.adapter.in.web.DynamicSiteCorsConfigurationSource publicCors,
                                             @org.springframework.beans.factory.annotation.Qualifier("publicRateLimitPort") dev.genericpayments.platform.payments.application.port.out.RateLimitPort publicLimits,
                                             dev.genericpayments.platform.partners.application.service.SiteApiKeyService siteKeys,
                                             @org.springframework.beans.factory.annotation.Qualifier("partnerRateLimitPort") dev.genericpayments.platform.payments.application.port.out.RateLimitPort partnerLimits,
                                             dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver customerSessions,
                                             dev.genericpayments.platform.customers.application.port.out.PublicCustomerAuthenticationRateLimitPort customerAuthenticationLimits,
                                             dev.genericpayments.platform.sites.application.port.out.LoadSitePort sites,
                                             dev.genericpayments.platform.donations.application.port.in.GetPublicDonationCampaignUseCase campaigns,
                                             dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort donationTelemetry) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.secure(properties.secureCookie()).sameSite("Strict").path("/"));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/api/admin/v1/auth/sessions", "/api/v1/public/**").permitAll()
                .requestMatchers("/api/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/partner/**").hasRole("PARTNER")
                .requestMatchers("/api/v1/customer/**").hasRole("CUSTOMER")
                .anyRequest().denyAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                // Each request restores an existing opaque session, not a new login. Keep its CSRF cookie.
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.POST, "/api/admin/v1/auth/sessions"), PathPatternRequestMatcher.withDefaults().matcher("/api/v1/public/**"),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/v1/partner/**"),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/v1/customer/**")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    ApiProblem.write(request, response, mapper, 401, "unauthorized", "Authentication required"))
                .accessDeniedHandler((request, response, exception) -> {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (exception instanceof CsrfException && (authentication == null
                            || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)) {
                        ApiProblem.write(request, response, mapper, 401, "unauthorized", "Authentication required");
                        return;
                    }
                    ApiProblem.write(request, response, mapper, 403,
                        exception instanceof CsrfException ? "csrf_invalid" : "forbidden",
                        exception instanceof CsrfException ? "Invalid CSRF token" : "Forbidden");
                }))
            .addFilterBefore(new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionRequestPolicyFilter(mapper,true), CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.customers.adapter.in.web.CustomerAuthenticationRequestPolicyFilter(
                sites,customerAuthenticationLimits,mapper),
                CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.customers.adapter.in.web.CustomerIdentityLinkRequestPolicyFilter(mapper),
                CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.customers.adapter.in.web.CustomerAnonymizationRequestPolicyFilter(mapper),
                CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.donations.adapter.in.web.DonationCampaignPublicRequestFilter(campaigns,publicLimits,mapper), CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.donations.adapter.in.web.DonationCheckoutPublicRequestFilter(campaigns,customerSessions,sites,publicLimits,mapper,donationTelemetry), CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.payments.adapter.in.web.PublicRateLimitFilter(publicCors, publicLimits, mapper), CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.partners.adapter.in.web.PartnerAuthenticationFilter(siteKeys, partnerLimits, mapper), CsrfFilter.class)
            .addFilterBefore(new AdminSessionAuthenticationFilter(sessions), CsrfFilter.class)
            .addFilterBefore(new dev.genericpayments.platform.customers.adapter.in.web.CustomerSessionAuthenticationFilter(customerSessions,sites,mapper),
                CsrfFilter.class)
            .addFilterAfter(new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionRequestPolicyFilter(mapper,false),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            // Keep session, CSRF and role failures ahead of body admission; MVC is still downstream.
            .addFilterAfter(new dev.genericpayments.platform.donations.adapter.in.web.DonationMessageAdminRequestPolicyFilter(mapper),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            .addFilterAfter(new dev.genericpayments.platform.donations.adapter.in.web.DonationCampaignAdminRequestPolicyFilter(mapper),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
