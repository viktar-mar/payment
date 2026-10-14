package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.*;
import java.util.*;

public final class CustomerAnonymizationService implements AnonymizeCustomerUseCase {
    private static final String OPERATION="ANONYMIZE_CUSTOMER";
    private final CustomerStore customers;
    private final CustomerIdentityStore identities;
    private final LockSitePort sites;
    private final CustomerIdempotencyStore idempotency;
    private final DigestPort digests;
    private final CustomerAccessRevocationPort access;
    private final CustomerDataRedactionPort redaction;
    private final CustomerAnonymizationAuditPort audits;
    private final CustomerTransactionPort transactions;
    private final Clock clock;
    private final CustomerTelemetryPort telemetry;
    private final CustomerBillingAnonymizationGuardPort billingGuard;

    public CustomerAnonymizationService(CustomerStore customers,CustomerIdentityStore identities,LockSitePort sites,
            CustomerIdempotencyStore idempotency,DigestPort digests,CustomerAccessRevocationPort access,
            CustomerDataRedactionPort redaction,CustomerAnonymizationAuditPort audits,CustomerTransactionPort transactions,Clock clock,CustomerBillingAnonymizationGuardPort billingGuard) {
        this(customers,identities,sites,idempotency,digests,access,redaction,audits,transactions,clock,billingGuard,CustomerTelemetryPort.NOOP);
    }
    public CustomerAnonymizationService(CustomerStore customers,CustomerIdentityStore identities,LockSitePort sites,
            CustomerIdempotencyStore idempotency,DigestPort digests,CustomerAccessRevocationPort access,
            CustomerDataRedactionPort redaction,CustomerAnonymizationAuditPort audits,CustomerTransactionPort transactions,Clock clock,CustomerBillingAnonymizationGuardPort billingGuard,CustomerTelemetryPort telemetry) {
        this.customers=customers;this.identities=identities;this.sites=sites;this.idempotency=idempotency;this.digests=digests;
        this.access=access;this.redaction=redaction;this.audits=audits;this.transactions=transactions;this.clock=clock;
        this.telemetry=Objects.requireNonNull(telemetry);
        this.billingGuard=Objects.requireNonNull(billingGuard);
    }
    @Override public Result anonymize(Command command) {
        boolean succeeded=false;
        try {var result=performAnonymization(command);succeeded=true;return result;}
        finally {telemetry.anonymization(succeeded?CustomerTelemetryPort.Outcome.SUCCESS:CustomerTelemetryPort.Outcome.REJECTED);}
    }
    private Result performAnonymization(Command command) {
        Objects.requireNonNull(command,"command"); Objects.requireNonNull(command.siteId(),"site"); Objects.requireNonNull(command.customerId(),"customer");
        validateKey(command.idempotencyKey());
        if(!command.customerId().value().equals(command.confirmation()))throw new DomainValidationException("Invalid Customer confirmation");
        String key=digests.sha256Hex(command.idempotencyKey());
        String fingerprint=digests.sha256Hex(field(OPERATION)+field(command.siteId().value().toString())+field(command.customerId().value().toString()));
        return transactions.required(() -> locked(command,key,fingerprint));
    }
    private Result locked(Command command,String key,String fingerprint) {
        Instant now=clock.instant();
        var claim=idempotency.claim(new CustomerIdempotencyStore.Record(UUID.randomUUID(),command.siteId(),OPERATION,key,fingerprint,
            CustomerIdempotencyStore.Status.IN_PROGRESS,null,null,now,now,now.plus(Duration.ofDays(30)),0));
        var record=claim.record();
        if(!record.fingerprint().equals(fingerprint))throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");
        if(!claim.owner()) {
            if(record.status()!=CustomerIdempotencyStore.Status.COMPLETED||record.resourceId()==null||!Integer.valueOf(200).equals(record.responseStatus()))
                throw new PaymentApplicationException("OPERATION_IN_PROGRESS",409,"Operation in progress");
            return new Result(new CustomerId(record.resourceId()),CustomerStatus.ANONYMIZED);
        }
        sites.findByIdForUpdate(command.siteId()).orElseThrow(CustomerQueryService::unavailable);
        Customer customer=customers.findByIdForUpdate(command.siteId(),command.customerId()).orElseThrow(CustomerQueryService::unavailable);
        billingGuard.requireAnonymizable(customer.siteId(),customer.id());
        now=clock.instant();
        if(customer.status()==CustomerStatus.ACTIVE) {
            // Capabilities are matched while the profile and complete identity aliases still exist.
            access.revokeCustomerAccess(customer.siteId(),customer.id(),now);
            customers.save(customer.anonymize(now));
            identities.deleteByCustomer(customer.siteId(),customer.id());
            redaction.redactCustomerData(customer.siteId(),customer.id(),now);
            audits.append(new CustomerAnonymizationAuditPort.Event(customer.siteId(),customer.id(),command.actorType(),command.actorId(),
                command.requestId(),command.sourceIp(),now));
        }
        idempotency.complete(record.id(),record.siteId(),record.scope(),record.keyDigest(),record.version(),customer.id().value(),200,now);
        return new Result(customer.id(),CustomerStatus.ANONYMIZED);
    }
    public static void validateKey(String key) {
        if(key==null||key.isBlank()||key.length()>255||key.chars().anyMatch(value -> Character.isISOControl(value)||Character.isSurrogate((char)value)))
            throw new DomainValidationException("Invalid idempotency key");
    }
    private static String field(String value){return value.length()+":"+value;}
}
