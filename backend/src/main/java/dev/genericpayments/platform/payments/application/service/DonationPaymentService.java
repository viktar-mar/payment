package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class DonationPaymentService implements CreateDonationPaymentUseCase {
    private final PaymentCreationTransaction creation;
    private final LoadPaymentPort payments;
    private final LoadPaymentAccountPort accounts;
    private final CheckoutSessionPort sessions;
    private final RandomTokenPort tokens;
    private final DigestPort digest;
    private final Clock clock;
    private final Stage2TelemetryPort telemetry;
    private final PaymentProviderRegistry providers;
    public DonationPaymentService(PaymentCreationTransaction creation,LoadPaymentPort payments,LoadPaymentAccountPort accounts,
        CheckoutSessionPort sessions,RandomTokenPort tokens,DigestPort digest,Clock clock,Stage2TelemetryPort telemetry,PaymentProviderRegistry providers) {
        this.creation=creation;this.payments=payments;this.accounts=accounts;this.sessions=sessions;this.tokens=tokens;this.digest=digest;this.clock=clock;
        this.telemetry=telemetry;this.providers=providers;
    }
    @Override public void preflight(Site site) { requireTelemetry(site,account(site.paymentAccountId())); }
    @Override public Result create(CreateDonationPaymentCommand c) {
        var account=account(c.site().paymentAccountId());
        var context=requireTelemetry(c.site(),account);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var payment=Payment.donation(new PaymentId(UUID.randomUUID()),c.site().id(),c.customerId(),account.id(),
            c.donationId(),c.snapshot(),c.amount(),TaxMode.NONE,Map.of(),now);
        var created=creation.createDonation(new PreparePaymentCommand(payment,account,c.site().key().value(),c.requestId(),c.idempotencyKey()));
        payment=created.payment();
        if(created.created()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { telemetry.paymentCreated(context); }
        });
        var token=tokens.issue(32);
        var session=new CheckoutSession(c.checkoutId(),payment.id(),payment.siteId(),digest.sha256Hex(token),c.origin(),
            Set.of("read","confirm"),now.plus(Duration.ofHours(24)),now);
        sessions.save(session);
        return result(session,payment,account,token);
    }
    @Override public Result load(SiteId siteId,UUID checkoutId,String token) {
        var session=sessions.findById(checkoutId).filter(s->s.siteId().equals(siteId)).orElseThrow(CheckoutAuthorizationService::invalidToken);
        var payment=payments.findById(siteId,session.paymentId()).filter(p->p.source()==PaymentSource.DONATION)
            .orElseThrow(CheckoutAuthorizationService::invalidToken);
        return result(session,payment,account(payment.paymentAccountId()),token);
    }
    private PaymentAccount account(PaymentAccountId id) {
        var account=accounts.findById(id).filter(a->a.status()==PaymentAccountStatus.ACTIVE)
            .orElseThrow(CatalogCheckoutTransaction::capability);
        capabilities(account).require(TaxMode.NONE);
        return account;
    }
    private PaymentProviderCapabilities capabilities(PaymentAccount account) {
        // Fake retains its local descriptor. Stripe's descriptor resolves config only, never SDK I/O.
        if(account.provider()==PaymentProviderType.FAKE)return PaymentProviderCapabilities.fake();
        try { return providers.select(account).capabilities(account); }
        catch(RuntimeException unavailable) { throw CatalogCheckoutTransaction.capability(); }
    }
    private Stage2TelemetryPort.Context requireTelemetry(Site site,PaymentAccount account) {
        try {
            var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.valueOf(account.provider().name()));
            telemetry.requireContext(context);
            return context;
        } catch(IllegalArgumentException misconfigured) {
            throw new PaymentApplicationException("SITE_NOT_AVAILABLE",503,"Site not available");
        }
    }
    private Result result(CheckoutSession session,Payment p,PaymentAccount account,String token) {
        var view=new CheckoutView(session.id(),p.id().value(),p.money().minorUnits(),p.money().currency(),null,
            account.provider().name(),capabilities(account).confirmationMode().name(),Map.of(),p.status().name(),session.expiresAt());
        return new Result(new CheckoutCreation(view,token),p);
    }
}
