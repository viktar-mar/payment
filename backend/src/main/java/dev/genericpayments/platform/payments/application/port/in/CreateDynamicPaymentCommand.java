package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
public record CreateDynamicPaymentCommand(SiteId siteId,UUID customerId,String externalOrderId,long amountMinor,String currency,URI checkoutOrigin,
    String returnUrl,Map<String,String> metadata,String idempotencyKey,String requestId) {
    public CreateDynamicPaymentCommand(SiteId siteId,String externalOrderId,long amountMinor,String currency,URI checkoutOrigin,
        String returnUrl,Map<String,String> metadata,String idempotencyKey,String requestId) {
        this(siteId,null,externalOrderId,amountMinor,currency,checkoutOrigin,returnUrl,metadata,idempotencyKey,requestId);
    }
    public CreateDynamicPaymentCommand {
        if (metadata != null) metadata=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
    @Override public String toString() { return "CreateDynamicPaymentCommand[siteId="+siteId+"]"; }
}
