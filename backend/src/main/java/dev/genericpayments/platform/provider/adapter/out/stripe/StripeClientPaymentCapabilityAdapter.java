package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Ephemeral capability only. SDK models, secrets and exceptions never cross into stored facts. */
@Component
@Transactional(propagation=Propagation.NEVER)
public class StripeClientPaymentCapabilityAdapter implements ClientPaymentCapabilityPort {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final LoadPaymentAccountPort accounts;
    private final LoadPaymentPort payments;
    private final ProviderOperationStore operations;
    private final StripePaymentProviderAdapter provider;
    public StripeClientPaymentCapabilityAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            LoadPaymentAccountPort accounts,LoadPaymentPort payments,ProviderOperationStore operations,StripePaymentProviderAdapter provider) {
        this.credentials=credentials;this.clients=clients;this.verification=verification;this.accounts=accounts;this.payments=payments;
        this.operations=operations;this.provider=provider;
    }
    @Override public ClientPaymentCapability retrieve(PaymentAccount account,Payment payment) {
        var binding=credentials.resolve(account);
        if(account.status()!=PaymentAccountStatus.ACTIVE||accounts.findById(account.id()).filter(account::equals).isEmpty()
                ||payments.findByAccountAndId(account.id(),payment.id()).filter(payment::equals).isEmpty())throw StripePaymentSnapshotMapper.mismatch();
        verification.verify(account);
        var receipt=operations.find(account.id(),ProviderOperationKeys.prepare(payment.id())).orElseThrow(StripePaymentSnapshotMapper::mismatch);
        if(receipt.status()!=ProviderOperation.Status.ACKNOWLEDGED||receipt.kind()!=ProviderOperation.Kind.PAYMENT_PREPARE
                ||!receipt.resourceId().equals(payment.id().value())||!receipt.externalAccountId().equals(account.externalAccountId())
                ||!receipt.credentialsRef().equals(account.credentialsRef())||!receipt.fingerprint().equals(provider.fingerprint(account,payment))
                ||!Objects.equals(receipt.providerReference(),payment.providerPaymentReference()))throw StripePaymentSnapshotMapper.mismatch();
        try {
            var intent=clients.create(binding).v1().paymentIntents().retrieve(payment.providerPaymentReference());
            StripePaymentSnapshotMapper.requireIntent(account,payment,intent);
            if(!Set.of(ProviderState.Status.REQUIRES_PAYMENT_METHOD,ProviderState.Status.REQUIRES_ACTION)
                    .contains(StripePaymentSnapshotMapper.normalizedStatus(intent.getStatus())))throw StripePaymentRequests.unavailable();
            var secret=intent.getClientSecret();
            if(secret==null||!secret.startsWith(intent.getId()+"_secret_")||secret.length()>1024
                    ||!secret.matches("[A-Za-z0-9_-]+"))throw StripePaymentRequests.unavailable();
            return new ClientPaymentCapability(binding.publishableKey(),secret);
        } catch(PaymentApplicationException safe) { throw safe; }
        catch(Exception unsafe) { throw new PaymentApplicationException("PROVIDER_TEMPORARILY_UNAVAILABLE",503,"Provider temporarily unavailable"); }
    }
}
