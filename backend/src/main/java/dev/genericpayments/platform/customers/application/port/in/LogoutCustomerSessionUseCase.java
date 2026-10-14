package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;

public interface LogoutCustomerSessionUseCase {
    void logout(CustomerSessionContext session);
}
