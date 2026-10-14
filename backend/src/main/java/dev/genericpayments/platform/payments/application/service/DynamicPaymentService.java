package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
public final class DynamicPaymentService implements CreateDynamicPaymentUseCase {
    private final DynamicPaymentTransaction phases; private final HandoffService handoffs; private final LoadPaymentPort payments;
    private final LoadPaymentAccountPort accounts; private final PaymentProviderRegistry providers;
    private final PaymentPreparationCoordinator preparation; private final Stage2TelemetryPort telemetry;
    public DynamicPaymentService(DynamicPaymentTransaction phases,HandoffService handoffs,LoadPaymentPort payments,LoadPaymentAccountPort accounts,
        PaymentProviderRegistry providers,PaymentPreparationCoordinator preparation,Stage2TelemetryPort telemetry) {
        this.phases=phases; this.handoffs=handoffs; this.payments=payments; this.accounts=accounts; this.providers=providers; this.preparation=preparation; this.telemetry=telemetry;
    }
    @Override public HandoffCreation create(CreateDynamicPaymentCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Dynamic coordinator requires no ambient transaction");
        var site=handoffs.site(command.siteId());
        var origin=PartnerPaymentRules.origin(site,command.checkoutOrigin());
        PartnerPaymentRules.returnUrl(command.returnUrl(),origin);
        PartnerPaymentRules.metadata(command.metadata());
        if (command.currency()==null || !command.currency().matches("[A-Z]{3}") || command.amountMinor()<1) throw PartnerPaymentRules.invalid();
        // Domain validation of metadata/order/money precedes claiming idempotency.
        var now=handoffs.now();
        Payment.dynamic(new PaymentId(UUID.randomUUID()),site.id(),command.customerId(),site.paymentAccountId(),command.externalOrderId(),
            dev.genericpayments.platform.catalog.domain.Money.of(command.amountMinor(),command.currency()),dev.genericpayments.platform.catalog.domain.TaxMode.NONE,
            command.metadata()==null ? Map.of() : command.metadata(),now);
        var account=PartnerPaymentRules.account(accounts,providers,site.paymentAccountId());
        PartnerPaymentRules.preflight(telemetry,site,account);
        DynamicPaymentTransaction.Phase result;
        try { result=phases.create(command,site,account,origin); }
        catch (DataIntegrityViolationException conflict) {
            var cause=conflict.getMostSpecificCause();
            if (cause.getMessage()!=null && cause.getMessage().contains("uq_payments_site_external_order"))
                throw new PaymentApplicationException("EXTERNAL_ORDER_ALREADY_EXISTS",409,"External order already exists");
            throw conflict;
        }
        Payment payment; HandoffSession handoff; String token;
        if (result.outcome() instanceof IdempotencyService.Owner) {
            payment=result.payment(); handoff=result.handoff(); token=result.token();
            telemetry.paymentCreated(new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.valueOf(account.provider().name())));
        } else {
            var replay=handoffs.replay(site.id(),result.outcome());
            handoff=replay.handoff(); token=replay.token(); payment=payments.findById(site.id(),handoff.paymentId()).orElseThrow(PartnerPaymentRules::notFound);
            if(!Objects.equals(payment.customerId(),command.customerId()))
                throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");
            account=PartnerPaymentRules.account(accounts,providers,payment.paymentAccountId());
        }
        if (payment.status()==PaymentStatus.CREATED && payment.providerPaymentReference()==null)
            payment=preparation.prepare(new PreparePaymentCommand(original(payment),account,site.key().value(),command.requestId(),command.idempotencyKey()));
        if (payment.status()==PaymentStatus.CREATED && payment.providerPaymentReference()==null)
            throw new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN",503,"Provider result unknown",Map.of("payment_id",payment.id().value()));
        return HandoffCreation.of(payment,token,handoff.expiresAt());
    }
    private static Payment original(Payment p) {
        return new Payment(p.id(),p.siteId(),p.customerId(),p.paymentAccountId(),p.source(),p.priceId(),p.priceSnapshot(),p.externalOrderId(),p.money(),p.taxMode(),
            p.taxAmountMinor(),PaymentStatus.CREATED,null,0,0,p.createdAt(),p.createdAt(),p.technicalMetadata());
    }
}
