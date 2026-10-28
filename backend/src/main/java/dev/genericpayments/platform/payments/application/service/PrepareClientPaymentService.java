package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.PrepareClientPaymentUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class PrepareClientPaymentService implements PrepareClientPaymentUseCase {
    private final CheckoutAuthorizationService authorization;
    private final LoadPaymentAccountPort accounts;
    private final PaymentProviderRegistry providers;
    private final PaymentPreparationCoordinator preparation;
    private final ClientPaymentAttemptTransaction transaction;
    private final ClientPaymentCapabilityPort capabilities;
    private final CheckoutReturnUrlPort returnUrls;
    private final LoadPaymentAttemptPort attempts;
    private final ProviderOperationStore operations;
    public PrepareClientPaymentService(CheckoutAuthorizationService authorization,LoadPaymentAccountPort accounts,PaymentProviderRegistry providers,
            PaymentPreparationCoordinator preparation,ClientPaymentAttemptTransaction transaction,ClientPaymentCapabilityPort capabilities,
            CheckoutReturnUrlPort returnUrls,LoadPaymentAttemptPort attempts,ProviderOperationStore operations) {
        this.authorization=authorization;this.accounts=accounts;this.providers=providers;this.preparation=preparation;
        this.transaction=transaction;this.capabilities=capabilities;this.returnUrls=returnUrls;this.attempts=attempts;this.operations=operations;
    }
    @Override public ClientPaymentSession prepare(PrepareClientPaymentCommand command) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Client session requires no ambient transaction");
        IdempotencyRecord.validateText(command.idempotencyKey(),255);
        var authorized=authorize(command);
        var initial=authorized;
        var account=accounts.findById(authorized.payment().paymentAccountId()).orElseThrow(CatalogCheckoutTransaction::capability);
        transaction.requireCurrent(authorized,account,authorized.payment());
        var provider=providers.select(account);
        if(provider.capabilities(account).confirmationMode()!=PaymentProviderCapabilities.ConfirmationMode.CLIENT_PROVIDER)throw CatalogCheckoutTransaction.capability();
        var returnUrl=returnUrls.find(authorized.site().id(),command.origin()).orElseThrow(CatalogCheckoutTransaction::capability);
        if(authorized.payment().providerPaymentReference()==null) {
            if(authorized.payment().status()!=dev.genericpayments.platform.payments.domain.PaymentStatus.CREATED)throw ClientPaymentAttemptTransaction.conflict();
            try {
                preparation.prepare(new PreparePaymentCommand(authorized.payment(),account,authorized.site().key().value(),command.requestId(),command.idempotencyKey()));
            } catch(IllegalStateException conflict) {
                if(!"PAYMENT_STATE_CONFLICT".equals(conflict.getMessage()))throw conflict;
            }
            authorized=authorize(command);
            transaction.requireCurrent(initial,account,authorized.payment());
            if(authorized.payment().providerPaymentReference()==null)
                throw new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN",503,"Provider result unknown");
        }
        var payment=authorized.payment();
        // Original dispatch identity is durable even before the first client baseline exists.
        var original=operations.find(account.id(),ProviderOperationKeys.prepare(payment.id())).orElseThrow(ClientPaymentAttemptTransaction::conflict);
        if(original.kind()!=ProviderOperation.Kind.PAYMENT_PREPARE||original.status()!=ProviderOperation.Status.ACKNOWLEDGED
                ||!original.resourceId().equals(payment.id().value())||!original.accountId().equals(account.id())
                ||!original.externalAccountId().equals(account.externalAccountId())||!original.credentialsRef().equals(account.credentialsRef())
                ||!payment.providerPaymentReference().equals(original.providerReference()))throw ClientPaymentAttemptTransaction.conflict();
        var state=provider.retrievePayment(ProviderPaymentLookup.forPayment(account,payment.id(),payment.providerPaymentReference()));
        ProviderEvidenceValidator.requirePayment(account,payment,state);
        if(!Set.of(ProviderState.Status.REQUIRES_PAYMENT_METHOD,ProviderState.Status.REQUIRES_ACTION).contains(state.status()))throw ClientPaymentAttemptTransaction.conflict();
        var attempt=transaction.begin(authorized,account,command.idempotencyKey(),command.requestId(),state.evidence().failedChargeReference());
        // The preceding proxied transaction has committed before any capability is obtained.
        var capability=capabilities.retrieve(account,payment);
        var current=authorize(command);
        transaction.requireCurrent(authorized,account,current.payment());
        var latestAttempt=attempts.findById(current.site().id(),attempt.id()).orElseThrow(ClientPaymentAttemptTransaction::conflict).value();
        transaction.requireAttempt(current.payment(),account,latestAttempt);
        if(!returnUrls.find(current.site().id(),command.origin()).filter(returnUrl::equals).isPresent())throw CatalogCheckoutTransaction.capability();
        return new ClientPaymentSession(current.session().id(),payment.id().value(),attempt.id().value(),capability,current.session().expiresAt(),returnUrl);
    }
    private CheckoutAuthorizationService.Authorized authorize(PrepareClientPaymentCommand c) {
        return authorization.authorize(c.checkoutId(),c.origin(),c.checkoutToken(),"confirm");
    }
}
