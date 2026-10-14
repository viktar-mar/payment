package dev.genericpayments.platform.payments.adapter.out.security;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.customer-history.cursor")
public final class CustomerPaymentHistoryCursorProperties {
    private final String activeKeyVersion;
    private final Map<String,byte[]> keys;
    public CustomerPaymentHistoryCursorProperties(String activeKeyVersion,Map<String,String> knownKeys) {
        try {
            if(activeKeyVersion==null||knownKeys==null||knownKeys.isEmpty()||knownKeys.size()>20
                    ||!knownKeys.containsKey(activeKeyVersion)) throw new IllegalArgumentException();
            var decoded=new HashMap<String,byte[]>();
            for(var entry:knownKeys.entrySet()) {
                if(!entry.getKey().matches("v[1-9][0-9]{0,8}")||entry.getValue()==null) throw new IllegalArgumentException();
                byte[] key=Base64.getDecoder().decode(entry.getValue());
                if(key.length<32||key.length>128) throw new IllegalArgumentException();
                decoded.put(entry.getKey(),key.clone());
            }
            this.activeKeyVersion=activeKeyVersion;this.keys=Map.copyOf(decoded);
        } catch(RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Customer history cursor configuration");
        }
    }
    String activeKeyVersion(){return activeKeyVersion;}
    byte[] key(String version){byte[] key=keys.get(version);return key==null?null:key.clone();}
    @Override public String toString(){return "CustomerPaymentHistoryCursorProperties[redacted]";}
}
