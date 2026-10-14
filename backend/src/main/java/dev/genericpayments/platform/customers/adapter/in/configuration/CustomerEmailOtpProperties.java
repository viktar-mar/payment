package dev.genericpayments.platform.customers.adapter.in.configuration;

import dev.genericpayments.platform.customers.application.port.out.EmailOtpPolicyPort;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("payment.customer-email-otp")
public final class CustomerEmailOtpProperties implements EmailOtpPolicyPort {
    private final Duration ttl;
    private final int maxAttempts;
    private final Duration resendCooldown;
    private final RateLimit rateLimit;
    private final List<String> trustedProxyCidrs;
    private final Outbox outbox;
    private final Smtp smtp;
    private final Map<String,Template> templates;

    public CustomerEmailOtpProperties(Duration ttl,Integer maxAttempts,Duration resendCooldown,RateLimit rateLimit,
            List<String> trustedProxyCidrs,Outbox outbox,Smtp smtp,Map<String,Template> templates) {
        this.ttl=duration(ttl,Duration.ofMinutes(10),Duration.ofMinutes(1),Duration.ofMinutes(30),"OTP TTL");
        this.maxAttempts=bound(maxAttempts,5,1,5,"OTP maximum attempts");
        this.resendCooldown=duration(resendCooldown,Duration.ofSeconds(60),Duration.ofSeconds(1),Duration.ofMinutes(10),"OTP resend cooldown");
        this.rateLimit=rateLimit==null?new RateLimit(null,null,null,null,null):rateLimit;
        this.trustedProxyCidrs=trustedProxyCidrs==null?List.of():List.copyOf(trustedProxyCidrs);
        if(this.trustedProxyCidrs.size()>100) throw new IllegalArgumentException("Too many trusted proxies");
        this.outbox=outbox==null?new Outbox(null,null,null,null,null,null,null,null,null,null):outbox;
        this.smtp=smtp==null?new Smtp("",null,"","","","",null,null,null,null,null):smtp;
        this.templates=templates==null||templates.isEmpty()
            ?Map.of("otp-default",new Template("Your verification code","Your verification code is {{code}}. It expires in {{ttl_minutes}} minutes."))
            :validatedTemplates(templates);
    }

    @Override public Duration ttl(){return ttl;}
    @Override public int maxAttempts(){return maxAttempts;}
    @Override public Duration resendCooldown(){return resendCooldown;}
    public RateLimit rateLimit(){return rateLimit;}
    public List<String> trustedProxyCidrs(){return trustedProxyCidrs;}
    public Outbox outbox(){return outbox;}
    public Smtp smtp(){return smtp;}
    public Map<String,Template> templates(){return templates;}
    @Override public int siteRequestLimit(){return rateLimit.siteRequests();}
    @Override public int emailRequestLimit(){return rateLimit.emailRequests();}
    @Override public int sourceAddressRequestLimit(){return rateLimit.sourceIpRequests();}
    @Override public Duration rateWindow(){return rateLimit.window();}
    @Override public int mailBatchSize(){return outbox.batchSize();}
    @Override public Duration mailLease(){return outbox.lease();}
    @Override public int mailMaxAttempts(){return outbox.maxAttempts();}
    @Override public Duration mailMaximumBackoff(){return outbox.maximumBackoff();}
    @Override public Duration mailTerminalRetention(){return outbox.terminalRetention();}
    @Override public int mailCleanupBatch(){return outbox.cleanupBatch();}
    @Override public Duration challengeRetention(){return outbox.challengeRetention();}
    @Override public int challengeCleanupBatch(){return outbox.challengeCleanupBatch();}
    @Override public boolean mailWorkerEnabled(){return outbox.workerEnabled();}
    @Override public Duration mailWorkerDelay(){return outbox.delay();}
    @Override public int rateCleanupBatch(){return rateLimit.cleanupBatch();}

    public record RateLimit(Integer siteRequests,Integer emailRequests,Integer sourceIpRequests,Duration window,Integer cleanupBatch) {
        public RateLimit {
            siteRequests=bound(siteRequests,60,1,10_000,"Site OTP limit");
            emailRequests=bound(emailRequests,5,1,10_000,"email OTP limit");
            sourceIpRequests=bound(sourceIpRequests,10,1,10_000,"source-IP OTP limit");
            window=duration(window,Duration.ofMinutes(10),Duration.ofMinutes(1),Duration.ofDays(1),"OTP rate window");
            cleanupBatch=bound(cleanupBatch,1000,1,100_000,"OTP cleanup batch");
        }
    }

    public record Outbox(Integer batchSize,Duration lease,Integer maxAttempts,Duration maximumBackoff,
            Duration terminalRetention,Integer cleanupBatch,Boolean workerEnabled,Duration delay,
            Duration challengeRetention,Integer challengeCleanupBatch) {
        public Outbox(Integer batchSize,Duration lease,Integer maxAttempts,Duration maximumBackoff,
                Duration terminalRetention,Integer cleanupBatch) {
            this(batchSize,lease,maxAttempts,maximumBackoff,terminalRetention,cleanupBatch,null,null,null,null);
        }
        @ConstructorBinding
        public Outbox {
            batchSize=bound(batchSize,50,1,1000,"mail batch");
            lease=duration(lease,Duration.ofSeconds(60),Duration.ofSeconds(5),Duration.ofMinutes(15),"mail lease");
            maxAttempts=bound(maxAttempts,8,1,8,"mail attempts");
            maximumBackoff=duration(maximumBackoff,Duration.ofMinutes(30),Duration.ofSeconds(1),Duration.ofHours(24),"mail backoff");
            terminalRetention=duration(terminalRetention,Duration.ofDays(7),Duration.ofHours(1),Duration.ofDays(30),"mail retention");
            cleanupBatch=bound(cleanupBatch,1000,1,100_000,"mail cleanup batch");
            workerEnabled=workerEnabled==null?Boolean.TRUE:workerEnabled;
            delay=duration(delay,Duration.ofSeconds(1),Duration.ofMillis(100),Duration.ofMinutes(1),"mail worker delay");
            challengeRetention=duration(challengeRetention,Duration.ofHours(24),Duration.ofHours(1),Duration.ofDays(30),"OTP challenge retention");
            challengeCleanupBatch=bound(challengeCleanupBatch,1000,1,100_000,"OTP challenge cleanup batch");
        }
    }

    public record Template(String subject,String body) {
        public Template {
            subject=template(subject,200,"subject"); body=template(body,10_000,"body");
            if(subject.indexOf('\r')>=0||subject.indexOf('\n')>=0) throw new IllegalArgumentException("Invalid template subject");
            if(!subject.contains("{{code}}")&&!body.contains("{{code}}"))
                throw new IllegalArgumentException("Mail template must render an OTP code");
        }
    }

    public record Smtp(String host,Integer port,String username,String password,String senderRef,String fromAddress,
            Boolean startTlsEnabled,Boolean sslEnabled,Integer connectTimeoutMs,Integer readTimeoutMs,Integer writeTimeoutMs) {
        public Smtp {
            host=trim(host); username=trim(username); password=password==null?"":password; senderRef=trim(senderRef); fromAddress=trim(fromAddress);
            port=bound(port,587,1,65535,"SMTP port");
            startTlsEnabled=startTlsEnabled==null?Boolean.TRUE:startTlsEnabled;
            sslEnabled=sslEnabled==null?Boolean.FALSE:sslEnabled;
            connectTimeoutMs=bound(connectTimeoutMs,2000,100,30_000,"SMTP connect timeout");
            readTimeoutMs=bound(readTimeoutMs,5000,100,60_000,"SMTP read timeout");
            writeTimeoutMs=bound(writeTimeoutMs,5000,100,60_000,"SMTP write timeout");
            if(startTlsEnabled&&sslEnabled) throw new IllegalArgumentException("SMTP TLS modes are mutually exclusive");
        }
        public boolean configured(boolean development) {
            boolean basics=!host.isBlank()&&!senderRef.isBlank()&&!fromAddress.isBlank();
            return basics&&(development||((startTlsEnabled||sslEnabled)&&!username.isBlank()&&!password.isBlank()));
        }
        @Override public String toString(){return "Smtp[redacted]";}
    }

    private static int bound(Integer value,int fallback,int min,int max,String name) {
        int resolved=value==null?fallback:value;
        if(resolved<min||resolved>max) throw new IllegalArgumentException("Invalid "+name);
        return resolved;
    }
    private static Duration duration(Duration value,Duration fallback,Duration min,Duration max,String name) {
        Duration resolved=value==null?fallback:value;
        if(resolved.compareTo(min)<0||resolved.compareTo(max)>0) throw new IllegalArgumentException("Invalid "+name);
        return resolved;
    }
    private static String trim(String value){return value==null?"":value.trim();}
    private static Map<String,Template> validatedTemplates(Map<String,Template> source) {
        if(source.size()>100) throw new IllegalArgumentException("Too many mail templates");
        var result=new TreeMap<String,Template>();
        source.forEach((key,value) -> {
            if(key==null||!key.matches("[a-z][a-z0-9-]{0,62}")||value==null) throw new IllegalArgumentException("Invalid mail template");
            result.put(key,value);
        });
        return Collections.unmodifiableMap(result);
    }
    private static String template(String value,int maximum,String name) {
        if(value==null||value.isBlank()||value.length()>maximum) throw new IllegalArgumentException("Invalid template "+name);
        String stripped=value.replace("{{code}}","").replace("{{ttl_minutes}}","");
        if(stripped.contains("{{")||stripped.contains("}}")||stripped.indexOf('{')>=0||stripped.indexOf('}')>=0)
            throw new IllegalArgumentException("Unknown mail template placeholder");
        return value;
    }
}
