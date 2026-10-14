package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.AdminPaymentViews.*;
import java.util.*;
public interface AdminPaymentReadPort { Page list(Query query);Optional<Details> details(UUID paymentId); }
