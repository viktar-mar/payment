package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface LoadPaymentEventPort { List<PaymentEvent> findByPayment(SiteId siteId, PaymentId paymentId); }

