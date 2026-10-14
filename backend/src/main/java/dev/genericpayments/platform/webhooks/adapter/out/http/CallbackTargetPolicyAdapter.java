package dev.genericpayments.platform.webhooks.adapter.out.http;

import dev.genericpayments.platform.webhooks.application.port.out.CallbackTargetPolicyPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class CallbackTargetPolicyAdapter implements CallbackTargetPolicyPort {
    private final CallbackUrlPolicy policy;
    public CallbackTargetPolicyAdapter(CallbackUrlPolicy policy){this.policy=policy;}
    @Override public String normalize(String endpoint){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new PaymentApplicationException("CALLBACK_CONFIG_INVALID",409,"Callback validation requires no transaction");
        try{return policy.validate(endpoint).toASCIIString();}
        catch(CallbackUrlPolicy.ResolutionFailure failure){throw CallbackUrlPolicy.invalid();}
    }
}
