package dev.genericpayments.platform.webhooks.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Proxy boundary entered only after the controller finishes target validation. */
@Component
public class CallbackConfigurationMutationFacade {
    private final CallbackConfigurationPort configurations;private final AdminAuditFacade audit;
    public CallbackConfigurationMutationFacade(CallbackConfigurationPort configurations,AdminAuditFacade audit){this.configurations=configurations;this.audit=audit;}
    @Transactional public CallbackConfigurationPort.Reveal configure(UUID site,String normalizedEndpoint,boolean enabled,AuthenticatedAdmin actor,HttpServletRequest request){
        var reveal=configurations.configure(site,normalizedEndpoint,enabled);var c=reveal.configuration();
        audit.record(AuditAction.SITE_CALLBACK_CONFIGURED,"SITE_CALLBACK",c.siteId(),actor,request,
            Map.of("site_id",c.siteId().toString(),"enabled",Boolean.toString(c.enabled()),"secret_version",Integer.toString(c.secretVersion())));
        return reveal;
    }
}
