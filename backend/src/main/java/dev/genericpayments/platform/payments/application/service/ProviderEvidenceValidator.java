package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.Objects;

/** Shared application guard for verified reconciliation, inbox resolution and recovery. No I/O. */
public final class ProviderEvidenceValidator {
    private ProviderEvidenceValidator() {}
    public static void requirePayment(PaymentAccount account,Payment payment,ProviderState state) {
        if(account.provider()!=PaymentProviderType.STRIPE)return;
        var e=state==null?null:state.evidence();
        if(e==null||account.mode()!=PaymentAccountMode.SANDBOX||e.provider()!=account.provider()
                ||!account.id().equals(payment.paymentAccountId())||!account.id().equals(e.accountId())
                ||account.mode()!=e.mode()||!account.externalAccountId().equals(e.externalAccountId())
                ||!payment.money().equals(e.money())||!Objects.equals(payment.providerPaymentReference(),state.reference())
                ||!Objects.equals(state.reference(),e.paymentReference())||!reference(e.paymentReference(),"pi_")
                ||e.chargeReference()!=null&&!reference(e.chargeReference(),"ch_")
                ||e.failedChargeReference()!=null&&!reference(e.failedChargeReference(),"ch_")
                ||state.status()==ProviderState.Status.SUCCEEDED&&(!e.captured()||!reference(e.chargeReference(),"ch_")))throw mismatch();
    }
    private static boolean reference(String value,String prefix) { return value!=null&&value.matches(prefix+"[A-Za-z0-9_]{1,200}"); }
    private static PaymentApplicationException mismatch() {
        return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match the payment");
    }
}
