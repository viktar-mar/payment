package dev.genericpayments.platform.payments.application.model;
public record CheckoutCreation(CheckoutView view, String checkoutToken) {
    @Override public String toString() { return "CheckoutCreation[view=" + view + ", token=redacted]"; }
}
