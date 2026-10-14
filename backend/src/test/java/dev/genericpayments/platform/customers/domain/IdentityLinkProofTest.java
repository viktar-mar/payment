package dev.genericpayments.platform.customers.domain;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class IdentityLinkProofTest {
    private static final Instant NOW=Instant.parse("2026-10-10T10:00:00Z");
    private static final String PRIMARY="v2:active-subject-digest";
    private static final String RETAINED="v1:retained-subject-digest";

    @Test void exposesAnImmutableNonemptyDistinctAliasSetWithDeterministicPrimary() {
        var source=new ArrayList<>(List.of(PRIMARY,RETAINED));
        var proof=proof(PRIMARY,source);
        source.clear();

        assertThat(proof.subjectDigest()).isEqualTo(PRIMARY);
        assertThat(proof.subjectDigests()).containsExactly(PRIMARY,RETAINED);
        assertThatThrownBy(() -> proof.subjectDigests().add("v3:other")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(proof.consume(NOW.plusSeconds(1)).subjectDigests()).containsExactly(PRIMARY,RETAINED);
        assertThat(proof.toString()).doesNotContain(PRIMARY,RETAINED);
    }

    @Test void rejectsMissingDuplicateMalformedOrNonprimaryAliasSets() {
        assertThatThrownBy(() -> proof(PRIMARY,null)).isInstanceOfAny(NullPointerException.class,DomainValidationException.class);
        assertThatThrownBy(() -> proof(PRIMARY,List.of())).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> proof(PRIMARY,List.of(PRIMARY,PRIMARY))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> proof(PRIMARY,List.of(RETAINED,PRIMARY))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> proof(PRIMARY,List.of(PRIMARY,"raw-subject"))).isInstanceOf(DomainValidationException.class);
    }

    private IdentityLinkProof proof(String primary,List<String> aliases) {
        return new IdentityLinkProof(UUID.randomUUID(),new SiteId(UUID.randomUUID()),"https://shop.example.test",
            CustomerAuthenticationMode.SITE_TOKEN,"site-provider",primary,aliases,"v1:proof-token-digest",
            NOW,NOW,NOW.plusSeconds(300),null,0);
    }
}
