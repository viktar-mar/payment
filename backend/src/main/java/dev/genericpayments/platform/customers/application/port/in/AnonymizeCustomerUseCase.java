package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.UUID;

public interface AnonymizeCustomerUseCase {
    record Command(SiteId siteId,CustomerId customerId,UUID confirmation,String idempotencyKey,
                   String actorType,String actorId,String requestId,String sourceIp) {
        @Override public String toString(){return "AnonymizeCustomerCommand[siteId="+siteId+", customerId="+customerId+"]";}
    }
    record Result(CustomerId customerId,CustomerStatus status) {}
    Result anonymize(Command command);
}
