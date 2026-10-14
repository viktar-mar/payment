package dev.genericpayments.platform.customers.adapter.out.security;

import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/** A decoder-local response, not an HTTP client. Even unimplemented/future RestOperations paths fail closed. */
final class ValidatedJwkRestOperations extends RestTemplate {
    static final URI MEMORY_URI=URI.create("https://validated-jwks.invalid/material");
    private final String json;
    ValidatedJwkRestOperations(ValidatedPublicJwkSet material) {
        super((uri,method)->{ throw CustomerAuthenticationFailure.failed(); });
        json=material.publicMaterial().canonicalJson();
    }
    @Override public <T> ResponseEntity<T> exchange(RequestEntity<?> request,Class<T> responseType) {
        try {
            if(!MEMORY_URI.equals(request.getUrl()) || request.getMethod()!=HttpMethod.GET || responseType!=String.class)
                throw CustomerAuthenticationFailure.failed();
            return ResponseEntity.ok(responseType.cast(json));
        } catch(RuntimeException ignored) {
            throw CustomerAuthenticationFailure.failed();
        }
    }
    @Override public String toString() { return "ValidatedJwkRestOperations[redacted]"; }
}
