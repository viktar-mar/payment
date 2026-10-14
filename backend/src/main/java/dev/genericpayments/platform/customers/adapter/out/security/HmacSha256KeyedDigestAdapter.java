package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSha256KeyedDigestAdapter implements KeyedDigestPort {
    private final CustomerSecurityProperties properties;
    public HmacSha256KeyedDigestAdapter(CustomerSecurityProperties properties) { this.properties=properties; }
    @Override public DigestValue digest(Purpose purpose, String value) {
        String version=properties.activeKeyVersion();
        return digest(purpose,value,version);
    }
    @Override public List<DigestValue> candidates(Purpose purpose,String value) {
        String active=properties.activeKeyVersion();
        var versions=new ArrayList<>(properties.keyVersions());
        versions.remove(active); versions.sort(Comparator.naturalOrder()); versions.addFirst(active);
        return versions.stream().map(version -> digest(purpose,value,version)).toList();
    }
    @Override public boolean matches(Purpose purpose, String value, DigestValue expected) {
        if(expected==null || expected.value()==null || expected.keyVersion()==null || properties.key(expected.keyVersion())==null) return false;
        try {
            byte[] decoded=HexFormat.of().parseHex(expected.value());
            if(decoded.length!=32) return false;
            return MessageDigest.isEqual(compute(purpose,value,expected.keyVersion()),decoded);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private byte[] compute(Purpose purpose, String value, String version) {
        if(purpose==null || value==null) throw new IllegalArgumentException("Invalid digest input");
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.key(version),"HmacSHA256"));
            // Every field has a byte-length prefix: no separator/input ambiguity or version substitution.
            for(String field:new String[]{"gp:customer-digest:v1",purpose.name(),version,value}) {
                ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(field));
                mac.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array()); mac.update(bytes);
            }
            return mac.doFinal();
        } catch(CharacterCodingException malformed) { throw new IllegalArgumentException("Invalid digest input"); }
        catch(GeneralSecurityException unavailable) { throw new IllegalStateException("Digest engine unavailable"); }
    }
    private DigestValue digest(Purpose purpose,String value,String version) {
        return new DigestValue(HexFormat.of().formatHex(compute(purpose,value,version)),version);
    }
    @Override public String toString() { return "HmacSha256KeyedDigestAdapter[redacted]"; }
}
