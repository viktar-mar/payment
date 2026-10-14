package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Optional;

public final class EmailOtpChallenge {
    private final EmailOtpChallengeId id;
    private final SiteId siteId;
    private final String origin;
    private final AuthenticationIntent intent;
    private final String providerKey;
    private final String emailDigest;
    private final String encryptedEmailRef;
    private final String otpDigest;
    private final int attemptCount;
    private final EmailOtpChallengeId previousChallengeId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant consumedAt;
    private final Instant invalidatedAt;
    private final long version;

    private EmailOtpChallenge(EmailOtpChallengeId id,SiteId siteId,String origin,AuthenticationIntent intent,String providerKey,
            String emailDigest,String encryptedEmailRef,String otpDigest,int attemptCount,EmailOtpChallengeId previousChallengeId,
            Instant createdAt,Instant expiresAt,Instant consumedAt,Instant invalidatedAt,long version) {
        this.id=required(id,"challenge id"); this.siteId=required(siteId,"site id");
        this.origin=reference(origin,"origin"); this.intent=required(intent,"intent"); this.providerKey=providerKey(providerKey);
        this.emailDigest=digest(emailDigest,"email digest"); this.encryptedEmailRef=reference(encryptedEmailRef,"encrypted email");
        this.otpDigest=digest(otpDigest,"OTP digest");
        if(attemptCount<0||attemptCount>5) throw new DomainValidationException("invalid OTP attempt count");
        this.attemptCount=attemptCount; this.previousChallengeId=previousChallengeId;
        this.createdAt=required(createdAt,"creation time"); this.expiresAt=required(expiresAt,"expiry time");
        if(!expiresAt.isAfter(createdAt)) throw new DomainValidationException("OTP expiry must follow creation");
        if(consumedAt!=null&&invalidatedAt!=null) throw new DomainValidationException("OTP cannot be consumed and invalidated");
        if(consumedAt!=null&&consumedAt.isBefore(createdAt)||invalidatedAt!=null&&invalidatedAt.isBefore(createdAt))
            throw new DomainValidationException("invalid OTP transition time");
        if(version<0) throw new DomainValidationException("OTP version must not be negative");
        this.consumedAt=consumedAt; this.invalidatedAt=invalidatedAt; this.version=version;
    }

    public static EmailOtpChallenge create(EmailOtpChallengeId id,SiteId siteId,String origin,AuthenticationIntent intent,String providerKey,
            String emailDigest,String encryptedEmailRef,String otpDigest,EmailOtpChallengeId previous,Instant now,Instant expiry) {
        return new EmailOtpChallenge(id,siteId,origin,intent,providerKey,emailDigest,encryptedEmailRef,otpDigest,0,previous,now,expiry,null,null,0);
    }
    public static EmailOtpChallenge reconstitute(EmailOtpChallengeId id,SiteId siteId,String origin,AuthenticationIntent intent,String providerKey,
            String emailDigest,String encryptedEmailRef,String otpDigest,int attempts,EmailOtpChallengeId previous,Instant created,
            Instant expires,Instant consumed,Instant invalidated,long version) {
        return new EmailOtpChallenge(id,siteId,origin,intent,providerKey,emailDigest,encryptedEmailRef,otpDigest,attempts,previous,created,expires,consumed,invalidated,version);
    }
    public boolean activeAt(Instant now) {
        return activeAt(now,5);
    }
    public boolean activeAt(Instant now,int maximumAttempts) {
        if(maximumAttempts<1||maximumAttempts>5) throw new DomainValidationException("invalid OTP maximum attempts");
        return consumedAt==null&&invalidatedAt==null&&attemptCount<maximumAttempts&&now.isBefore(expiresAt);
    }
    public EmailOtpChallenge failedAttempt() {
        return failedAttempt(5);
    }
    public EmailOtpChallenge failedAttempt(int maximumAttempts) {
        if(!activeAt(createdAt,maximumAttempts)) throw new DomainValidationException("OTP challenge is not active");
        return copy(attemptCount+1,consumedAt,invalidatedAt);
    }
    public EmailOtpChallenge consume(Instant now) {
        if(!activeAt(now)) throw new DomainValidationException("OTP challenge is not active");
        return copy(attemptCount,now,null);
    }
    public EmailOtpChallenge invalidate(Instant now) {
        if(consumedAt!=null||invalidatedAt!=null) return this;
        return copy(attemptCount,null,now);
    }
    private EmailOtpChallenge copy(int attempts,Instant consumed,Instant invalidated) {
        return new EmailOtpChallenge(id,siteId,origin,intent,providerKey,emailDigest,encryptedEmailRef,otpDigest,attempts,previousChallengeId,
            createdAt,expiresAt,consumed,invalidated,version);
    }
    public EmailOtpChallengeId id(){return id;} public SiteId siteId(){return siteId;} public String origin(){return origin;}
    public AuthenticationIntent intent(){return intent;} public String providerKey(){return providerKey;} public String emailDigest(){return emailDigest;}
    public String encryptedEmailRef(){return encryptedEmailRef;} public String otpDigest(){return otpDigest;}
    public int attemptCount(){return attemptCount;} public Optional<EmailOtpChallengeId> previousChallengeId(){return Optional.ofNullable(previousChallengeId);}
    public Instant createdAt(){return createdAt;} public Instant expiresAt(){return expiresAt;}
    public Optional<Instant> consumedAt(){return Optional.ofNullable(consumedAt);} public Optional<Instant> invalidatedAt(){return Optional.ofNullable(invalidatedAt);}
    public long version(){return version;}
    @Override public String toString(){return "EmailOtpChallenge[id="+id+", siteId="+siteId+", intent="+intent+"]";}
    private static <T> T required(T value,String name){if(value==null)throw new DomainValidationException(name+" is required");return value;}
    private static String reference(String value,String name){if(value==null||value.isBlank()||value.length()>2048)throw new DomainValidationException("invalid "+name);return value;}
    private static String digest(String value,String name){if(value==null||!value.matches("v[1-9][0-9]{0,8}:[A-Za-z0-9_-]+")||value.length()>512)throw new DomainValidationException("invalid "+name);return value;}
    private static String providerKey(String value){if(value==null||!value.matches("[a-z0-9][a-z0-9-]{2,62}"))throw new DomainValidationException("invalid provider key");return value;}
}
