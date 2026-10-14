package dev.genericpayments.platform.customers.application.port.out;

import java.util.function.Supplier;

public interface CustomerTransactionPort {
    <T> T required(Supplier<T> work);
}
