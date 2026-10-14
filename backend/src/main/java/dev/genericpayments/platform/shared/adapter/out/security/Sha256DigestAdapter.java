package dev.genericpayments.platform.shared.adapter.out.security;

import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class Sha256DigestAdapter implements DigestPort {
    @Override public String sha256Hex(String value) {
        if (value == null) throw new IllegalArgumentException("Digest input is required");
        try {
            var utf8=StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(utf8);
            return HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Digest input must be valid Unicode");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
