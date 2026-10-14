package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore.PublicJwkSet;
import dev.genericpayments.platform.customers.application.port.out.PublicJwkSetValidationPort.KeyMetadata;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** Only created after bounded public signing-key validation; opaque/redacted at every diagnostic boundary. */
public final class ValidatedPublicJwkSet {
    private final PublicJwkSet material;
    private final Map<String,String> algorithms;
    private final List<KeyMetadata> metadata;
    private ValidatedPublicJwkSet(String json,Map<String,String> algorithms,List<KeyMetadata> metadata) {
        this.material=new PublicJwkSet(json); this.algorithms=Map.copyOf(algorithms); this.metadata=List.copyOf(metadata);
    }
    static ValidatedPublicJwkSet parse(PublicJwkSet input) {
        try {
            if(input==null || input.canonicalJson().length()>262144) throw CustomerAuthenticationFailure.failed();
            byte[] bytes=input.canonicalJson().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>262144) throw CustomerAuthenticationFailure.failed();
            var root=IdentityJson.object(bytes);
            if(!root.keySet().equals(Set.of("keys")) || !(root.get("keys") instanceof List<?> keys) || keys.isEmpty() || keys.size()>20)
                throw CustomerAuthenticationFailure.failed();
            var canonical=new TreeMap<String,Map<String,Object>>(); var algorithms=new HashMap<String,String>();
            for(Object item:keys) {
                if(!(item instanceof Map<?,?> key)) throw CustomerAuthenticationFailure.failed();
                String kid=text(key,"kid",128); String kty=text(key,"kty",16);
                if(kid.isBlank() || kid.codePoints().anyMatch(Character::isISOControl) || canonical.containsKey(kid)) throw CustomerAuthenticationFailure.failed();
                Set<String> fields="RSA".equals(kty)?Set.of("kty","kid","use","key_ops","alg","n","e"):
                    Set.of("kty","kid","use","key_ops","alg","crv","x","y");
                if(!fields.containsAll(key.keySet()) || key.containsKey("use")&&!"sig".equals(key.get("use"))
                    || key.containsKey("key_ops")&&!List.of("verify").equals(key.get("key_ops"))) throw CustomerAuthenticationFailure.failed();
                String algorithm;
                var output=new TreeMap<String,Object>();
                output.put("kty",kty); output.put("kid",kid); output.put("use","sig"); output.put("key_ops",List.of("verify"));
                if("RSA".equals(kty)) {
                    byte[] modulus=decode(key,"n",1024); byte[] exponent=decode(key,"e",8);
                    var n=new BigInteger(1,modulus); var e=new BigInteger(1,exponent);
                    if(n.bitLength()<2048 || n.bitLength()>8192 || !n.testBit(0) || modulus[0]==0 || exponent[0]==0
                        || e.compareTo(BigInteger.valueOf(3))<0 || !e.testBit(0) || e.bitLength()>32) throw CustomerAuthenticationFailure.failed();
                    KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e));
                    algorithm="RS256"; output.put("n",key.get("n")); output.put("e",key.get("e"));
                } else if("EC".equals(kty) && "P-256".equals(key.get("crv"))) {
                    byte[] xb=decode(key,"x",32); byte[] yb=decode(key,"y",32);
                    if(xb.length!=32 || yb.length!=32) throw CustomerAuthenticationFailure.failed();
                    var x=new BigInteger(1,xb); var y=new BigInteger(1,yb);
                    var params=AlgorithmParameters.getInstance("EC"); params.init(new ECGenParameterSpec("secp256r1"));
                    var spec=params.getParameterSpec(ECParameterSpec.class); var curve=spec.getCurve(); var p=((ECFieldFp)curve.getField()).getP();
                    if(x.compareTo(p)>=0 || y.compareTo(p)>=0
                        || !y.multiply(y).mod(p).equals(x.multiply(x).multiply(x).add(curve.getA().multiply(x)).add(curve.getB()).mod(p)))
                        throw CustomerAuthenticationFailure.failed();
                    KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x,y),spec));
                    algorithm="ES256"; output.put("crv","P-256"); output.put("x",key.get("x")); output.put("y",key.get("y"));
                } else throw CustomerAuthenticationFailure.failed();
                if(key.containsKey("alg")&&!algorithm.equals(key.get("alg"))) throw CustomerAuthenticationFailure.failed();
                output.put("alg",algorithm); canonical.put(kid,output); algorithms.put(kid,algorithm);
            }
            var metadata=new ArrayList<KeyMetadata>();
            canonical.forEach((kid,key)->metadata.add(new KeyMetadata(kid,(String)key.get("kty"),(String)key.get("alg"),fingerprint(key))));
            return new ValidatedPublicJwkSet(IdentityJson.write(Map.of("keys",List.copyOf(canonical.values()))),algorithms,metadata);
        } catch(RuntimeException|GeneralSecurityException invalid) { throw CustomerAuthenticationFailure.failed(); }
    }
    private static String text(Map<?,?> key,String name,int max) {
        if(!(key.get(name) instanceof String value) || value.isBlank() || value.length()>max) throw CustomerAuthenticationFailure.failed();
        return value;
    }
    private static byte[] decode(Map<?,?> key,String name,int maxBytes) {
        String value=text(key,name,2*maxBytes);
        if(!value.matches("[A-Za-z0-9_-]+")) throw CustomerAuthenticationFailure.failed();
        byte[] bytes=Base64.getUrlDecoder().decode(value);
        if(bytes.length==0 || bytes.length>maxBytes || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))
            throw CustomerAuthenticationFailure.failed();
        return bytes;
    }
    public boolean contains(String kid) { return algorithms.containsKey(kid); }
    public String algorithm(String kid) { return algorithms.get(kid); }
    public PublicJwkSet publicMaterial() { return material; }
    public List<KeyMetadata> metadata() { return metadata; }
    /** RFC 7638 SHA-256 thumbprint bytes rendered as lowercase hexadecimal. */
    private static String fingerprint(Map<String,Object> key) {
        try {
            var members=new TreeMap<String,Object>(); members.put("kty",key.get("kty"));
            if("RSA".equals(key.get("kty"))) { members.put("e",key.get("e")); members.put("n",key.get("n")); }
            else { members.put("crv",key.get("crv")); members.put("x",key.get("x")); members.put("y",key.get("y")); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(IdentityJson.write(members).getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException unavailable) { throw CustomerAuthenticationFailure.failed(); }
    }
    @Override public String toString() { return "ValidatedPublicJwkSet[redacted]"; }
}
