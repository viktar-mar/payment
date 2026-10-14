package dev.genericpayments.platform.customers.adapter.out.persistence;

import static dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceMapper.Digest;
import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional
public class AuthenticationAttemptPersistenceAdapter implements AuthenticationAttemptStore,ConsumedSiteTokenStore {
    private final JdbcTemplate jdbc;
    public AuthenticationAttemptPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override @Transactional(readOnly=true)
    public Optional<OidcAuthenticationAttempt> find(UUID id) { return query(id,false); }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<OidcAuthenticationAttempt> findForUpdate(UUID id) { return query(id,true); }
    private Optional<OidcAuthenticationAttempt> query(UUID id,boolean lock) {
        return jdbc.query("select * from oidc_authentication_attempts where id=?"+(lock?" for update":""),(row,index)->attempt(row),id).stream().findFirst();
    }
    @Override public OidcAuthenticationAttempt save(OidcAuthenticationAttempt value) {
        Digest digest=Digest.parse(value.nonceDigest());
        int changed;
        if(value.version()==0 && find(value.id()).isEmpty()) {
            changed=jdbc.update("""
                insert into oidc_authentication_attempts(id,site_id,provider_key,origin,intent,nonce_digest,nonce_digest_key_version,
                  provider_config_version,created_at,expires_at,consumed_at,version) values (?,?,?,?,?,?,?,?,?,?,?,0)
                """,value.id(),value.siteId().value(),value.providerKey(),value.origin(),value.intent().name(),digest.value(),digest.keyVersion(),
                value.providerConfigVersion(),timestamp(value.createdAt()),timestamp(value.expiresAt()),timestamp(value.consumedAt()));
        } else {
            changed=jdbc.update("update oidc_authentication_attempts set consumed_at=?,version=version+1 where id=? and version=? and consumed_at is null",
                timestamp(value.consumedAt()),value.id(),value.version());
        }
        if(changed!=1) throw new org.springframework.dao.OptimisticLockingFailureException("Stale authentication attempt");
        return find(value.id()).orElseThrow();
    }
    @Override public void saveAll(List<ConsumedSiteTokenJti> values) {
        for(ConsumedSiteTokenJti value:values) {
            Digest digest=Digest.parse(value.jtiDigest());
            jdbc.update("insert into consumed_site_token_jtis(id,site_id,issuer_key,jti_digest,jti_digest_key_version,consumed_at,expires_at,version) values (?,?,?,?,?,?,?,0)",
                value.id(),value.siteId().value(),value.issuerKey(),digest.value(),digest.keyVersion(),timestamp(value.consumedAt()),timestamp(value.expiresAt()));
        }
    }
    private static OidcAuthenticationAttempt attempt(java.sql.ResultSet row) throws java.sql.SQLException {
        return new OidcAuthenticationAttempt(row.getObject("id",UUID.class),new SiteId(row.getObject("site_id",UUID.class)),row.getString("provider_key"),row.getString("origin"),
            AuthenticationIntent.valueOf(row.getString("intent")),new Digest(row.getString("nonce_digest"),row.getString("nonce_digest_key_version")).reference(),
            row.getLong("provider_config_version"),canonical(row.getTimestamp("created_at").toInstant()),canonical(row.getTimestamp("expires_at").toInstant()),
            row.getTimestamp("consumed_at")==null?null:canonical(row.getTimestamp("consumed_at").toInstant()),row.getLong("version"));
    }
    private static Timestamp timestamp(java.time.Instant value) { return value==null?null:Timestamp.from(canonical(value)); }
}
