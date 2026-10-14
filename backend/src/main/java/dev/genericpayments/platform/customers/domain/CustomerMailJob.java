package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;

public record CustomerMailJob(UUID id,SiteId siteId,EmailOtpChallengeId challengeId,Status status,int attemptCount,
        Instant nextAttemptAt,String leaseOwner,Instant leaseExpiresAt,ErrorCategory errorCategory,Instant createdAt,
        Instant updatedAt,Instant completedAt,Instant payloadDestroyedAt,long version) {
    public enum Status { PENDING,PROCESSING,SENT,DEAD }
    public enum ErrorCategory { TRANSIENT,PERMANENT,CONFIGURATION,EXHAUSTED }
    public CustomerMailJob {
        if(id==null||siteId==null||challengeId==null||status==null||nextAttemptAt==null||createdAt==null||updatedAt==null)
            throw new DomainValidationException("invalid Customer mail job");
        if(attemptCount<0||attemptCount>8||version<0) throw new DomainValidationException("invalid Customer mail counters");
        if((status==Status.PROCESSING)!=(leaseOwner!=null&&leaseExpiresAt!=null)) throw new DomainValidationException("invalid Customer mail lease");
        if((status==Status.SENT||status==Status.DEAD)!=(completedAt!=null)) throw new DomainValidationException("invalid Customer mail terminal state");
    }
    public static CustomerMailJob pending(UUID id,SiteId site,EmailOtpChallengeId challenge,Instant now) {
        return new CustomerMailJob(id,site,challenge,Status.PENDING,0,now,null,null,null,now,now,null,null,0);
    }
    @Override public String toString(){return "CustomerMailJob[id="+id+", status="+status+", attempts="+attemptCount+"]";}
}
