package dev.genericpayments.platform.customers.adapter.in.localfixture;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Explicitly non-production fixture; existing Admin session and CSRF filters protect every operation. */
@RestController
@Profile({"local","test"})
@ConditionalOnProperty(name="payment.customer-local-issuer.enabled",havingValue="true")
@RequestMapping("/api/v1/admin/local/customer-issuer")
public final class LocalCustomerIssuerController {
    public static final String ISSUER="https://local.customer-issuer.invalid";
    public static final String AUDIENCE="local-web";
    private final JWKSet keys;
    private final Clock clock;
    public LocalCustomerIssuerController(Environment environment,Clock clock,LocalCustomerIssuerGuard guard) {
        this.clock=clock;
        try {
            var path=Path.of(environment.getRequiredProperty("payment.customer-local-issuer.private-jwk-set"));
            if(Files.size(path)>16384)throw new IllegalArgumentException();
            keys=JWKSet.parse(Files.readString(path));
            if(keys.getKeys().size()!=2 || keys.getKeys().stream().anyMatch(k -> !k.isPrivate()))throw new IllegalArgumentException();
            if(!(keys.getKeyByKeyId("local-fixture-rs") instanceof RSAKey rsa) || rsa.size()<2048
                || !(keys.getKeyByKeyId("local-fixture-es") instanceof ECKey ec) || !ec.getCurve().equals(Curve.P_256))throw new IllegalArgumentException();
        } catch(Exception failure) {throw new IllegalStateException("Local Customer issuer fixture material is unavailable or invalid");}
    }
    @GetMapping("/public-keys") public Map<String,Object> publicKeys() {return keys.toPublicJWKSet().toJSONObject();}
    @PostMapping("/tokens") public Map<String,Object> token(@RequestBody FixtureRequest request) {
        if(request==null || !Set.of("RS256","ES256").contains(Objects.toString(request.algorithm(),""))
            || !safe(request.subject(),128) || (request.nonce()!=null&&!safe(request.nonce(),256)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local fixture request");
        try {
            var algorithm=JWSAlgorithm.parse(request.algorithm());
            var key=keys.getKeyByKeyId(algorithm.equals(JWSAlgorithm.RS256)?"local-fixture-rs":"local-fixture-es");
            var claims=new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(request.subject())
                .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(120)))
                .jwtID(UUID.randomUUID().toString());
            if(request.nonce()!=null)claims.claim("nonce",request.nonce());
            var jwt=new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),claims.build());
            jwt.sign(key instanceof RSAKey rsa?new RSASSASigner(rsa):new ECDSASigner((ECKey)key));
            return Map.of("token",jwt.serialize(),"expires_at",clock.instant().plusSeconds(120).toString());
        } catch(Exception failure) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Local fixture signing unavailable");}
    }
    private static boolean safe(String value,int maximum) {return value!=null&&!value.isBlank()&&value.length()<=maximum&&value.chars().allMatch(c -> c>=33&&c<=126);}
    public record FixtureRequest(String algorithm,String subject,String nonce) {
        @Override public String toString(){return "FixtureRequest[redacted]";}
    }
}
