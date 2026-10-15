package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.model.SafeFactJson;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.util.*;
public final class AdminReconciliationService {
    private final AdminPaymentReadPort reads;private final PaymentTransitionPort tx;private final IdempotencyService idem;private final AdminPaymentAuditPort audit;private final ReconcilePaymentService reconcile;
    private final LoadSitePort sites;private final LoadPaymentAccountPort accounts;private final PaymentProviderRegistry providers;private final Stage2TelemetryPort telemetry;
    public AdminReconciliationService(AdminPaymentReadPort reads,PaymentTransitionPort tx,IdempotencyService idem,AdminPaymentAuditPort audit,ReconcilePaymentService reconcile,LoadSitePort sites,LoadPaymentAccountPort accounts,PaymentProviderRegistry providers,Stage2TelemetryPort telemetry){this.reads=reads;this.tx=tx;this.idem=idem;this.audit=audit;this.reconcile=reconcile;this.sites=sites;this.accounts=accounts;this.providers=providers;this.telemetry=telemetry;}
    public void reconcile(PaymentId id,UUID actor,String key,String requestId){
        if(tx.hasAmbientTransaction())throw new IllegalStateException("Provider coordinator requires no ambient transaction");SafeFactJson.text(key,255);SafeFactJson.text(requestId,128);
        var siteId=tx.transaction(()->{var p=reads.details(id.value()).orElseThrow(PartnerPaymentRules::notFound).payment();
            var account=accounts.findById(new PaymentAccountId(p.paymentAccountId())).filter(a->a.status()==PaymentAccountStatus.ACTIVE).orElseThrow(PartnerPaymentRules::state);
            var site=sites.findById(new SiteId(p.siteId())).filter(s->s.status()==SiteStatus.ACTIVE&&(account.provider()==PaymentProviderType.STRIPE||s.paymentAccountId().value().equals(p.paymentAccountId()))).orElseThrow(PartnerPaymentRules::state);
            try{providers.select(account);}catch(IllegalStateException unavailable){throw PartnerPaymentRules.state();}PartnerPaymentRules.preflight(telemetry,site,account);
            var outcome=idem.begin(IdempotencyRecord.Scope.admin(site.id().value(),actor,Stage2TelemetryPort.Operation.RECONCILE),key,idem.fingerprint(Map.of("payment_id",id.value())));
            if(outcome instanceof IdempotencyService.Conflict)throw RefundCoordinator.conflict();
            if(outcome instanceof IdempotencyService.Completed completed){if(completed.resource().type()!=IdempotencyRecord.ResourceType.PAYMENT||!completed.resource().id().equals(id.value()))throw RefundCoordinator.conflict();return site.id();}
            if(!(outcome instanceof IdempotencyService.Owner owner))throw PartnerPaymentRules.state();
            idem.complete(owner,new IdempotencyRecord.Resource(IdempotencyRecord.ResourceType.PAYMENT,id.value()),202,null);
            audit.record(AdminPaymentAuditPort.Action.PAYMENT_RECONCILIATION_REQUESTED,actor,id.value(),id.value(),requestId,Map.of("status","ACCEPTED"));return site.id();
        });
        // Receipt completion is immutable. Replays resume the same target via its bounded lease;
        // known truth is a kernel no-op and current safe state is read independently by HTTP.
        try{reconcile.reconcile(siteId,id,requestId);}catch(PaymentApplicationException safe){throw safe;}catch(RuntimeException failure){throw new PaymentApplicationException("PROVIDER_TEMPORARILY_UNAVAILABLE",503,"Provider temporarily unavailable");}
    }
}
