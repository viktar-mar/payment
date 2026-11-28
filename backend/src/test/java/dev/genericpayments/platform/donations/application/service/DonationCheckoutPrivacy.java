package dev.genericpayments.platform.donations.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

final class DonationCheckoutPrivacy {
    // The accent-stripped text also matches the NFD prefix and cannot occur in a hex value.
    static final String MESSAGE_ASCII = "solidarite";
    static final String MESSAGE_NFD = MESSAGE_ASCII + "\u0301";
    static final String MESSAGE_NFC = "solidarité";

    private DonationCheckoutPrivacy() {}

    static void assertPrivateRows(String donationRow, String eventRow, UUID customerId) {
        assertThat(donationRow).doesNotContain(MESSAGE_NFC, MESSAGE_ASCII, customerId.toString());
        assertThat(eventRow).doesNotContain(MESSAGE_NFC, MESSAGE_ASCII, "message");
    }
}
