package dev.genericpayments.platform.subscriptions.application.port.out;
import java.util.function.Supplier;
public interface SubscriptionTransactionPort { <T>T required(Supplier<T> work); <T>T requiresNew(Supplier<T> work); boolean active(); }
