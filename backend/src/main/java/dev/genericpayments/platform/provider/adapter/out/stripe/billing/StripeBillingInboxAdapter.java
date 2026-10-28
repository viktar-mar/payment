package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxResolutionPort;
import java.util.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import com.stripe.exception.StripeException;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership.*;

/** Bounded fresh identity reads. No financial operation/attempt/grant is created by a hint. */
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
public class StripeBillingInboxAdapter implements SubscriptionInboxResolutionPort {
    private final LoadPaymentAccountPort accounts;
    private final SubscriptionObjectPort objects;
    private final StripeBillingOwnership ownership;
    private final StripeBillingBindingPort bindings;
    private final SubscriptionOperationStore operations;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final StripeSubscriptionProviderAdapter provider;
    private final SubscriptionCommandTransaction commands;
    public StripeBillingInboxAdapter(LoadPaymentAccountPort accounts,SubscriptionObjectPort objects,StripeBillingOwnership ownership,
            StripeBillingBindingPort bindings,SubscriptionOperationStore operations,StripeCredentialResolver credentials,
            StripeClientFactory clients,PaymentAccountVerificationPort verification,StripeSubscriptionProviderAdapter provider,
            SubscriptionCommandTransaction commands){
        this.accounts=accounts;this.objects=objects;this.ownership=ownership;this.bindings=bindings;this.operations=operations;
        this.credentials=credentials;this.clients=clients;this.verification=verification;this.provider=provider;this.commands=commands;
    }
    public Optional<UUID> resolve(SubscriptionProviderEnvelope envelope){
        Objects.requireNonNull(envelope);
        if(envelope.provider()!=PaymentProviderType.STRIPE)return Optional.empty();
        var account=accounts.findById(envelope.accountId()).orElseThrow(StripeBillingOwnership::mismatch);
        ownership.requireAccount(account);
        try{
            return switch(envelope.eventType()){
                case "SUBSCRIPTION_CHANGED" -> subscription(account,envelope);
                case "SUBSCRIPTION_INVOICE_CHANGED" -> invoice(account,envelope.objectReference());
                // Admission is not Task7/8's original dispatch/method/schedule ownership proof.
                case "SUBSCRIPTION_SETUP_CHANGED","SUBSCRIPTION_SCHEDULE_CHANGED" -> Optional.empty();
                default -> throw mismatch();
            };
        }catch(StripeException malformed){throw mismatch();}
    }

    private Optional<UUID> subscription(PaymentAccount account,SubscriptionProviderEnvelope envelope)throws StripeException{
        String reference=envelope.objectReference();
        if(!reference.matches("sub_[A-Za-z0-9]{1,251}"))throw mismatch();
        var local=objects.resolve(PaymentProviderType.STRIPE,account.id(),SubscriptionObjectPort.Kind.SUBSCRIPTION,reference);
        if(local.isPresent())return prepared(account,local.get(),reference,null);
        // Exact admitted event only. Metadata selects an existing candidate; recoverCreate owns
        // request-key/window/fingerprint/fresh-resource proof and the durable provider receipt.
        if(!envelope.eventId().matches("evt_[A-Za-z0-9]{1,251}"))throw mismatch();
        verification.verify(account);
        var event=clients.create(credentials.resolve(account)).v1().events().retrieve(envelope.eventId());
        if(event==null||!envelope.eventId().equals(event.getId())||!"event".equals(event.getObject())
            ||!"2026-09-30.endive".equals(event.getApiVersion())||!Boolean.FALSE.equals(event.getLivemode())
            ||event.getAccount()!=null||event.getContext()!=null)throw mismatch();
        if(!"customer.subscription.created".equals(event.getType()))return Optional.empty();
        var data=event.getDataObjectDeserializer().getObject().orElseThrow(StripeBillingOwnership::mismatch);
        if(!(data instanceof com.stripe.model.Subscription created)||!reference.equals(created.getId())
            ||!"subscription".equals(created.getObject())||!Boolean.FALSE.equals(created.getLivemode())||created.getMetadata()==null)throw mismatch();
        UUID id,operation;
        try{id=UUID.fromString(created.getMetadata().get("subscription_id"));operation=UUID.fromString(created.getMetadata().get("operation_id"));}
        catch(RuntimeException malformed){throw mismatch();}
        var owned=ownership.owned(account,id);
        ownership.original(owned,operation);
        var reply=provider.recoverCreate(account,id,operation,envelope.eventId());
        if(reply.outcome()!=Outcome.ACCEPTED||!reference.equals(reply.reference()))throw mismatch();
        commands.recordReply(operation,reply);
        return prepared(account,id,reference,null);
    }

    private Optional<UUID> invoice(PaymentAccount account,String reference)throws StripeException{
        if(!reference.matches("in_[A-Za-z0-9]{1,252}"))throw mismatch();
        verification.verify(account);
        var remote=clients.create(credentials.resolve(account)).v1().invoices().retrieve(reference);
        if(remote==null||!reference.equals(remote.getId())||!"invoice".equals(remote.getObject())||!Boolean.FALSE.equals(remote.getLivemode())
            ||remote.getCustomer()==null||!remote.getCustomer().matches("cus_[A-Za-z0-9]{1,251}")
            ||remote.getParent()==null||!"subscription_details".equals(remote.getParent().getType())
            ||remote.getParent().getSubscriptionDetails()==null)throw mismatch();
        String parent=remote.getParent().getSubscriptionDetails().getSubscription();
        if(parent==null||!parent.matches("sub_[A-Za-z0-9]{1,251}"))throw mismatch();
        var local=objects.resolve(PaymentProviderType.STRIPE,account.id(),SubscriptionObjectPort.Kind.SUBSCRIPTION,parent);
        if(local.isEmpty())return Optional.empty();
        var mapped=objects.resolve(PaymentProviderType.STRIPE,account.id(),SubscriptionObjectPort.Kind.INVOICE,reference);
        if(mapped.isPresent()&&!mapped.get().equals(local.get()))throw mismatch();
        return prepared(account,local.get(),parent,remote.getCustomer());
    }

    private Optional<UUID> prepared(PaymentAccount account,UUID id,String reference,String remoteCustomer){
        var subscription=ownership.owned(account,id);
        if(!reference.equals(subscription.providerReference()))throw mismatch();
        var binding=bindings.findCustomer(account.id(),subscription.siteId(),subscription.customerId()).orElseThrow(StripeBillingOwnership::mismatch);
        requireBinding(account,subscription,binding);
        if(remoteCustomer!=null&&!binding.providerReference().equals(remoteCustomer))throw mismatch();
        var original=operations.unresolved(subscription.siteId(),id).stream().filter(o->o.kind()==SubscriptionOperation.Kind.CREATE).toList();
        if(original.size()>1)throw mismatch();
        if(!original.isEmpty()&&original.getFirst().providerReference()==null){
            var operation=original.getFirst();
            ownership.acknowledgedCreate(new Lookup(account,id,reference,operation.providerOperationKey()));
            commands.recordReply(operation.id(),new Reply(Outcome.ACCEPTED,reference,null));
        }
        return Optional.of(id);
    }
}
