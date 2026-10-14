package dev.genericpayments.platform.webhooks.application.service;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
public final class CallbackSigner {
    public String sign(byte[] secret,long timestamp,byte[] body) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));return HexFormat.of().formatHex(mac.doFinal(body));}
        catch(java.security.GeneralSecurityException failure){throw new IllegalStateException("CALLBACK_DELIVERY_FAILED");}
    }
}
