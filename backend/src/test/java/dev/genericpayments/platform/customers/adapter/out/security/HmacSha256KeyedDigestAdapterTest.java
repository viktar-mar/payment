package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HmacSha256KeyedDigestAdapterTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static CustomerSecurityProperties properties(String active, Map<String,String> keys) {
        return new CustomerSecurityProperties(active, keys);
    }
    @Test void separatesEveryPurposeAndFramesTheVersion() {
        var one = new HmacSha256KeyedDigestAdapter(properties("v1",Map.of("v1",KEY)));
        var two = new HmacSha256KeyedDigestAdapter(properties("v2",Map.of("v2",KEY)));
        var digests = new HashSet<String>();
        for (var purpose : Purpose.values()) {
            var result = one.digest(purpose,"fixture-input");
            assertThat(result.equals(one.digest(purpose,"fixture-input"))).isTrue();
            assertThat(result.equals(two.digest(purpose,"fixture-input"))).isFalse();
            assertThat(digests.add(result.value())).isTrue();
            assertThat(one.matches(purpose,"fixture-input",result)).isTrue();
            assertThat(one.matches(purpose,"different",result)).isFalse();
        }
        assertThat(digests).hasSize(Purpose.values().length);
        assertThat(one.digest(Purpose.OIDC_NONCE,"fixture-input"))
            .isNotEqualTo(one.digest(Purpose.LINK_PROOF,"fixture-input"));
    }
    @Test void readsKnownRotatedVersionButWritesOnlyActiveVersion() {
        var old = new HmacSha256KeyedDigestAdapter(properties("v1",Map.of("v1",KEY)));
        var rotated = new HmacSha256KeyedDigestAdapter(properties("v2",Map.of("v1",KEY,"v2",Base64.getEncoder().encodeToString(new byte[48]))));
        assertThat(rotated.matches(Purpose.OTP_CODE,"fixture-input",old.digest(Purpose.OTP_CODE,"fixture-input"))).isTrue();
        assertThat(rotated.digest(Purpose.OTP_CODE,"fixture-input").keyVersion()).isEqualTo("v2");
        assertThat(rotated.matches(Purpose.SDK_SESSION,"fixture-input",old.digest(Purpose.OTP_CODE,"fixture-input"))).isFalse();
        assertThat(rotated.matches(Purpose.OTP_CODE,"fixture-input",new DigestValue("00".repeat(32),"unknown"))).isFalse();
        assertThat(rotated.matches(Purpose.OTP_CODE,"fixture-input",new DigestValue("malformed","v1"))).isFalse();
    }
    @Test void enumeratesEveryRetainedVersionInDeterministicActiveFirstOrder() {
        String v2=Base64.getEncoder().encodeToString(new byte[48]);
        String v10=Base64.getEncoder().encodeToString(new byte[64]);
        var rotated=new HmacSha256KeyedDigestAdapter(properties("v2",Map.of("v10",v10,"v1",KEY,"v2",v2)));

        var candidates=rotated.candidates(Purpose.IDENTITY_SUBJECT,"rotation-subject");

        assertThat(candidates).extracting(DigestValue::keyVersion).containsExactly("v2","v1","v10");
        assertThat(candidates).allSatisfy(candidate ->
            assertThat(rotated.matches(Purpose.IDENTITY_SUBJECT,"rotation-subject",candidate)).isTrue());
        assertThat(candidates.toString()).doesNotContain("rotation-subject");
    }
    @ParameterizedTest(name="invalid-key-{index}") @NullAndEmptySource @ValueSource(strings={"bad base64", "AA=="})
    void rejectsInvalidKeysWithoutEcho(String input) {
        var keys = new HashMap<String,String>(); keys.put("v1",input);
        assertThatThrownBy(() -> properties("v1",keys)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid Customer security configuration").hasNoCause();
    }
    @Test void rejectsMissingActiveKeysAndMalformedUnicode() {
        assertThatThrownBy(() -> properties("missing",Map.of("v1",KEY))).isInstanceOf(IllegalArgumentException.class);
        var digest = new HmacSha256KeyedDigestAdapter(properties("v1",Map.of("v1",KEY)));
        assertThatThrownBy(() -> digest.digest(Purpose.EMAIL_LOOKUP,"\ud800")).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThat(digest.digest(Purpose.OTP_CODE,"fixture-input").toString()).isEqualTo("DigestValue[redacted]");
        assertThat(properties("v1",Map.of("v1",KEY)).toString()).isEqualTo("CustomerSecurityProperties[redacted]");
    }
    @Test void rejectsVersionsThatCannotBePersistedByTheCustomerSchema() {
        assertThatThrownBy(() -> properties("test-v1",Map.of("test-v1",KEY)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid Customer security configuration")
            .hasNoCause();
        assertThatThrownBy(() -> properties("v01",Map.of("v01",KEY)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid Customer security configuration")
            .hasNoCause();
    }
}
