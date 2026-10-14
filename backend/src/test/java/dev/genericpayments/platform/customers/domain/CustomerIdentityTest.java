package dev.genericpayments.platform.customers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerIdentityTest {
    private static final CustomerIdentityId IDENTITY_ID = new CustomerIdentityId(UUID.randomUUID());
    private static final SiteId SITE_ID = new SiteId(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final CustomerId OTHER_CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final Instant INITIAL_TIME = Instant.parse("2026-10-10T10:00:00Z");
    private static final String SUBJECT_DIGEST = "v1:opaque-subject-digest";

    @ParameterizedTest
    @EnumSource(CustomerAuthenticationMode.class)
    void recordsVerifiedIdentityWithoutRawSubject(CustomerAuthenticationMode mode) {
        CustomerIdentity identity = CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID, mode,
            "google", SUBJECT_DIGEST, INITIAL_TIME);

        assertThat(identity.mode()).isEqualTo(mode);
        assertThat(identity.issuerKey()).isEqualTo("google");
        assertThat(identity.subjectDigest()).isEqualTo(SUBJECT_DIGEST);
        assertThat(identity.verifiedAt()).isEqualTo(INITIAL_TIME);
        assertThat(identity.createdAt()).isEqualTo(INITIAL_TIME);
        assertThat(identity.lastUsedAt()).isEqualTo(INITIAL_TIME);
        assertThat(identity.toString()).doesNotContain(SUBJECT_DIGEST);
    }

    @Test
    void identityOwnershipCannotMoveBetweenCustomers() {
        CustomerIdentity identity = create();
        assertThatCode(() -> identity.assertOwnedBy(CUSTOMER_ID)).doesNotThrowAnyException();
        assertThatThrownBy(() -> identity.assertOwnedBy(OTHER_CUSTOMER_ID)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> identity.assertOwnedBy(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> identity.assertOwnedBy(new SiteId(UUID.randomUUID()), CUSTOMER_ID))
            .isInstanceOf(DomainValidationException.class);
        assertThatCode(() -> identity.assertOwnedBy(SITE_ID, CUSTOMER_ID)).doesNotThrowAnyException();
    }

    @Test
    void touchingIdentityPreservesOwnershipAndVerification() {
        CustomerIdentity initial = create();
        CustomerIdentity touched = initial.touch(INITIAL_TIME.plusSeconds(10));
        assertThat(initial.lastUsedAt()).isEqualTo(INITIAL_TIME);
        assertThat(touched.lastUsedAt()).isEqualTo(INITIAL_TIME.plusSeconds(10));
        assertThat(touched.id()).isEqualTo(IDENTITY_ID);
        assertThat(touched.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(touched.siteId()).isEqualTo(SITE_ID);
        assertThat(touched.issuerKey()).isEqualTo("google");
        assertThat(touched.subjectDigest()).isEqualTo(SUBJECT_DIGEST);
        assertThat(touched.createdAt()).isEqualTo(INITIAL_TIME);
        assertThat(touched.verifiedAt()).isEqualTo(INITIAL_TIME);
        assertThatThrownBy(() -> touched.touch(INITIAL_TIME.plusSeconds(9))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> touched.touch(null)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "go", "Google", "https://issuer.example", "bad_key", "has space"})
    void rejectsInvalidStableIssuerKeys(String issuerKey) {
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID,
            CustomerAuthenticationMode.OIDC_TOKEN, issuerKey, SUBJECT_DIGEST, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "raw-subject", "raw@example.com", "v0:digest", "v1:", "v1:with space"})
    void rejectsUnversionedOrMalformedSubjectDigests(String digest) {
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID,
            CustomerAuthenticationMode.OIDC_TOKEN, "google", digest, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingRequiredValuesAndUnboundedIdentityKeys() {
        assertThatThrownBy(() -> new CustomerIdentityId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(null, SITE_ID, CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN,
            "google", SUBJECT_DIGEST, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, null, CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN,
            "google", SUBJECT_DIGEST, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, null, CustomerAuthenticationMode.OIDC_TOKEN,
            "google", SUBJECT_DIGEST, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID, null,
            "google", SUBJECT_DIGEST, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN,
            "google", SUBJECT_DIGEST, null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN,
            "g".repeat(64), SUBJECT_DIGEST, INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN,
            "google", "v1:" + "d".repeat(510), INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
    }

    private CustomerIdentity create() {
        return CustomerIdentity.create(IDENTITY_ID, SITE_ID, CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN,
            "google", SUBJECT_DIGEST, INITIAL_TIME);
    }
}
