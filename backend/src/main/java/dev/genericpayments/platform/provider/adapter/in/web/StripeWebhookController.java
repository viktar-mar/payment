package dev.genericpayments.platform.provider.adapter.in.web;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt;
import dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeWebhookVerifier;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeVerifiedReceipt;
import dev.genericpayments.platform.webhooks.application.port.in.ReceiveSubscriptionEventUseCase;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class StripeWebhookController {
    private final LoadPaymentAccountPort accounts;
    private final StripeWebhookVerifier verifier;
    private final ProviderInboxPort inbox;
    private final ReceiveSubscriptionEventUseCase subscriptions;
    private final Clock clock;
    public StripeWebhookController(LoadPaymentAccountPort accounts,StripeWebhookVerifier verifier,ProviderInboxPort inbox,ReceiveSubscriptionEventUseCase subscriptions,
            @Qualifier("providerReferenceClock") Clock clock) {
        this.accounts=accounts;this.verifier=verifier;this.inbox=inbox;this.subscriptions=subscriptions;this.clock=clock;
    }

    @PostMapping("/api/v1/provider-webhooks/stripe/{accountKey}")
    public ResponseEntity<?> receive(@PathVariable String accountKey,HttpServletRequest request) {
        if(!accountKey.matches("[a-z0-9][a-z0-9-]{2,62}"))return rejected(request,404);
        var account=accounts.findByKey(accountKey).filter(a -> a.status()==PaymentAccountStatus.ACTIVE
            && a.provider()==PaymentProviderType.STRIPE && a.mode()==PaymentAccountMode.SANDBOX);
        if(account.isEmpty())return rejected(request,404);
        Optional<StripeVerifiedReceipt> receipt;
        try {receipt=verifier.verifyAny((byte[])request.getAttribute(StripeWebhookAdmissionFilter.RAW_BODY),
                request.getHeader("Stripe-Signature"),account.get(),clock.instant());}
        catch(IllegalArgumentException invalid) {return rejected(request,400);}
        if(receipt.isEmpty())return ResponseEntity.noContent().build();
        try {switch(receipt.get()){
            case StripeVerifiedReceipt.Reference reference -> inbox.insertReference(reference.receipt());
            case StripeVerifiedReceipt.Billing billing -> subscriptions.receive(billing.envelope());
        }}
        catch(RuntimeException unavailable) {return rejected(request,503);}
        return ResponseEntity.noContent().build();
    }
    private ResponseEntity<?> rejected(HttpServletRequest request,int status) {
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblem.of(status,"invalid_provider_webhook","Provider webhook rejected",request));
    }
}
