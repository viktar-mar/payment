package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** Payment lock serializes all browser keys; attempt, baseline, journal and safe receipt commit together. */
public class ClientPaymentAttemptTransaction {
    private final LoadPaymentPort payments;
    private final LoadPaymentAttemptPort attempts;
    private final SavePaymentAttemptPort saveAttempts;
    private final ClientPaymentAttemptPort baselines;
    private final CheckoutSessionPort sessions;
    private final LoadSitePort sites;
    private final LoadPaymentAccountPort accounts;
    private final CustomerStore customers;
    private final IdempotencyService idempotency;
    private final PaymentPhaseSupport journal;
    private final Clock clock;
    public ClientPaymentAttemptTransaction(LoadPaymentPort payments,LoadPaymentAttemptPort attempts,SavePaymentAttemptPort saveAttempts,
            ClientPaymentAttemptPort baselines,CheckoutSessionPort sessions,LoadSitePort sites,LoadPaymentAccountPort accounts,
            CustomerStore customers,IdempotencyService idempotency,PaymentPhaseSupport journal,Clock clock) {
        this.payments=payments;this.attempts=attempts;this.saveAttempts=saveAttempts;this.baselines=baselines;this.sessions=sessions;
        this.sites=sites;this.accounts=accounts;this.customers=customers;this.idempotency=idempotency;this.journal=journal;this.clock=clock;
    }
    @Transactional
    public PaymentAttempt begin(CheckoutAuthorizationService.Authorized authorized,PaymentAccount account,String key,String requestId,String previousFailedCharge) {
        var locked=payments.findForAttemptStart(authorized.site().id(),authorized.payment().id()).orElseThrow(ClientPaymentAttemptTransaction::conflict);
        if(locked.hasLiveLease(clock.instant()))throw conflict();
        requireCurrent(authorized,account,locked.value());
        var p=locked.value();
        var scope=Scope.publicOperation(p.siteId().value(),Stage2TelemetryPort.Operation.CHECKOUT_CLIENT_SESSION,authorized.session().id());
        var fingerprint=idempotency.fingerprint(Map.of("checkout_id",authorized.session().id(),"payment_id",p.id().value(),
            "account_id",account.id().value(),"customer_ownership",p.customerId()==null?"anonymous":"customer:"+p.customerId()));
        var outcome=idempotency.begin(scope,key,fingerprint);
        if(outcome instanceof IdempotencyService.Conflict)throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");
        if(outcome instanceof IdempotencyService.Completed completed) {
            if(completed.resource().type()!=ResourceType.ATTEMPT)throw conflict();
            var a=attempts.findById(p.siteId(),new PaymentAttemptId(completed.resource().id())).orElseThrow(ClientPaymentAttemptTransaction::conflict).value();
            requireAttempt(p,account,a);return a;
        }
        if(!(outcome instanceof IdempotencyService.Owner owner))throw conflict();
        var previous=attempts.findByPayment(p.siteId(),p.id());
        var pending=previous.stream().map(LoadPaymentAttemptPort.StoredAttempt::value)
            .filter(a -> a.status()!=PaymentAttemptStatus.FAILED&&a.status()!=PaymentAttemptStatus.CANCELLED&&a.status()!=PaymentAttemptStatus.SUCCEEDED).toList();
        PaymentAttempt attempt;
        if(!pending.isEmpty()) {
            if(pending.size()!=1)throw conflict();
            attempt=pending.getFirst();requireAttempt(p,account,attempt);
        } else {
            if(!p.canStartAttempt())throw conflict();
            var id=new PaymentAttemptId(UUID.randomUUID());
            attempt=PaymentAttempt.start(id,p.id(),previous.stream().mapToInt(a -> a.value().sequence()).max().orElse(0)+1,
                ProviderOperationKeys.confirm(id),"CARD",clock.instant()).withProviderIntentReference(p.providerPaymentReference(),clock.instant())
                .requiresPaymentMethod(clock.instant());
            saveAttempts.insert(attempt,clock.instant());
            baselines.record(new ClientPaymentAttemptPort.Baseline(id.value(),account.id(),p.id(),p.customerId(),
                account.credentialsRef(),account.externalAccountId(),previousFailedCharge));
            journal.event(p,attempt.providerOperationKey(),"created",PaymentEventType.ATTEMPT_CREATED,requestId,key,null);
        }
        idempotency.complete(owner,new Resource(ResourceType.ATTEMPT,attempt.id().value()),200,null);
        return attempt;
    }
    /** Also called before network work and after capability retrieval. No provider I/O. */
    public void requireCurrent(CheckoutAuthorizationService.Authorized authorized,PaymentAccount account,Payment payment) {
        var session=sessions.findById(authorized.session().id()).orElseThrow(CheckoutAuthorizationService::invalidToken);
        if(!session.equals(authorized.session())||!session.scopes().contains("confirm"))throw CheckoutAuthorizationService.invalidToken();
        if(!clock.instant().isBefore(session.expiresAt()))throw CheckoutAuthorizationService.expired(payment.id().value());
        var site=sites.findById(session.siteId()).orElseThrow(CheckoutAuthorizationService::unavailable);
        if(site.status()!=SiteStatus.ACTIVE)throw CheckoutAuthorizationService.unavailable();
        CheckoutAuthorizationService.requireOrigin(site,session.origin());
        if(site.paymentMethodPolicy().mode()==PaymentMethodPolicyMode.ALLOWLIST&&!site.paymentMethodPolicy().allowedMethods().contains(PaymentMethod.CARD))
            throw CatalogCheckoutTransaction.capability();
        if(account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX||account.status()!=PaymentAccountStatus.ACTIVE
                ||!"stripe-sandbox-default".equals(account.credentialsRef())
                ||accounts.findById(account.id()).filter(account::equals).isEmpty())throw CatalogCheckoutTransaction.capability();
        var original=authorized.payment();
        if(!payment.id().equals(session.paymentId())||!payment.siteId().equals(session.siteId())||!payment.paymentAccountId().equals(account.id())
                ||!Objects.equals(original.customerId(),payment.customerId())||!original.money().equals(payment.money())
                ||original.taxMode()!=payment.taxMode()||original.source()!=payment.source()
                ||original.providerPaymentReference()!=null&&!Objects.equals(original.providerPaymentReference(),payment.providerPaymentReference()))throw conflict();
        requirePayable(payment);
        // Null is the explicit guest/anonymous branch; there is no second browser Customer bearer.
        if(payment.customerId()!=null&&customers.findById(payment.siteId(),new CustomerId(payment.customerId()))
                .filter(customer -> customer.status()==CustomerStatus.ACTIVE).isEmpty())throw CheckoutAuthorizationService.invalidToken();
        for(var stored:attempts.findClientAttemptsByPayment(payment.siteId(),payment.id()))requireBaseline(payment,account,stored.value());
    }
    public void requireAttempt(Payment p,PaymentAccount account,PaymentAttempt attempt) {
        if(!attempt.paymentId().equals(p.id())||!Objects.equals(attempt.providerIntentReference(),p.providerPaymentReference())
                ||!Set.of(PaymentAttemptStatus.CREATED,PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD,PaymentAttemptStatus.REQUIRES_ACTION).contains(attempt.status()))throw conflict();
        requireBaseline(p,account,attempt);
    }
    private void requireBaseline(Payment p,PaymentAccount account,PaymentAttempt a) {
        var baseline=baselines.findByAttempt(a.id().value()).orElseThrow(ClientPaymentAttemptTransaction::conflict);
        if(!baseline.paymentId().equals(p.id())||!baseline.accountId().equals(account.id())||!Objects.equals(baseline.customerId(),p.customerId())
                ||!baseline.credentialsRef().equals(account.credentialsRef())||!baseline.externalAccountId().equals(account.externalAccountId()))throw conflict();
    }
    static void requirePayable(Payment payment) {
        if(!Set.of(PaymentStatus.CREATED,PaymentStatus.REQUIRES_PAYMENT_METHOD,PaymentStatus.REQUIRES_ACTION).contains(payment.status()))throw conflict();
        if(payment.source()==PaymentSource.SUBSCRIPTION)throw CatalogCheckoutTransaction.capability();
    }
    static PaymentApplicationException conflict() { return new PaymentApplicationException("PAYMENT_STATE_CONFLICT",409,"Payment state conflict"); }
}
