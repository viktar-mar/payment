package dev.genericpayments.platform.customers.adapter.out.security;

import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

final class IdentityJson {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private IdentityJson() {}
    @SuppressWarnings("unchecked")
    static Map<String,Object> object(byte[] bytes) {
        try {
            var result=JSON.readValue(bytes,Map.class);
            if(result==null) throw CustomerAuthenticationFailure.failed();
            return result;
        } catch(RuntimeException invalid) { throw CustomerAuthenticationFailure.failed(); }
    }
    static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(RuntimeException invalid) { throw CustomerAuthenticationFailure.failed(); }
    }
}
