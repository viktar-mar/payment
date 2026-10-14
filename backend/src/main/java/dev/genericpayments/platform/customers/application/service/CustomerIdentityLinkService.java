package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.in.LinkCustomerIdentityUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.Clock;
import java.util.*;

public final class CustomerIdentityLinkService implements LinkCustomerIdentityUseCase {
    private final LockSitePort sites;
    private final CustomerStore customers;
    private final IdentityLinkProofStore proofs;
    private final KeyedDigestPort digests;
    private final IdentityLinkingSupport linking;
    private final CustomerIdentityLinkAuditPort audits;
    private final Clock clock;
    private final CustomerTransactionPort transactions;
    private final CustomerTelemetryPort telemetry;

    public CustomerIdentityLinkService(LockSitePort sites,CustomerStore customers,IdentityLinkProofStore proofs,KeyedDigestPort digests,
            CustomerIdentityStore identities,CustomerIdentityLinkAuditPort audits,Clock clock,CustomerTransactionPort transactions) {
        this(sites,customers,proofs,digests,identities,audits,clock,transactions,CustomerTelemetryPort.NOOP);
    }
    public CustomerIdentityLinkService(LockSitePort sites,CustomerStore customers,IdentityLinkProofStore proofs,KeyedDigestPort digests,
            CustomerIdentityStore identities,CustomerIdentityLinkAuditPort audits,Clock clock,CustomerTransactionPort transactions,CustomerTelemetryPort telemetry) {
        this.sites=sites;this.customers=customers;this.proofs=proofs;this.digests=digests;this.linking=new IdentityLinkingSupport(identities);
        this.audits=audits;this.clock=clock;this.transactions=transactions;
        this.telemetry=Objects.requireNonNull(telemetry);
    }

    @Override public Result link(Command command) {
        var outcome=CustomerTelemetryPort.Outcome.REJECTED;
        try {
            var result=performLink(command);
            outcome=result.outcome()==Outcome.LINKED?CustomerTelemetryPort.Outcome.SUCCESS:CustomerTelemetryPort.Outcome.ALREADY_LINKED;
            return result;
        } catch(PaymentApplicationException failure) {
            if("IDENTITY_LINK_CONFLICT".equals(failure.code()))outcome=CustomerTelemetryPort.Outcome.CONFLICT;
            throw failure;
        } finally {telemetry.link(outcome);}
    }
    private Result performLink(Command command) {
        Objects.requireNonNull(command,"command");
        command.session().requireScope(CustomerSessionScope.IDENTITY_LINK);
        if(!command.proofToken().matches("[A-Za-z0-9_-]{43}"))throw failed();
        List<String> candidates;
        try { candidates=references(digests.candidates(KeyedDigestPort.Purpose.LINK_PROOF,command.proofToken())); }
        catch(RuntimeException invalid){throw failed();}
        var outcome=transactions.required(() -> locked(command,candidates));
        if(outcome==null)throw new IllegalStateException("Identity link transaction returned no outcome");
        if(outcome.outcome()==IdentityLinkingSupport.Outcome.CONFLICT)throw conflict();
        return new Result(outcome.identityId(),outcome.outcome()==IdentityLinkingSupport.Outcome.LINKED?Outcome.LINKED:Outcome.ALREADY_LINKED);
    }

    private IdentityLinkingSupport.Result locked(Command command,List<String> candidates) {
        var session=command.session();
        // Authentication and anonymization also serialize on Site before Customer or subject locks.
        sites.findByIdForUpdate(session.siteId()).orElseThrow(CustomerIdentityLinkService::failed);
        var customer=customers.findByIdForUpdate(session.siteId(),session.customerId()).filter(value -> value.status()==CustomerStatus.ACTIVE)
            .orElseThrow(CustomerIdentityLinkService::failed);
        var proof=proofs.findForUpdate(session.siteId(),candidates).orElseThrow(CustomerIdentityLinkService::failed);
        var now=clock.instant();
        if(proof.consumedAt()!=null || !now.isBefore(proof.expiresAt()) || !proof.origin().equals(session.origin()))throw failed();
        var result=linking.link(session.siteId(),customer.id(),proof.mode(),proof.issuerKey(),proof.subjectDigests(),proof.verifiedAt(),now);
        proofs.consume(proof,now);
        audits.append(new CustomerIdentityLinkAuditPort.Event(session.siteId(),customer.id(),result.identityId(),proof.mode(),proof.issuerKey(),
            "CUSTOMER_SESSION",session.sessionId().value().toString(),result.outcome().name(),command.requestId(),command.sourceIp()));
        return result;
    }

    static List<String> references(List<KeyedDigestPort.DigestValue> values) {
        return values.stream().map(value -> value.keyVersion()+":"+value.value()).toList();
    }
    static PaymentApplicationException failed(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
    static PaymentApplicationException conflict(){return new PaymentApplicationException("IDENTITY_LINK_CONFLICT",409,"Identity link conflict");}
}
