package dev.genericpayments.platform.webhooks.domain;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import java.util.UUID;
public record CallbackSecretVersion(UUID id,UUID siteId,int version,EncryptedValue encrypted) {
    @Override public String toString(){return "CallbackSecretVersion[id="+id+", version="+version+", secret=redacted]";}
}
