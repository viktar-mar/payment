package dev.genericpayments.platform.webhooks.domain;
import java.util.UUID;
public record CallbackConfiguration(UUID siteId,String endpointUrl,boolean enabled,UUID secretId,int secretVersion) {
    @Override public String toString(){return "CallbackConfiguration[siteId="+siteId+", enabled="+enabled+", secretVersion="+secretVersion+"]";}
}
