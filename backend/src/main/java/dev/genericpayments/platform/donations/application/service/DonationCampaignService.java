package dev.genericpayments.platform.donations.application.service;

import dev.genericpayments.platform.donations.application.port.in.*;
import dev.genericpayments.platform.donations.application.port.out.DonationCampaignStore;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class DonationCampaignService implements ManageDonationCampaignUseCase,GetPublicDonationCampaignUseCase {
    private final DonationCampaignStore campaigns;
    private final LoadSitePort sites;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final DigestPort digest;
    public DonationCampaignService(DonationCampaignStore campaigns,LoadSitePort sites,IdempotencyService idempotency,Clock clock,DigestPort digest) {
        this.campaigns=campaigns; this.sites=sites; this.idempotency=idempotency; this.clock=clock; this.digest=digest;
    }
    @Override @Transactional(readOnly=true)
    public List<DonationCampaign> list(SiteId site,int offset,int limit) {
        if(offset<0 || offset>100000 || limit<1 || limit>50) throw new DomainValidationException("invalid pagination");
        requireSite(site); return campaigns.list(site,offset,limit);
    }
    @Override @Transactional(readOnly=true)
    public DonationCampaign get(SiteId site,DonationCampaignId id) {
        requireSite(site); return campaigns.findById(site,id).orElseThrow(DonationCampaignService::unavailable);
    }
    @Override public Mutation create(SiteId site,UUID actor,String key,Configuration config) {
        requireSite(site);
        var outcome=begin(site,actor,key,Operation.CAMPAIGN_CREATE,null,fields(config));
        if(outcome instanceof IdempotencyService.Completed done) return replay(site,null,done);
        var owner=owner(outcome);
        var campaign=DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),site,new DonationCampaignKey(config.key()),
            config.displayName(),config.purposeDescription(),config.currency(),config.suggestedAmounts(),config.minimumAmount(),
            config.maximumAmount(),config.customAmountAllowed(),config.anonymousAllowed(),clock.instant());
        return complete(owner,campaigns.insert(campaign),201);
    }
    @Override public Mutation update(SiteId site,DonationCampaignId id,UUID actor,String key,long version,Configuration config) {
        requireSite(site); var fields=fields(config); fields.put("expected_version",version);
        var outcome=begin(site,actor,key,Operation.CAMPAIGN_UPDATE,id,fields);
        if(outcome instanceof IdempotencyService.Completed done) return replay(site,id,done);
        var owner=owner(outcome); var current=locked(site,id,version);
        if(!current.campaignKey().equals(new DonationCampaignKey(config.key()))) throw new DomainValidationException("campaign key is immutable");
        var updated=current.updateConfiguration(config.displayName(),config.purposeDescription(),config.currency(),config.suggestedAmounts(),
            config.minimumAmount(),config.maximumAmount(),config.customAmountAllowed(),config.anonymousAllowed(),clock.instant());
        return complete(owner,campaigns.update(updated,version),200);
    }
    @Override public Mutation changeStatus(SiteId site,DonationCampaignId id,UUID actor,String key,long version,CampaignStatus status) {
        requireSite(site); if(status==null) throw new DomainValidationException("status is required");
        var outcome=begin(site,actor,key,Operation.CAMPAIGN_STATUS,id,Map.of("status",status.name(),"expected_version",version));
        if(outcome instanceof IdempotencyService.Completed done) return replay(site,id,done);
        var owner=owner(outcome); var current=locked(site,id,version);
        var updated=switch(status) {
            case ACTIVE -> current.activate(clock.instant());
            case PAUSED -> current.pause(clock.instant());
            case ARCHIVED -> current.archive(clock.instant());
            case DRAFT -> throw new DomainValidationException("campaign cannot return to draft");
        };
        return complete(owner,campaigns.update(updated,version),200);
    }
    private DonationCampaign locked(SiteId site,DonationCampaignId id,long version) {
        if(version<0) throw new DomainValidationException("invalid expected version");
        var current=campaigns.findByIdForUpdate(site,id).orElseThrow(DonationCampaignService::unavailable);
        if(current.version()!=version) throw new OptimisticLockingFailureException("Stale campaign version");
        return current;
    }
    private Site requireSite(SiteId site) { return sites.findById(site).orElseThrow(DonationCampaignService::unavailable); }
    private IdempotencyService.Outcome begin(SiteId site,UUID actor,String key,Operation operation,DonationCampaignId id,Map<String,?> fields) {
        if(key==null || key.isBlank() || key.length()>255 || key.chars().anyMatch(Character::isISOControl))
            throw new DomainValidationException("invalid idempotency key");
        return idempotency.begin(new Scope(Actor.ADMIN,site.value(),actor,operation,id==null?null:id.value()),key,idempotency.fingerprint(fields));
    }
    private IdempotencyService.Owner owner(IdempotencyService.Outcome outcome) {
        if(outcome instanceof IdempotencyService.Owner owner) return owner;
        if(outcome instanceof IdempotencyService.Conflict) throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");
        throw new PaymentApplicationException("OPERATION_IN_PROGRESS",409,"Operation in progress");
    }
    private Mutation complete(IdempotencyService.Owner owner,DonationCampaign campaign,int status) {
        idempotency.complete(owner,new Resource(ResourceType.DONATION_CAMPAIGN,campaign.id().value()),status,null);
        return new Mutation(campaign,false);
    }
    private Mutation replay(SiteId site,DonationCampaignId id,IdempotencyService.Completed completed) {
        if(completed.resource().type()!=ResourceType.DONATION_CAMPAIGN || id!=null && !id.value().equals(completed.resource().id()))
            throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");
        return new Mutation(get(site,new DonationCampaignId(completed.resource().id())),true);
    }
    private Map<String,Object> fields(Configuration config) {
        var fields=new TreeMap<String,Object>();
        // Free text may be multiline or absent. Hash each typed field independently rather than
        // imposing the payment fingerprint encoder's text/list limits on campaign configuration.
        fields.put("key_digest",textDigest(config.key())); fields.put("display_name_digest",textDigest(config.displayName()));
        fields.put("purpose_description_digest",textDigest(config.purposeDescription()));
        fields.put("currency_digest",textDigest(config.currency()));
        fields.put("suggested_amounts_digest",textDigest(config.suggestedAmounts()==null?null:config.suggestedAmounts().toString()));
        fields.put("minimum_amount",config.minimumAmount()); fields.put("maximum_amount",config.maximumAmount());
        fields.put("custom_amount_allowed",config.customAmountAllowed()); fields.put("anonymous_allowed",config.anonymousAllowed());
        return fields;
    }
    private String textDigest(String value) { return digest.sha256Hex(value==null?"absent":"present:"+value); }
    @Override @Transactional(readOnly=true)
    public Site requirePublicSite(String siteKey,URI origin) {
        if(origin==null || origin.getHost()==null || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || origin.getRawPath()!=null && !origin.getRawPath().isEmpty()) throw originDenied();
        var site=sites.findByKey(new SiteKey(siteKey)).filter(s->s.status()==SiteStatus.ACTIVE).orElseThrow(DonationCampaignService::unavailable);
        if(site.allowedOrigins().stream().map(URI::toString).noneMatch(origin.toString()::equals)) throw originDenied();
        return site;
    }
    @Override @Transactional(readOnly=true)
    public PublicCampaign getPublic(String siteKey,String campaignKey,URI origin) {
        var site=requirePublicSite(siteKey,origin);
        var c=campaigns.findByKey(site.id(),new DonationCampaignKey(campaignKey))
            .filter(value->value.status()==CampaignStatus.ACTIVE).orElseThrow(DonationCampaignService::unavailable);
        return new PublicCampaign(c.campaignKey().value(),c.displayName(),c.purposeDescription(),c.currency(),c.suggestedAmounts(),
            c.minimumAmount(),c.maximumAmount(),c.customAmountAllowed(),c.anonymousAllowed());
    }
    public static PaymentApplicationException unavailable() { return new PaymentApplicationException("CAMPAIGN_NOT_AVAILABLE",404,"Campaign not available"); }
    public static PaymentApplicationException originDenied() { return new PaymentApplicationException("ORIGIN_NOT_ALLOWED",403,"Origin not allowed"); }
}
