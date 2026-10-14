package dev.genericpayments.platform.webhooks.adapter.out.persistence;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import dev.genericpayments.platform.webhooks.domain.CallbackConfiguration;
import dev.genericpayments.platform.shared.application.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class CallbackPersistenceAdapter implements CallbackConfigurationPort {
    private final JdbcTemplate jdbc;private final EncryptedSecretPort encryption;private final RandomTokenPort random;private final Clock clock;
    public CallbackPersistenceAdapter(JdbcTemplate jdbc,EncryptedSecretPort encryption,RandomTokenPort random,Clock clock){this.jdbc=jdbc;this.encryption=encryption;this.random=random;this.clock=clock;}
    @Override @Transactional public Reveal configure(UUID site,String endpoint,boolean enabled) {
        String normalized=endpoint;
        lockSite(site);var old=find(site);Instant now=clock.instant();
        if(old.isPresent()){jdbc.update("update site_callback_configs set endpoint_url=?,enabled=?,updated_at=?,version=version+1 where site_id=?",normalized,enabled,Timestamp.from(now),site);return new Reveal(find(site).orElseThrow(),null);}
        String secret=random.issue(32);UUID id=insertSecret(site,1,secret,now);
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,enabled,active_secret_version_id,created_at,updated_at) values (?,?,?,?,?,?)",site,normalized,enabled,id,Timestamp.from(now),Timestamp.from(now));return new Reveal(find(site).orElseThrow(),secret);
    }
    @Override @Transactional public Reveal rotate(UUID site) {
        lockSite(site);
        // The current-retry path also locks configuration before the selected secret.
        jdbc.queryForList("select site_id from site_callback_configs where site_id=? for update",site);
        var old=find(site).orElseThrow(()->new PaymentApplicationException("CALLBACK_CONFIG_INVALID",409,"Callback configuration required"));
        jdbc.queryForList("select id from site_callback_secret_versions where id=? for update",old.secretId());Instant now=clock.instant();
        int version=jdbc.queryForObject("select coalesce(max(secret_version),0)+1 from site_callback_secret_versions where site_id=?",Integer.class,site);
        String secret=random.issue(32);UUID id=insertSecret(site,version,secret,now);
        jdbc.update("update site_callback_secret_versions set retire_after=greatest(coalesce(retire_after,?),?) where id=?",Timestamp.from(now.plus(Duration.ofHours(24))),Timestamp.from(now.plus(Duration.ofHours(24))),old.secretId());
        jdbc.update("update site_callback_configs set active_secret_version_id=?,updated_at=?,version=version+1 where site_id=?",id,Timestamp.from(now),site);return new Reveal(find(site).orElseThrow(),secret);
    }
    private void lockSite(UUID site){if(jdbc.queryForList("select id from sites where id=? for update",site).isEmpty())throw new PaymentApplicationException("SITE_NOT_AVAILABLE",404,"Site not found");}
    private UUID insertSecret(UUID site,int version,String secret,Instant now){var encrypted=encryption.encrypt(secret);UUID id=UUID.randomUUID();jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,?,?,?,?,?)",id,site,version,encrypted.ciphertext(),encrypted.nonce(),encrypted.keyVersion(),Timestamp.from(now));return id;}
    @Override @Transactional(readOnly=true) public Optional<CallbackConfiguration> find(UUID site){return jdbc.query("select c.*,s.secret_version from site_callback_configs c join site_callback_secret_versions s on s.id=c.active_secret_version_id where c.site_id=?",(r,n)->new CallbackConfiguration(site,r.getString("endpoint_url"),r.getBoolean("enabled"),r.getObject("active_secret_version_id",UUID.class),r.getInt("secret_version")),site).stream().findFirst();}
    @Override @Transactional public int cleanup(Instant now){return jdbc.update("""
        update site_callback_secret_versions s set ciphertext=null,nonce=null,master_key_version=null,destroyed_at=?
        where destroyed_at is null and retire_after<=?
        and not exists(select 1 from site_callback_configs c where c.active_secret_version_id=s.id)
        and not exists(select 1 from outbox_events e where e.secret_version_id=s.id and e.status='PENDING')
        and not exists(select 1 from outbox_retry_requests r where r.secret_version_id=s.id and r.status in ('PENDING','PROCESSING'))
        """,Timestamp.from(now),Timestamp.from(now));}
}
