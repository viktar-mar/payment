package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;
/** Owned preflight; never a grant. Repeated under lifecycle locks before applying observations. */
@Component
public class SubscriptionReadPreparation implements dev.genericpayments.platform.subscriptions.application.port.in.PrepareSubscriptionReadUseCase {
    private final SubscriptionOperationStore operations;private final TrialEligibilityStore trials;
    public SubscriptionReadPreparation(SubscriptionOperationStore operations,TrialEligibilityStore trials){this.operations=operations;this.trials=trials;}
    public SubscriptionOperation financial(Subscription s){
        var owner=trials.find(s.siteId(),s.customerId(),s.productId()).filter(e->e.consumedAt()==null&&e.reservedByOperation()!=null).map(e->{
            var o=operations.find(s.siteId(),s.id(),e.reservedByOperation()).orElseThrow(SubscriptionRuleErrors::conflict);
            validate(s,o);
            if(o.kind()!=SubscriptionOperation.Kind.CREATE||!CreateSubscriptionFingerprint.startsTrial(o.fingerprint()))throw SubscriptionRuleErrors.conflict();
            return o;
        }).orElse(null);
        var financial=operations.unresolved(s.siteId(),s.id()).stream().filter(o->o.kind()!=SubscriptionOperation.Kind.RECONCILE&&o.kind()!=SubscriptionOperation.Kind.PORTAL).sorted(Comparator.comparing(SubscriptionOperation::id)).toList();
        if(financial.size()>1)throw SubscriptionRuleErrors.conflict();
        var selected=financial.isEmpty()?owner:financial.getFirst();
        if(selected!=null){validate(s,selected);if(selected.kind()==SubscriptionOperation.Kind.CREATE)CreateSubscriptionFingerprint.startsTrial(selected.fingerprint());}
        return selected;
    }
    private void validate(Subscription s,SubscriptionOperation o){
        if(!s.siteId().equals(o.siteId())||!s.customerId().equals(o.customerId())||!s.id().equals(o.subscriptionId())
            ||s.providerReference()!=null&&o.providerReference()!=null&&!s.providerReference().equals(o.providerReference()))throw SubscriptionRuleErrors.conflict();
        if(o.kind()==SubscriptionOperation.Kind.CREATE&&!Objects.equals(o.providerOperationKey(),"subscription:"+s.paymentAccountId().value()+":"+o.id()+":CREATE"))throw SubscriptionRuleErrors.conflict();
    }
}
