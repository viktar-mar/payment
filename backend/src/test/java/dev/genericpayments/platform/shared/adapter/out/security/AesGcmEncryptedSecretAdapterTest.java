package dev.genericpayments.platform.shared.adapter.out.security;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AesGcmEncryptedSecretAdapterTest {
    private static String key() { return Base64.getEncoder().encodeToString(new byte[32]); }
    private AesGcmEncryptedSecretAdapter crypto() {
        return new AesGcmEncryptedSecretAdapter(new DataEncryptionProperties(key(), "local-v1"));
    }

    @Test void tokensContainExactly256RandomBitsAndRejectInvalidSizes() {
        var tokens = new SecureRandomTokenAdapter();
        var values = new HashSet<String>();
        for (int i=0;i<100;i++) {
            String token=tokens.issue(32);
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            values.add(token);
        }
        assertThat(values).hasSize(100);
        for (int size : new int[]{0,-1,4097,Integer.MAX_VALUE})
            assertThatIllegalArgumentException().isThrownBy(() -> tokens.issue(size));
    }

    @Test void digestUsesUtf8AndLowercaseHex() {
        var digest=new Sha256DigestAdapter();
        assertThat(digest.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(digest.sha256Hex("é")).isEqualTo("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c");
        assertThatIllegalArgumentException().isThrownBy(() -> digest.sha256Hex(null));
    }

    @ParameterizedTest @ValueSource(ints={0xD800,0xDC00})
    void digestRejectsMalformedUnicodeInsteadOfReplacingIt(int surrogate) {
        var digest=new Sha256DigestAdapter();
        assertThatIllegalArgumentException().isThrownBy(() -> digest.sha256Hex(String.valueOf((char)surrogate)))
            .withMessage("Digest input must be valid Unicode");
    }

    @Test void encryptsUtf8WithFreshNonceAndDefensiveCopies() {
        var crypto=crypto(); var first=crypto.encrypt("temporary-π"); var second=crypto.encrypt("temporary-π");
        assertThat(crypto.decrypt(first)).isEqualTo("temporary-π");
        assertThat(first.keyVersion()).isEqualTo("local-v1");
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(new String(first.ciphertext(),StandardCharsets.UTF_8)).doesNotContain("temporary-π");
        byte[] ciphertext=first.ciphertext(), nonce=first.nonce();
        var copy=new EncryptedValue(ciphertext,nonce,first.keyVersion());
        ciphertext[0]^=1; nonce[0]^=1;
        copy.ciphertext()[0]^=1; copy.nonce()[0]^=1;
        assertThat(crypto.decrypt(copy)).isEqualTo("temporary-π");
        assertThat(first.toString()).doesNotContain("temporary",Arrays.toString(first.ciphertext()));
    }

    @Test void alteredCiphertextNonceVersionAndWrongKeyFailClosed() {
        var crypto=crypto(); var value=crypto.encrypt("temporary-π");
        byte[] ciphertext=value.ciphertext(); ciphertext[0]^=1;
        byte[] nonce=value.nonce(); nonce[0]^=1;
        for (var invalid:List.of(new EncryptedValue(ciphertext,value.nonce(),"local-v1"),
            new EncryptedValue(value.ciphertext(),nonce,"local-v1"),
            new EncryptedValue(value.ciphertext(),value.nonce(),"other-v1"))) {
            assertThatThrownBy(() -> crypto.decrypt(invalid)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Secret decryption failed").hasNoCause();
        }
        byte[] other=new byte[32]; Arrays.fill(other,(byte)7);
        var wrong=new AesGcmEncryptedSecretAdapter(new DataEncryptionProperties(Base64.getEncoder().encodeToString(other),"local-v1"));
        assertThatThrownBy(() -> wrong.decrypt(value)).hasMessage("Secret decryption failed").hasNoCause();
    }

    @Test void configurationRejectsMissingMalformedAndWrongLengthWithoutEchoingMaterial() {
        new ApplicationContextRunner().withUserConfiguration(DataEncryptionConfiguration.class)
            .run(context -> assertThat(context).hasFailed());
        for (String invalid:List.of("", "malformed!",Base64.getEncoder().encodeToString(new byte[31]),
            Base64.getEncoder().encodeToString(new byte[33]))) {
            new ApplicationContextRunner().withUserConfiguration(DataEncryptionConfiguration.class)
                .withPropertyValues("payment.data-encryption.key-base64="+invalid,"payment.data-encryption.key-version=local-v1")
                .run(context -> assertThat(context).hasFailed());
        }
        for (String version:List.of("", "  ","x".repeat(129)))
            assertThatIllegalArgumentException().isThrownBy(() -> new DataEncryptionProperties(key(),version));
        new ApplicationContextRunner().withUserConfiguration(DataEncryptionConfiguration.class)
            .withPropertyValues("payment.data-encryption.key-base64="+key(),"payment.data-encryption.key-version=local-v1")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AesGcmEncryptedSecretAdapter.class));
    }
}
