package dev.genericpayments.platform.donations.application.service;

import dev.genericpayments.platform.catalog.domain.Money;

import dev.genericpayments.platform.customers.application.port.in.RevalidateCustomerSessionUseCase;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand;
import dev.genericpayments.platform.donations.application.port.in.*;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import dev.genericpayments.platform.shared.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Site serializes freshness, campaign snapshot capture, and durable checkout ownership. */
@Service
public class DonationCheckoutTransaction {
    private final GetPublicDonationCampaignUseCase publicSites;
    private final LockSitePort sites;
    private final RevalidateCustomerSessionUseCase sessions;
    private final DonationCampaignStore campaigns;
    private final DonationStore donations;
    private final DonationPaymentCreationPort payments;
    private final DonorMessageEncryptionPort encryption;
    private final DonationMessageFingerprintPort messageFingerprints;
    private final IdempotencyService idempotency;
    private final IdempotencyReceiptQuery receipts;
    private final Clock clock;
    public DonationCheckoutTransaction(GetPublicDonationCampaignUseCase publicSites,LockSitePort sites,
        RevalidateCustomerSessionUseCase sessions,DonationCampaignStore campaigns,DonationStore donations,
        DonationPaymentCreationPort payments,DonorMessageEncryptionPort encryption,DonationMessageFingerprintPort messageFingerprints,
        IdempotencyService idempotency,IdempotencyReceiptQuery receipts,Clock clock) {
        this.publicSites=publicSites;this.sites=sites;this.sessions=sessions;this.campaigns=campaigns;this.donations=donations;
        this.payments=payments;this.encryption=encryption;this.messageFingerprints=messageFingerprints;this.idempotency=idempotency;
        this.receipts=receipts;this.clock=clock;
    }
    public record Result(DonationPaymentCreationPort.Checkout checkout,UUID expiredPaymentId,boolean replayed) {}
    @Transactional
    public Result create(CreateDonationCheckoutCommand c) {
        var resolved=publicSites.requirePublicSite(c.siteKey(),c.origin());
        var site=sites.findByIdForUpdate(resolved.id()).orElseThrow(DonationCampaignService::unavailable);
        if(site.status()!=SiteStatus.ACTIVE) throw DonationCampaignService.unavailable();
        if(site.allowedOrigins().stream().map(java.net.URI::toString).noneMatch(c.origin().toString()::equals)) throw DonationCampaignService.originDenied();
        UUID customer=c.session()==null?null:sessions.revalidate(c.session(),site.id(),c.origin().toString(),CustomerSessionScope.CHECKOUT_CREATE).customerId().value();
        if(c.idempotencyKey()==null || c.idempotencyKey().isBlank() || c.idempotencyKey().length()>255
            || c.idempotencyKey().chars().anyMatch(Character::isISOControl)) throw new DomainValidationException("invalid idempotency key");
        var message=c.message()==null?null:DonorMessage.of(c.message());
        payments.preflight(site);
        var scope=Scope.publicOperation(site.id().value(),Operation.DONATION_CHECKOUT_CREATE,null);
        var receipt=receipts.find(scope,c.idempotencyKey());
        DonationPaymentCreationPort.Checkout original=null;
        DonationCampaignSnapshot snapshot;
        if(receipt.isPresent() && receipt.get().status()==Status.COMPLETED) {
            if(receipt.get().resource().type()!=ResourceType.CHECKOUT) throw conflict();
            original=payments.load(site.id(),receipt.get().resource().id(),null);
            snapshot=original.snapshot();
        } else {
            var campaign=campaigns.findByKeyForUpdate(site.id(),new DonationCampaignKey(c.campaignKey()))
                .filter(value->value.status()==CampaignStatus.ACTIVE).orElseThrow(DonationCampaignService::unavailable);
            try { campaign.validateAmount(c.amountMinor()); }
            catch(DomainValidationException invalid) { throw new PaymentApplicationException("AMOUNT_NOT_ALLOWED",422,"Amount not allowed"); }
            if(customer==null && !campaign.anonymousAllowed())
                throw new PaymentApplicationException("ANONYMOUS_DONATION_NOT_ALLOWED",422,"Anonymous donation not allowed");
            snapshot=campaign.snapshot();
        }
        var fields=new TreeMap<String,Object>();
        fields.put("site_id",site.id().value());
        fields.put("campaign_id",snapshot.campaignId().value()); fields.put("campaign_key",c.campaignKey());
        fields.put("campaign_version",snapshot.version());fields.put("currency",snapshot.currency());fields.put("amount",c.amountMinor());
        fields.put("identity",customer==null?"anonymous":"customer:"+customer);fields.put("origin",c.origin().toString());
        fields.put("message_fingerprint",messageFingerprints.fingerprint(message));
        var fingerprint=idempotency.fingerprint(fields);
        if(receipt.isPresent() && !messageFingerprints.matches(receipt.get().fingerprint(),fingerprint)) {
            idempotency.begin(scope,c.idempotencyKey(),fingerprint); // Preserve the common finite conflict metric.
            throw conflict();
        }
        var outcome=idempotency.begin(scope,c.idempotencyKey(),fingerprint);
        if(outcome instanceof IdempotencyService.Completed completed)
            return new Result(payments.load(site.id(),completed.resource().id(),completed.secret().orElseThrow(DonationCheckoutTransaction::conflict)),null,true);
        if(outcome instanceof IdempotencyService.ExpiredSecret)
            return new Result(null,Objects.requireNonNull(original).checkout().view().paymentId(),true);
        if(outcome instanceof IdempotencyService.Conflict) throw conflict();
        if(!(outcome instanceof IdempotencyService.Owner owner))
            throw new PaymentApplicationException("OPERATION_IN_PROGRESS",409,"Operation in progress");
        var donationId=new DonationId(UUID.randomUUID());
        var donation=Donation.create(donationId,site.id(),snapshot.campaignId(),message==null?null:DonationStore.messageReference(donationId),clock.instant());
        if(message==null) donations.save(donation); else donations.save(donation,encryption.encrypt(message));
        var checkout=payments.create(site,donationId,snapshot,new Money(c.amountMinor(),snapshot.currency()),customer,owner.id(),c.origin(),c.requestId(),c.idempotencyKey());
        idempotency.complete(owner,new Resource(ResourceType.CHECKOUT,checkout.checkout().view().checkoutId()),201,
            new IdempotencyService.SecretFragment(checkout.checkout().checkoutToken(),checkout.checkout().view().expiresAt()));
        return new Result(checkout,null,false);
    }
    private static PaymentApplicationException conflict() {return new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");}
}
