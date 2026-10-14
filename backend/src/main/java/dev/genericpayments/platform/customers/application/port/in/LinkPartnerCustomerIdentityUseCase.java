package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.partners.domain.SiteApiKeyId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Objects;

public interface LinkPartnerCustomerIdentityUseCase {
    enum Outcome { LINKED, ALREADY_LINKED }
    record Command(SiteId siteId,SiteApiKeyId actorId,CustomerId customerId,CustomerAuthenticationMode mode,
            String issuerKey,String subject,String idempotencyKey,String requestId,String sourceIp) {
        public Command {
            Objects.requireNonNull(siteId,"site id"); Objects.requireNonNull(actorId,"actor id");
            Objects.requireNonNull(customerId,"customer id"); Objects.requireNonNull(mode,"mode");
            Objects.requireNonNull(issuerKey,"issuer key"); Objects.requireNonNull(subject,"subject");
            Objects.requireNonNull(idempotencyKey,"idempotency key"); Objects.requireNonNull(requestId,"request id");
        }
        @Override public String toString(){return "Command[siteId="+siteId+", customerId="+customerId+", credentials=redacted]";}
    }
    record Result(CustomerIdentityId identityId,Outcome outcome,int responseStatus) {
        public Result { Objects.requireNonNull(identityId,"identity id"); Objects.requireNonNull(outcome,"outcome"); }
    }
    Result link(Command command);
}
