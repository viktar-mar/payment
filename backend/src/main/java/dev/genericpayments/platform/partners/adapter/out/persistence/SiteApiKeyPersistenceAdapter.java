package dev.genericpayments.platform.partners.adapter.out.persistence;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.partners.application.port.out.SiteApiKeyPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
@Repository
public class SiteApiKeyPersistenceAdapter implements SiteApiKeyPort {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public SiteApiKeyPersistenceAdapter(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    @Override @Transactional public boolean insert(SiteApiKey key) {
        return jdbc.update("""
            insert into site_api_keys(id,site_id,prefix,key_hash,scopes,status,created_at,expires_at)
            values (?,?,?,?,?::jsonb,'ACTIVE',?,?) on conflict on constraint uq_api_keys_prefix do nothing
            """,key.id().value(),key.siteId().value(),key.prefix(),key.keyHash(),mapper.writeValueAsString(key.scopes().stream().map(PartnerScope::value).sorted().toList()),
            timestamp(key.createdAt()),timestamp(key.expiresAt())) == 1;
    }
    @Override @Transactional(readOnly=true) public Optional<SiteApiKey> findByPrefix(String prefix) {
        return jdbc.query("select * from site_api_keys where prefix=?",this::map,prefix).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public Optional<SiteApiKey> lock(SiteId site,SiteApiKeyId id) {
        return jdbc.query("select * from site_api_keys where id=? and site_id=? for update",this::map,id.value(),site.value()).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public void update(SiteApiKey key) {
        if (jdbc.update("update site_api_keys set status=?,expires_at=?,revoked_at=?,version=version+1 where id=? and site_id=?",
            key.status().name(),timestamp(key.expiresAt()),timestamp(key.revokedAt()),key.id().value(),key.siteId().value()) != 1) throw new IllegalStateException("Site API key missing");
    }
    @Override @Transactional public void touch(SiteApiKeyId id,Instant now) {
        jdbc.update("""
            update site_api_keys set last_used_at=?,version=version+1 where id=? and status='ACTIVE'
            and (expires_at is null or expires_at>?) and (last_used_at is null or last_used_at<=?)
            """,timestamp(now),id.value(),timestamp(now),timestamp(now.minus(Duration.ofMinutes(5))));
    }
    private SiteApiKey map(ResultSet row,int index) throws SQLException {
        var scopes=mapper.readValue(row.getString("scopes"),new TypeReference<Set<String>>() {});
        var typed=new HashSet<PartnerScope>(); scopes.forEach(scope -> typed.add(PartnerScope.parse(scope)));
        return new SiteApiKey(new SiteApiKeyId(row.getObject("id",UUID.class)),new SiteId(row.getObject("site_id",UUID.class)),
            row.getString("prefix"),row.getString("key_hash"),typed,SiteApiKey.Status.valueOf(row.getString("status")),
            instant(row,"created_at"),instant(row,"expires_at"),instant(row,"last_used_at"),instant(row,"revoked_at"));
    }
    private static Instant instant(ResultSet row,String column) throws SQLException { var value=row.getTimestamp(column); return value==null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value==null ? null : Timestamp.from(value); }
}
