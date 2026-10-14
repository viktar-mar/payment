package dev.genericpayments.platform.donations.application.service;

import dev.genericpayments.platform.donations.application.port.in.RedactDonationMessageUseCase;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.application.model.DonationViews.Redaction;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class DonationMessageRedactionService implements RedactDonationMessageUseCase {
    private final DonationPaymentQueryPort payments;
    private final DonationStore donations;
    private final LockSitePort sites;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final DonationTelemetryPort telemetry;
    public DonationMessageRedactionService(DonationPaymentQueryPort payments,DonationStore donations,LockSitePort sites,IdempotencyService idempotency,Clock clock,DonationTelemetryPort telemetry){this.payments=payments;this.donations=donations;this.sites=sites;this.idempotency=idempotency;this.clock=clock;this.telemetry=telemetry;}
    public Redaction redact(UUID id,UUID confirmation,UUID actor,String key){
        try {return execute(id,confirmation,actor,key);}
        catch(PaymentApplicationException failure){telemetry.redaction(failure.status()==409?DonationTelemetryPort.RedactionOutcome.CONFLICT:failure.status()<500?DonationTelemetryPort.RedactionOutcome.REJECTED:DonationTelemetryPort.RedactionOutcome.FAILED);throw failure;}
        catch(DomainValidationException failure){telemetry.redaction(DonationTelemetryPort.RedactionOutcome.REJECTED);throw failure;}
    }
    private Redaction execute(UUID id,UUID confirmation,UUID actor,String key){
        if(id==null||!id.equals(confirmation))throw new DomainValidationException("Invalid Donation confirmation");
        if(actor==null||key==null||key.isBlank()||key.length()>255||key.chars().anyMatch(c->Character.isISOControl(c)||Character.isSurrogate((char)c)))throw new DomainValidationException("Invalid idempotency key");
        var payment=payments.find(null,id).orElseThrow(DonationQueryService::missing);var site=new SiteId(payment.siteId());
        // Serialize with checkout and Customer anonymization before locking Donation or claiming a receipt.
        sites.findByIdForUpdate(site).orElseThrow(DonationQueryService::missing);
        var current=donations.findByIdForUpdate(site,new DonationId(id)).orElseThrow(DonationQueryService::missing);
        var outcome=idempotency.begin(new Scope(Actor.ADMIN,site.value(),actor,Operation.DONATION_MESSAGE_REDACT,null),key,
            idempotency.fingerprint(Map.of("donation_id",id.toString(),"confirmation",confirmation.toString())));
        if(outcome instanceof IdempotencyService.Completed completed){
            if(completed.resource().type()!=ResourceType.PAYMENT||!completed.resource().id().equals(payment.paymentId()))throw conflict();
            committed(DonationTelemetryPort.RedactionOutcome.REPLAYED);
            return new Redaction(id,current.messageState(),false,true);
        }
        if(outcome instanceof IdempotencyService.Conflict)throw conflict();
        if(!(outcome instanceof IdempotencyService.Owner owner))throw new PaymentApplicationException("OPERATION_IN_PROGRESS",409,"Operation in progress");
        boolean changed=current.messageState()==DonorMessageState.PRESENT;
        if(changed)current=donations.save(current.redactMessage(clock.instant()));
        // PAYMENT is the existing receipt resource and uniquely identifies its immutable Donation.
        idempotency.complete(owner,new Resource(ResourceType.PAYMENT,payment.paymentId()),200,null);
        committed(changed?DonationTelemetryPort.RedactionOutcome.REDACTED:DonationTelemetryPort.RedactionOutcome.UNCHANGED);
        return new Redaction(id,current.messageState(),changed,false);
    }
    private void committed(DonationTelemetryPort.RedactionOutcome outcome){
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCompletion(int status){telemetry.redaction(status==STATUS_COMMITTED?outcome:DonationTelemetryPort.RedactionOutcome.FAILED);}
        });
    }
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");}
}
