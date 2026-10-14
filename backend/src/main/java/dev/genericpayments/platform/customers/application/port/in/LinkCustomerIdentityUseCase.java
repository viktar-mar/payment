package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerIdentityId;
import java.util.Objects;

public interface LinkCustomerIdentityUseCase {
    enum Outcome { LINKED, ALREADY_LINKED }
    record Command(CustomerSessionContext session,String proofToken,String requestId,String sourceIp) {
        public Command {
            Objects.requireNonNull(session,"session"); Objects.requireNonNull(proofToken,"proof token");
            Objects.requireNonNull(requestId,"request id");
        }
        @Override public String toString(){return "Command[session="+session.sessionId()+", proof=redacted]";}
    }
    record Result(CustomerIdentityId identityId,Outcome outcome) {
        public Result { Objects.requireNonNull(identityId,"identity id"); Objects.requireNonNull(outcome,"outcome"); }
    }
    Result link(Command command);
}
