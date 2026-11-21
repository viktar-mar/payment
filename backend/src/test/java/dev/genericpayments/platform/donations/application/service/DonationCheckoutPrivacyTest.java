package dev.genericpayments.platform.donations.application.service;

import static dev.genericpayments.platform.donations.application.service.DonationCheckoutPrivacy.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DonationCheckoutPrivacyTest {
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test void acceptsCafeInDonationIdAndCiphertext() {
        String donation = """
            {"id":"cafe0000-0000-4000-8000-000000000002","message_ciphertext":"\\\\x01cafe02"}
            """;
        assertPrivateRows(donation, "{}", CUSTOMER_ID);
    }

    @Test void acceptsCafeInPaymentEventHash() {
        String event = """
            {"payment_id":"00000000-0000-4000-8000-000000000484",
             "payload_hash":"51fa1412cafebdb2397674bc153a455c24e17bbc50119450d6ff108edd6aadef"}
            """;
        assertPrivateRows("{}", event, CUSTOMER_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {MESSAGE_NFD, MESSAGE_NFC, MESSAGE_ASCII})
    void rejectsDonorTextInEitherRow(String leaked) {
        String row = """
            {"technical_metadata":{"note":"%s"}}
            """.formatted(leaked);
        assertThrows(AssertionError.class, () -> assertPrivateRows(row, "{}", CUSTOMER_ID));
        assertThrows(AssertionError.class, () -> assertPrivateRows("{}", row, CUSTOMER_ID));
    }

    @Test void rejectsCustomerIdInDonationAndMessageFieldInEvent() {
        String donation = """
            {"customer_id":"%s"}
            """.formatted(CUSTOMER_ID);
        String event = """
            {"technical_metadata":{"message":null}}
            """;
        assertThrows(AssertionError.class, () -> assertPrivateRows(donation, "{}", CUSTOMER_ID));
        assertThrows(AssertionError.class, () -> assertPrivateRows("{}", event, CUSTOMER_ID));
    }
}
