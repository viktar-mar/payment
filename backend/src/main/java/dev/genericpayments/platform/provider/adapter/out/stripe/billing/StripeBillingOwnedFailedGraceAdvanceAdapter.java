package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceAdvanceService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Explicit original G−1 writer. It has no runtime registration or financial continuation. */
public final class StripeBillingOwnedFailedGraceAdvanceAdapter {
    private final OwnedFailedGraceAdvanceService service;
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;
    private final Clock utc;private final LongSupplier nanos;private final Consumer<Duration> wait;

    public StripeBillingOwnedFailedGraceAdvanceAdapter(OwnedFailedGraceAdvanceService service,
            StripeCredentialResolver credentials,StripeClientFactory clients,Clock utc){
        this(service,credentials,clients,utc,System::nanoTime,duration->{
            try{Thread.sleep(duration);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        });
    }
    StripeBillingOwnedFailedGraceAdvanceAdapter(OwnedFailedGraceAdvanceService service,
            StripeCredentialResolver credentials,StripeClientFactory clients,Clock utc,LongSupplier nanos,Consumer<Duration> wait){
        this.service=Objects.requireNonNull(service);this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);
        this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);this.wait=Objects.requireNonNull(wait);
    }
    public GraceAdvanceReady advance(Admission admission,GraceLastSecondPreparation preparation){
        final InvocationBudget budget;
        try{budget=new InvocationBudget(utc,nanos);}catch(RuntimeException failure){throw unavailable();}
        PreparationContext original=null;ProviderOperation owned=null;AdvanceAck acknowledged=null;
        try{
            var context=service.inspect(admission,preparation,budget);original=context;
            if(!StripeBillingTestClockAdapter.API_VERSION.equals(context.admission().firstRenewalAdmission().receipt().intent().clock().owner().apiVersion()))throw unavailable();
            budget.remainingNanos(null);var resolved=credentials.resolve(context.account());budget.remainingNanos(null);
            var client=clients.create(resolved);budget.remainingNanos(null);
            // Assignment occurs only after the fresh firstclaim transaction has actually returned.
            var claim=service.claim(context,UUID.randomUUID().toString(),budget);owned=claim;budget.remainingNanos(claim);
            var exchange=new OwnedGraceClockExchange(client,context.admission().ready().observation(),context.preparation().target(),
                context.preparation().operationKey(),budget,claim,utc,nanos,wait);
            var ack=exchange.acknowledge(()->service.inspectClaim(context,claim,budget),
                (observation,at)->service.acknowledge(context,claim,observation,at,budget));
            acknowledged=ack;
            return exchange.recordReady(()->service.inspectAck(context,ack,budget),
                (observation,at)->service.recordReady(context,ack,observation,at,budget));
        }catch(RuntimeException failure){cleanup(original,owned,acknowledged,budget);throw unavailable();}
        catch(Error failure){cleanup(original,owned,acknowledged,budget);throw failure;}
    }
    private void cleanup(PreparationContext original,ProviderOperation owned,AdvanceAck ack,InvocationBudget budget){
        if(original!=null&&owned!=null)try{service.uncertain(original,owned,ack,budget);}catch(RuntimeException|Error ignored){/* Preserve original failure and consumed evidence. */}
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_GRACE_CLOCK_ADVANCE_UNAVAILABLE",409,"Owned grace clock advance unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedGraceAdvanceAdapter[redacted]";}
}
