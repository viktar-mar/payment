package dev.genericpayments.platform.donations.adapter.out.persistence;

import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository @Transactional
public class DonationPersistenceAdapter implements DonationCampaignStore,DonationStore {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    DonationPersistenceAdapter(EntityManager entities,JdbcTemplate jdbc) { this.entities=entities; this.jdbc=jdbc; }
    @Override @Transactional(readOnly=true)
    public List<DonationCampaign> list(SiteId site,int offset,int limit) {
        return entities.createQuery("select c from DonationCampaignJpaEntity c where c.siteId=:site order by c.createdAt desc,c.id desc",DonationCampaignJpaEntity.class)
            .setParameter("site",site.value()).setFirstResult(offset).setMaxResults(limit)
            .getResultStream().map(this::campaign).toList();
    }
    private TypedQuery<DonationCampaignJpaEntity> campaignQuery(SiteId site,String field,Object value) {
        return entities.createQuery("select c from DonationCampaignJpaEntity c where c.siteId=:site and c."+field+"=:value",DonationCampaignJpaEntity.class)
            .setParameter("site",site.value()).setParameter("value",value);
    }
    @Override @Transactional(readOnly=true)
    public Optional<DonationCampaign> findById(SiteId site,DonationCampaignId id) {
        return campaignQuery(site,"id",id.value()).getResultStream().findFirst().map(this::campaign);
    }
    @Override @Transactional(readOnly=true)
    public Optional<DonationCampaign> findByKey(SiteId site,DonationCampaignKey key) {
        return campaignQuery(site,"campaignKey",key.value()).getResultStream().findFirst().map(this::campaign);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<DonationCampaign> findByIdForUpdate(SiteId site,DonationCampaignId id) {
        return lockedCampaign(campaignQuery(site,"id",id.value()));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<DonationCampaign> findByKeyForUpdate(SiteId site,DonationCampaignKey key) {
        return lockedCampaign(campaignQuery(site,"campaignKey",key.value()));
    }
    private Optional<DonationCampaign> lockedCampaign(TypedQuery<DonationCampaignJpaEntity> query) {
        return query.setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().map(e->{
            entities.refresh(e,LockModeType.PESSIMISTIC_WRITE); return campaign(e);
        });
    }
    @Override public DonationCampaign insert(DonationCampaign campaign) {
        if(campaign.version()!=0) throw new IllegalArgumentException("Initial campaign version must be zero");
        var e=new DonationCampaignJpaEntity(); e.id=campaign.id().value(); e.siteId=campaign.siteId().value();
        copy(campaign,e); entities.persist(e); entities.flush(); return campaign(e);
    }
    @Override public DonationCampaign update(DonationCampaign campaign,long expectedVersion) {
        var e=campaignQuery(campaign.siteId(),"id",campaign.id().value()).getResultStream().findFirst()
            .orElseThrow(()->new OptimisticLockingFailureException("Campaign no longer exists"));
        if(e.version!=expectedVersion) throw new OptimisticLockingFailureException("Stale campaign version");
        if(campaign.version()<=expectedVersion || !e.campaignKey.equals(campaign.campaignKey().value())
                || !canonical(e.createdAt).equals(canonical(campaign.createdAt()))
                || canonical(campaign.updatedAt()).isBefore(canonical(e.updatedAt))
                || (e.activatedOnce && (!campaign.activatedOnce() || !e.currency.equals(campaign.currency())))
                || "ARCHIVED".equals(e.status)) throw new IllegalArgumentException("Campaign history is immutable");
        entities.flush();
        int changed=jdbc.update("update donation_campaigns set version=? where site_id=? and id=? and version=?",
            campaign.version(),campaign.siteId().value(),campaign.id().value(),expectedVersion);
        if(changed!=1) throw new OptimisticLockingFailureException("Stale campaign version");
        // Delete the old order before inserting its replacement: swapping two amounts otherwise
        // violates the campaign/amount unique key during Hibernate's per-position updates.
        e.suggestedAmounts.clear(); entities.flush();
        copy(campaign,e); entities.flush(); return campaign(e);
    }
    private void copy(DonationCampaign c,DonationCampaignJpaEntity e) {
        e.campaignKey=c.campaignKey().value(); e.displayName=c.displayName(); e.purposeDescription=c.purposeDescription();
        e.currency=c.currency(); e.minimumAmount=c.minimumAmount(); e.maximumAmount=c.maximumAmount();
        e.customAmountAllowed=c.customAmountAllowed(); e.anonymousAllowed=c.anonymousAllowed();
        e.status=c.status().name(); e.activatedOnce=c.activatedOnce(); e.version=c.version();
        e.createdAt=canonical(c.createdAt()); e.updatedAt=canonical(c.updatedAt());
        e.suggestedAmounts.clear(); e.suggestedAmounts.addAll(c.suggestedAmounts());
    }
    private DonationCampaign campaign(DonationCampaignJpaEntity e) {
        return DonationCampaign.reconstitute(new DonationCampaignId(e.id),new SiteId(e.siteId),new DonationCampaignKey(e.campaignKey),
            e.displayName,e.purposeDescription,e.currency,e.suggestedAmounts,e.minimumAmount,e.maximumAmount,
            e.customAmountAllowed,e.anonymousAllowed,CampaignStatus.valueOf(e.status),e.activatedOnce,e.version,e.createdAt,e.updatedAt);
    }
    private TypedQuery<DonationJpaEntity> donationQuery(SiteId site,DonationId id) {
        return entities.createQuery("select d from DonationJpaEntity d where d.siteId=:site and d.id=:id",DonationJpaEntity.class)
            .setParameter("site",site.value()).setParameter("id",id.value());
    }
    @Override @Transactional(readOnly=true)
    public Optional<Donation> findById(SiteId site,DonationId id) {
        return donationQuery(site,id).getResultStream().findFirst().map(this::donation);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Donation> findByIdForUpdate(SiteId site,DonationId id) {
        return donationQuery(site,id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().map(e->{
            entities.refresh(e,LockModeType.PESSIMISTIC_WRITE);return donation(e);
        });
    }
    @Override @Transactional(readOnly=true)
    public Optional<EncryptedValue> findEncryptedMessage(SiteId site,DonationId id) {
        return donationQuery(site,id).getResultStream().findFirst().filter(e->e.messageCiphertext!=null)
            .map(e->new EncryptedValue(e.messageCiphertext,e.messageNonce,e.messageKeyVersion));
    }
    @Override public Donation save(Donation donation) { return saveDonation(donation,null); }
    @Override public Donation save(Donation donation,EncryptedValue encryptedMessage) {
        return saveDonation(donation,Objects.requireNonNull(encryptedMessage,"encrypted message"));
    }
    private Donation saveDonation(Donation d,EncryptedValue encrypted) {
        var e=donationQuery(d.siteId(),d.id()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
        // A query lock does not refresh an entity already present in this transaction's cache.
        // Re-read under the lock so a concurrent redaction cannot be overwritten by stale state.
        if(e!=null) entities.refresh(e,LockModeType.PESSIMISTIC_WRITE);
        boolean insert=e==null;
        if(insert) {
            e=new DonationJpaEntity(); e.id=d.id().value(); e.siteId=d.siteId().value(); e.campaignId=d.campaignId().value(); e.createdAt=canonical(d.createdAt());
        } else if(!e.campaignId.equals(d.campaignId().value()) || !canonical(e.createdAt).equals(canonical(d.createdAt()))
                || canonical(d.updatedAt()).isBefore(canonical(e.updatedAt))
                || (!e.messageState.equals(d.messageState().name()) && !("PRESENT".equals(e.messageState) && d.messageState()==DonorMessageState.REDACTED))) {
            throw new IllegalArgumentException("Donation history is immutable");
        }
        if(d.messageState()==DonorMessageState.PRESENT) {
            if(!d.encryptedMessageRef().orElseThrow().equals(DonationStore.messageReference(d.id())))
                throw new IllegalArgumentException("Unknown encrypted message reference");
            if(encrypted==null && e.messageCiphertext==null) throw new IllegalArgumentException("New message requires encrypted material");
            if(encrypted!=null) { e.messageCiphertext=encrypted.ciphertext(); e.messageNonce=encrypted.nonce(); e.messageKeyVersion=encrypted.keyVersion(); }
        } else {
            if(encrypted!=null) throw new IllegalArgumentException("Message material requires PRESENT state");
            e.messageCiphertext=null; e.messageNonce=null; e.messageKeyVersion=null;
        }
        e.messageState=d.messageState().name(); e.updatedAt=canonical(d.updatedAt());
        if(insert) entities.persist(e);
        entities.flush(); return donation(e);
    }
    private Donation donation(DonationJpaEntity e) {
        var id=new DonationId(e.id);
        return Donation.reconstitute(id,new SiteId(e.siteId),new DonationCampaignId(e.campaignId),
            "PRESENT".equals(e.messageState)?DonationStore.messageReference(id):null,DonorMessageState.valueOf(e.messageState),e.createdAt,e.updatedAt);
    }
    private static Instant canonical(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
}
