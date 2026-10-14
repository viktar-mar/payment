package dev.genericpayments.platform.customers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerTest {
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final SiteId SITE_ID = new SiteId(UUID.randomUUID());
    private static final Instant INITIAL_TIME = Instant.parse("2026-10-10T10:00:00Z");
    private static final String EMAIL_CIPHERTEXT_REF = "ciphertext-ref-1";
    private static final String EMAIL_DIGEST = "v1:opaque-email-digest";

    @Test
    void createsActiveCustomerWithoutProfileEmail() {
        Customer customer = Customer.create(CUSTOMER_ID, SITE_ID, null, null, INITIAL_TIME);

        assertThat(customer.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(customer.encryptedEmailRef()).isEmpty();
        assertThat(customer.emailDigest()).isEmpty();
        assertThat(customer.anonymizedAt()).isEmpty();
        assertThat(customer.createdAt()).isEqualTo(INITIAL_TIME);
        assertThat(customer.updatedAt()).isEqualTo(INITIAL_TIME);
        assertThat(customer.version()).isZero();
    }

    @Test
    void transitionsPreserveSiteAndOriginalCustomer() {
        Customer initial = Customer.create(CUSTOMER_ID, SITE_ID, null, null, INITIAL_TIME);
        Customer withEmail = initial.attachEmail(EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME.plusSeconds(1));
        Customer touched = withEmail.touch(INITIAL_TIME.plusSeconds(2));
        Customer anonymized = touched.anonymize(INITIAL_TIME.plusSeconds(3));

        assertThat(initial.encryptedEmailRef()).isEmpty();
        assertThat(withEmail.encryptedEmailRef()).contains(EMAIL_CIPHERTEXT_REF);
        assertThat(withEmail.emailDigest()).contains(EMAIL_DIGEST);
        assertThat(touched.updatedAt()).isEqualTo(INITIAL_TIME.plusSeconds(2));
        for (Customer customer : new Customer[] {withEmail, touched, anonymized}) {
            assertThat(customer.id()).isEqualTo(CUSTOMER_ID);
            assertThat(customer.siteId()).isEqualTo(SITE_ID);
            assertThat(customer.createdAt()).isEqualTo(INITIAL_TIME);
            assertThat(customer.version()).isZero();
        }
    }

    @Test
    void anonymizationIsIrreversibleAndDropsProfileEmail() {
        Customer active = Customer.create(CUSTOMER_ID, SITE_ID, EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME);
        Customer anonymized = active.anonymize(INITIAL_TIME.plusSeconds(1));

        assertThat(anonymized.status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(anonymized.encryptedEmailRef()).isEmpty();
        assertThat(anonymized.emailDigest()).isEmpty();
        assertThat(anonymized.anonymizedAt()).contains(INITIAL_TIME.plusSeconds(1));
        assertThat(anonymized.updatedAt()).isEqualTo(INITIAL_TIME.plusSeconds(1));
        assertThat(anonymized.anonymize(INITIAL_TIME.plusSeconds(2))).isSameAs(anonymized);
        assertThatThrownBy(() -> anonymized.attachEmail(EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME.plusSeconds(2)))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> anonymized.touch(INITIAL_TIME.plusSeconds(2)))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void matchingEmailDoesNotMergeCustomersOrSites() {
        Customer first = Customer.create(CUSTOMER_ID, SITE_ID, EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME);
        SiteId otherSite = new SiteId(UUID.randomUUID());
        CustomerId otherId = new CustomerId(UUID.randomUUID());
        Customer second = Customer.create(otherId, otherSite, EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.siteId()).isEqualTo(otherSite);
    }

    @Test
    void requiresEmailReferenceAndDigestTogether() {
        assertThatThrownBy(() -> Customer.create(CUSTOMER_ID, SITE_ID, EMAIL_CIPHERTEXT_REF, null, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Customer.create(CUSTOMER_ID, SITE_ID, null, EMAIL_DIGEST, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        Customer customer = Customer.create(CUSTOMER_ID, SITE_ID, null, null, INITIAL_TIME);
        assertThatThrownBy(() -> customer.attachEmail(null, null, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "raw@example.com", "digest", "v0:digest", "v1:", "v1:with space"})
    void rejectsInvalidEmailDigestOnAttachment(String digest) {
        Customer customer = Customer.create(CUSTOMER_ID, SITE_ID, null, null, INITIAL_TIME);
        assertThatThrownBy(() -> customer.attachEmail(EMAIL_CIPHERTEXT_REF, digest, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingRequiredValuesAndBackwardTransitions() {
        assertThatThrownBy(() -> new CustomerId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Customer.create(null, SITE_ID, null, null, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Customer.create(CUSTOMER_ID, null, null, null, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Customer.create(CUSTOMER_ID, SITE_ID, null, null, null))
            .isInstanceOf(DomainValidationException.class);
        Customer customer = Customer.create(CUSTOMER_ID, SITE_ID, null, null, INITIAL_TIME).touch(INITIAL_TIME.plusSeconds(10));
        assertThatThrownBy(() -> customer.touch(INITIAL_TIME.plusSeconds(9))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> customer.attachEmail(EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> customer.anonymize(INITIAL_TIME)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> customer.touch(null)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void diagnosticsRedactProfileReferences() {
        Customer customer = Customer.create(CUSTOMER_ID, SITE_ID, EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST, INITIAL_TIME);
        assertThat(customer.toString()).doesNotContain(EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST);
    }
}
