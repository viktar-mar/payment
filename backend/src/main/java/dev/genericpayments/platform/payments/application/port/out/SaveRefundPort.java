package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface SaveRefundPort {
 LoadRefundPort.StoredRefund insert(Refund refund);
 LoadRefundPort.StoredRefund save(LoadRefundPort.StoredRefund refund);
}

