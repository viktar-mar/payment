package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.shared.adapter.out.security.Sha256DigestAdapter;
import dev.genericpayments.platform.shared.adapter.out.security.AesGcmEncryptedSecretAdapter;
import dev.genericpayments.platform.shared.adapter.out.security.DataEncryptionProperties;
import dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.out.IdempotencyPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyServiceTest {
    private final CanonicalFingerprint fingerprint=new CanonicalFingerprint(new Sha256DigestAdapter());

    @Test void fieldOrderCurrenciesUuidsAndNestedMapOrderAreCanonical() {
        var first=new LinkedHashMap<String,Object>(); first.put("amount_minor",1999L); first.put("currency","eur");
        first.put("site_id","ABCDEFAB-1234-1234-1234-ABCDEFABCDEF"); first.put("metadata",Map.of("second","b","first","a"));
        var second=new LinkedHashMap<String,Object>(); second.put("metadata",Map.of("first","a","second","b"));
        second.put("site_id",UUID.fromString("abcdefab-1234-1234-1234-abcdefabcdef")); second.put("currency","EUR"); second.put("amount_minor",1999L);
        assertThat(fingerprint.hash(first)).isEqualTo(fingerprint.hash(second)).matches("[a-f0-9]{64}");
        assertThat(fingerprint.hash(first)).isEqualTo("831a3e4fd60c35a47eb1d51b9afdf8ddf86688927b1067ed86c886d4217ecd68");
    }

    @Test void metadataPropertyNamesPermitPunctuationWithoutWeakeningTransportGuards() {
        assertThat(fingerprint.hash(Map.of("metadata",Map.of("a.b","é","z","value"))))
            .isEqualTo(fingerprint.hash(Map.of("metadata",Map.of("z","value","a.b","é"))));
        for (String name : List.of("request.id","client.secret","api.key","checkout.token","cookie","authorization"))
            assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("metadata",Map.of(name,"value"))));
        for (String name : List.of("bad\ud800","bad\udc00","bad\nname"))
            assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("metadata",Map.of(name,"value"))));
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("unknown.field","value")));
    }

    @Test void arbitraryMetadataValuesAreNotRequiredToBeBusinessCurrencyOrIdentity() {
        assertThat(fingerprint.hash(Map.of("metadata",Map.of("currency","display-label","site_id","partner-reference"))))
            .matches("[a-f0-9]{64}");
        // Technical values are case-sensitive literals, not business currencies.
        assertThat(fingerprint.hash(Map.of("metadata",Map.of("currency","eur"))))
            .isNotEqualTo(fingerprint.hash(Map.of("metadata",Map.of("currency","EUR"))));
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("currency","display-label")));
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("site_id","partner-reference")));
    }

    @Test void businessValuesTypesAndArrayOrderRemainSignificant() {
        assertThat(fingerprint.hash(Map.of("amount_minor",1999))).isNotEqualTo(fingerprint.hash(Map.of("amount_minor",2000)));
        assertThat(fingerprint.hash(Map.of("value",1))).isNotEqualTo(fingerprint.hash(Map.of("value","1")));
        assertThat(fingerprint.hash(Map.of("items",List.of("a","b")))).isNotEqualTo(fingerprint.hash(Map.of("items",List.of("b","a"))));
    }

    @ParameterizedTest @ValueSource(ints={0xD800,0xDC00})
    void malformedUnicodeCannotCollideWithLiteralQuestionMark(int surrogate) {
        String malformed=String.valueOf((char)surrogate);
        assertThat(malformed).isNotEqualTo("?");
        assertThat(fingerprint.hash(Map.of("external_order_id","?"))).matches("[a-f0-9]{64}");
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("external_order_id",malformed)));
    }

    @ParameterizedTest @ValueSource(ints={0xD800,0xDC00})
    void nestedMalformedUnicodeIsRejected(int surrogate) {
        String malformed="prefix"+(char)surrogate+"suffix";
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("metadata",Map.of("description",malformed))));
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("items",List.of("valid",malformed))));
    }

    @Test void validSupplementaryUnicodeRemainsSupportedAndStable() {
        String emoji=new String(Character.toChars(0x1F600));
        assertThat(fingerprint.hash(Map.of("metadata",Map.of("description",emoji),"currency","eur")))
            .isEqualTo(fingerprint.hash(Map.of("currency","EUR","metadata",Map.of("description","😀"))))
            .isNotEqualTo(fingerprint.hash(Map.of("metadata",Map.of("description","?"),"currency","EUR")));
    }

    @Test void secretsTransportAndUnsupportedValuesAreRejectedRecursively() {
        for(String field:List.of("authorization","headers","request_id","checkoutToken","client_secret","api-key","token","password","idempotency_key")) {
            assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of(field,"redacted")));
            assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("metadata",Map.of(field,"redacted"))));
        }
        for(Object value:List.of(Double.NaN,Double.POSITIVE_INFINITY,new Object(),new Date(),Set.of("a","b")))
            assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("value",value)));
        var withNull=new HashMap<String,Object>(); withNull.put("value",null);
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(withNull));
        assertThatIllegalArgumentException().isThrownBy(() -> fingerprint.hash(Map.of("site_id","bad-id")));
    }

    @Test void expiryAfterBlockingClaimNeverDecryptsAndShredsBeforeReturningExpired() {
        Instant start=Instant.parse("2026-10-04T00:00:00Z");
        var time=new AtomicReference<>(start);
        Clock clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return time.get();}
        };
        var encryption=new AesGcmEncryptedSecretAdapter(new DataEncryptionProperties(Base64.getEncoder().encodeToString(new byte[32]),"local-v1"));
        var scope=Scope.partner(UUID.randomUUID(),Operation.PAYMENT_CREATE);
        var resource=new Resource(ResourceType.PAYMENT,UUID.randomUUID());
        var live=new IdempotencyRecord(UUID.randomUUID(),scope.value(),"key","a".repeat(64),Status.COMPLETED,resource,201,SecretState.LIVE,
            encryption.encrypt("fragment"),start.plusSeconds(60),start.plus(Duration.ofDays(30)),start,start,1);
        var state=new AtomicReference<>(live);
        IdempotencyPort delayed=new IdempotencyPort() {
            public Claim claim(IdempotencyRecord candidate,Instant supplied) {
                if (!supplied.isBefore(live.secretExpiresAt())) state.set(new IdempotencyRecord(live.id(),live.scope(),live.key(),live.fingerprint(),
                    live.status(),resource,201,SecretState.EXPIRED,null,live.secretExpiresAt(),live.expiresAt(),start,supplied,2));
                time.set(start.plusSeconds(60));
                return new Claim(false,state.get());
            }
            public IdempotencyRecord complete(Ownership owner,Completion completion,Instant now){throw new UnsupportedOperationException();}
            public void expireSecret(UUID id,Instant now) {
                assertThat(id).isEqualTo(live.id());
                state.set(new IdempotencyRecord(live.id(),live.scope(),live.key(),live.fingerprint(),live.status(),resource,201,
                    SecretState.EXPIRED,null,live.secretExpiresAt(),live.expiresAt(),start,now,2));
            }
            public int cleanup(Instant now){throw new UnsupportedOperationException();}
        };
        var service=new IdempotencyService(delayed,encryption,new MicrometerStage2TelemetryAdapter(new SimpleMeterRegistry(),Set.of()),new Sha256DigestAdapter(),clock);
        assertThat(service.begin(scope,"key","a".repeat(64))).isInstanceOf(IdempotencyService.ExpiredSecret.class);
        assertThat(state.get().secret()).isNull();
    }
}
