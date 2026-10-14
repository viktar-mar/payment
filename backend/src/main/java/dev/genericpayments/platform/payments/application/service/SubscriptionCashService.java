package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Imports provider-owned cash without provider IO or a one-time PaymentAttempt. */
@Service
public class SubscriptionCashService implements RecordSubscriptionCashUseCase {
    private final SubscriptionCashIdentityPort identities;
    private final SavePaymentPort payments;
    private final LoadPaymentAccountPort accounts;
    private final LoadSitePort sites;
    private final PaymentTransitionService transitions;
    private final Clock clock;
    public SubscriptionCashService(SubscriptionCashIdentityPort identities,SavePaymentPort payments,LoadPaymentAccountPort accounts,LoadSitePort sites,PaymentTransitionService transitions,Clock clock) {
        this.identities=identities;this.payments=payments;this.accounts=accounts;this.sites=sites;this.transitions=transitions;this.clock=clock;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Result record(Command c) {
        var account=accounts.findById(c.accountId()).orElseThrow(()->new IllegalArgumentException("Cash account missing"));
        if(account.provider()!=c.provider())throw new IllegalArgumentException("Cash provider mismatch");
        var site=sites.findById(c.siteId()).orElseThrow(()->new IllegalArgumentException("Cash Site missing"));
        var old=identities.lock(c.accountId(),c.providerPaymentReference());
        Payment p;
        if(old.isPresent()) {
            p=old.get();
            if(!p.siteId().equals(c.siteId())||!p.customerId().equals(c.customerId())||!p.subscriptionContext().equals(c.context())
                ||!p.money().equals(c.amount())||p.taxMode()!=c.taxMode())throw new IllegalArgumentException("Cash object terms conflict");
        } else {
            p=payments.save(Payment.subscription(new PaymentId(UUID.randomUUID()),c.siteId(),c.customerId(),c.accountId(),c.context(),c.amount(),c.taxMode(),Map.of(),clock.instant())
                .withProviderPaymentReference(c.providerPaymentReference(),clock.instant()));
        }
        var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.valueOf(account.provider().name()));
        var result=transitions.subscriptionCash(p,c.status(),context,c.occurredAt(),c.factKey());
        return new Result(result.payment().id(),result.changed());
    }
}
