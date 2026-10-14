package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.in.LinkPartnerCustomerIdentityUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.*;
import java.util.*;

public final class PartnerCustomerIdentityLinkService implements LinkPartnerCustomerIdentityUseCase {
    private static final Duration RETENTION=Duration.ofDays(30);
    private final LockSitePort sites;
    private final CustomerStore customers;
    private final IdentityProviderConfigurationStore providers;
    private final CustomerIdempotencyStore idempotency;
    private final KeyedDigestPort keyedDigests;
    private final DigestPort digests;
    private final IdentityLinkingSupport linking;
    private final CustomerIdentityLinkAuditPort audits;
    private final Clock clock;
    private final CustomerTransactionPort transactions;
    private record Prepared(Command command,String issuer,List<String> subjects,String keyDigest,List<String> fingerprints) {
        String primaryFingerprint(){return fingerprints.getFirst();}
    }
    private record TxResult(CustomerIdentityId identityId,IdentityLinkingSupport.Outcome outcome,int status) {}

    public PartnerCustomerIdentityLinkService(LockSitePort sites,CustomerStore customers,IdentityProviderConfigurationStore providers,
            CustomerIdempotencyStore idempotency,KeyedDigestPort keyedDigests,DigestPort digests,CustomerIdentityStore identities,
            CustomerIdentityLinkAuditPort audits,Clock clock,CustomerTransactionPort transactions) {
        this.sites=sites;this.customers=customers;this.providers=providers;this.idempotency=idempotency;this.keyedDigests=keyedDigests;this.digests=digests;
        this.linking=new IdentityLinkingSupport(identities);this.audits=audits;this.clock=clock;this.transactions=transactions;
    }

    @Override public Result link(Command command) {
        Prepared prepared=prepare(command);
        TxResult result=transactions.required(() -> locked(prepared));
        if(result==null)throw new IllegalStateException("Partner identity link transaction returned no outcome");
        if(result.outcome()==IdentityLinkingSupport.Outcome.CONFLICT)throw CustomerIdentityLinkService.conflict();
        return new Result(result.identityId(),result.outcome()==IdentityLinkingSupport.Outcome.LINKED?Outcome.LINKED:Outcome.ALREADY_LINKED,result.status());
    }

    private Prepared prepare(Command command) {
        Objects.requireNonNull(command,"command");
        String issuer=command.issuerKey().trim().toLowerCase(Locale.ROOT);
        if(!issuer.matches("[a-z0-9][a-z0-9-]{2,62}"))throw invalid();
        validateText(command.subject(),2048,"subject"); validateText(command.idempotencyKey(),255,"idempotency key");
        List<String> subjects=CustomerIdentityLinkService.references(keyedDigests.candidates(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,command.subject()));
        if(subjects.isEmpty())throw invalid();
        String keyDigest=digests.sha256Hex(command.idempotencyKey());
        List<String> fingerprints=subjects.stream().map(subject -> digests.sha256Hex(canonical(command,issuer,subject))).distinct().toList();
        return new Prepared(command,issuer,subjects,keyDigest,fingerprints);
    }

    private TxResult locked(Prepared prepared) {
        Command command=prepared.command();
        String scope="LINK_IDENTITY"; Instant now=clock.instant();
        var candidate=new CustomerIdempotencyStore.Record(UUID.randomUUID(),command.siteId(),scope,prepared.keyDigest(),prepared.primaryFingerprint(),
            CustomerIdempotencyStore.Status.IN_PROGRESS,null,null,now,now,now.plus(RETENTION),0);
        var claim=idempotency.claim(candidate); var record=claim.record();
        if(!prepared.fingerprints().contains(record.fingerprint()))throw idempotencyConflict();
        if(!claim.owner())return replay(record);
        // Completed replay remains independent of mutable state; new work shares the Site lock order.
        sites.findByIdForUpdate(command.siteId()).orElseThrow(PartnerCustomerIdentityLinkService::unavailable);
        var customer=customers.findByIdForUpdate(command.siteId(),command.customerId()).filter(value -> value.status()==CustomerStatus.ACTIVE)
            .orElseThrow(PartnerCustomerIdentityLinkService::unavailable);
        var provider=providers.findForUpdate(command.siteId(),prepared.issuer()).filter(value -> value.status()==IdentityProviderStatus.ACTIVE
            && value.mode()==command.mode()).orElseThrow(PartnerCustomerIdentityLinkService::invalid);
        var linked=linking.link(command.siteId(),customer.id(),provider.mode(),prepared.issuer(),prepared.subjects(),now,now);
        int responseStatus=switch(linked.outcome()){case LINKED->201;case ALREADY_LINKED->200;case CONFLICT->409;};
        UUID resource=linked.identityId()==null?customer.id().value():linked.identityId().value();
        idempotency.complete(record.id(),record.siteId(),record.scope(),record.keyDigest(),record.version(),resource,responseStatus,now);
        audits.append(new CustomerIdentityLinkAuditPort.Event(command.siteId(),customer.id(),linked.identityId(),provider.mode(),prepared.issuer(),
            "SITE_API_KEY",command.actorId().value().toString(),linked.outcome().name(),command.requestId(),command.sourceIp()));
        return new TxResult(linked.identityId(),linked.outcome(),responseStatus);
    }

    private static TxResult replay(CustomerIdempotencyStore.Record record) {
        if(record.status()!=CustomerIdempotencyStore.Status.COMPLETED || record.resourceId()==null || record.responseStatus()==null)
            throw new PaymentApplicationException("OPERATION_IN_PROGRESS",409,"Operation in progress");
        var outcome=switch(record.responseStatus()){case 201->IdentityLinkingSupport.Outcome.LINKED;case 200->IdentityLinkingSupport.Outcome.ALREADY_LINKED;
            case 409->IdentityLinkingSupport.Outcome.CONFLICT;default->throw new IllegalStateException("Invalid identity link replay status");};
        return new TxResult(outcome==IdentityLinkingSupport.Outcome.CONFLICT?null:new CustomerIdentityId(record.resourceId()),outcome,record.responseStatus());
    }
    private static String canonical(Command command,String issuer,String subjectDigest) {
        return field(command.siteId().value().toString())+field(command.customerId().value().toString())+field(command.mode().name())+field(issuer)+field(subjectDigest);
    }
    private static String field(String value){return value.length()+":"+value;}
    private static void validateText(String value,int max,String name) {
        if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl)
            ||value.codePoints().anyMatch(code -> code>=Character.MIN_SURROGATE&&code<=Character.MAX_SURROGATE))throw new DomainValidationException("Invalid "+name);
    }
    private static DomainValidationException invalid(){return new DomainValidationException("Invalid identity link request");}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("CUSTOMER_NOT_AVAILABLE",404,"Customer not available");}
    private static PaymentApplicationException idempotencyConflict(){return new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");}
}
