package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.payments.application.port.out.AdminPaymentAuditPort;
import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class AdminPaymentAuditAdapter implements AdminPaymentAuditPort {
    private final AuditLogPort audit;private final Clock clock;
    public AdminPaymentAuditAdapter(AuditLogPort audit,Clock clock){this.audit=audit;this.clock=clock;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void record(Action action,UUID actor,UUID payment,UUID resource,String requestId,Map<String,String> facts){
        var safe=new TreeMap<>(facts);safe.put("payment_id",payment.toString());
        audit.append(new AuditEntry(UUID.randomUUID(),"ADMIN",actor.toString(),AuditAction.valueOf(action.name()),action==Action.PAYMENT_REFUND_REQUESTED?"REFUND":"PAYMENT",resource.toString(),requestId,null,safe,clock.instant()));
    }
}
