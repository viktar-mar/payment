package dev.genericpayments.platform.partners.domain;
public enum PartnerScope {
    PAYMENTS_CREATE("payments:create"), PAYMENTS_READ("payments:read"),
    CUSTOMERS_READ("customers:read"), CUSTOMERS_MANAGE("customers:manage"), DONATIONS_READ("donations:read"),
    SUBSCRIPTIONS_READ("subscriptions:read"), SUBSCRIPTIONS_MANAGE("subscriptions:manage");
    private final String value;
    PartnerScope(String value) { this.value = value; }
    public String value() { return value; }
    public static PartnerScope parse(String value) {
        for (var scope : values()) if (scope.value.equals(value)) return scope;
        throw new dev.genericpayments.platform.shared.domain.DomainValidationException("Invalid Partner scope");
    }
}
