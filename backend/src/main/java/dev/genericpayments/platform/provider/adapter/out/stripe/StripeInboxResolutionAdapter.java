package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** At most identity + intent + latest Charge + distinct failed Charge; all reads precede financial locks. */
@Component
@Transactional(propagation=Propagation.NEVER)
public class StripeInboxResolutionAdapter implements ProviderInboxResolutionPort {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final LoadPaymentAccountPort accounts;
    private final LoadPaymentPort payments;
    private final PaymentTransitionPort transitions;
    private final ProviderOperationStore operations;
    private final StripePaymentProviderAdapter provider;
    public StripeInboxResolutionAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            PaymentAccountVerificationPort verification,LoadPaymentAccountPort accounts,LoadPaymentPort payments,
            PaymentTransitionPort transitions,ProviderOperationStore operations,StripePaymentProviderAdapter provider) {
        this.credentials=credentials;this.clients=clients;this.verification=verification;this.accounts=accounts;
        this.payments=payments;this.transitions=transitions;this.operations=operations;this.provider=provider;
    }
    @Override public ResolvedProviderReceipt resolve(ProviderReferenceReceipt receipt) {
        // Task 10 must supply verified refund-specific money/ownership proof before this family can apply.
        if(receipt.objectType()!=ProviderReferenceReceipt.ObjectType.PAYMENT)throw unresolved();
        var account=accounts.findById(receipt.accountId()).orElseThrow(StripeInboxResolutionAdapter::unresolved);
        var binding=credentials.resolve(account);
        if(account.status()!=PaymentAccountStatus.ACTIVE)throw StripePaymentSnapshotMapper.mismatch();
        verification.verify(account);
        try {
            var client=clients.create(binding);
            var intent=client.v1().paymentIntents().retrieve(receipt.objectId());
            if(intent==null||!receipt.objectId().equals(intent.getId()))throw StripePaymentSnapshotMapper.mismatch();
            var identity=transitions.resolve(account.id(),receipt.objectId());
            PaymentId id;
            if(identity.isPresent())id=identity.get().paymentId();
            else {
                // Metadata only selects a candidate. Original acknowledged request proof below is mandatory.
                try {id=new PaymentId(UUID.fromString(intent.getMetadata().get("payment_id")));}
                catch(RuntimeException invalid){throw unresolved();}
            }
            var payment=payments.findByAccountAndId(account.id(),id).orElseThrow(StripeInboxResolutionAdapter::unresolved);
            var operation=operations.find(account.id(),ProviderOperationKeys.prepare(id)).orElseThrow(StripeInboxResolutionAdapter::unresolved);
            String fingerprint=provider.fingerprint(account,payment);
            if(operation.status()!=ProviderOperation.Status.ACKNOWLEDGED)throw unresolved();
            if(operation.kind()!=ProviderOperation.Kind.PAYMENT_PREPARE||!operation.resourceId().equals(id.value())
                    ||!operation.accountId().equals(account.id())||!operation.operationKey().equals(ProviderOperationKeys.prepare(id))
                    ||!operation.externalAccountId().equals(account.externalAccountId())||!operation.credentialsRef().equals(account.credentialsRef())
                    ||!fingerprint.equals(operation.fingerprint())||!receipt.objectId().equals(operation.providerReference())
                    ||payment.providerPaymentReference()!=null&&!receipt.objectId().equals(payment.providerPaymentReference()))throw StripePaymentSnapshotMapper.mismatch();
            if(intent.getMetadata()==null||!id.value().toString().equals(intent.getMetadata().get("payment_id"))
                    ||!payment.siteId().value().toString().equals(intent.getMetadata().get("site_id"))
                    ||!payment.source().name().equals(intent.getMetadata().get("source")))throw StripePaymentSnapshotMapper.mismatch();
            // Only the in-memory projection is bound here. Final mapping repair is fenced under the Payment lock.
            var bound=payment.withProviderPaymentReference(receipt.objectId(),payment.updatedAt());
            StripePaymentSnapshotMapper.requireIntent(account,bound,intent);
            String latest=intent.getLatestCharge(),failed=StripePaymentSnapshotMapper.failedReference(intent);
            String selected=latest!=null?latest:failed;
            var charge=selected==null?null:client.v1().charges().retrieve(selected);
            var state=StripePaymentSnapshotMapper.payment(account,bound,intent,charge);
            var failedCharge=failed==null?null:failed.equals(selected)?charge:client.v1().charges().retrieve(failed);
            if(failedCharge!=null) {
                StripePaymentSnapshotMapper.requireFailedCharge(bound,intent,failedCharge);
                var e=state.evidence();
                state=new ProviderState(state.reference(),state.status(),new ProviderEvidence(e.provider(),e.accountId(),e.mode(),e.externalAccountId(),
                    e.money(),e.paymentReference(),e.chargeReference(),failedCharge.getId(),e.captured()));
            }
            return new ResolvedProviderReceipt(receipt,id,null,state,fingerprint,account,payment);
        } catch(PaymentApplicationException safe){throw safe;}
        catch(IllegalStateException safe){if("UNRESOLVED_OBJECT".equals(safe.getMessage()))throw safe;throw unavailable();}
        catch(Exception unsafe){throw unavailable();}
    }
    private static IllegalStateException unresolved(){return new IllegalStateException("UNRESOLVED_OBJECT");}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("PROVIDER_TEMPORARILY_UNAVAILABLE",503,"Provider temporarily unavailable");}
}
