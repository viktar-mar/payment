package dev.genericpayments.platform.customers.adapter.out.persistence;

import static dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceMapper.Digest;
import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;
import dev.genericpayments.platform.customers.application.port.out.IdentityLinkProofStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository @Transactional
public class IdentityLinkProofPersistenceAdapter implements IdentityLinkProofStore {
    private final JdbcTemplate jdbc;
    public IdentityLinkProofPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public IdentityLinkProof save(IdentityLinkProof value) {
        Digest subject=Digest.parse(value.subjectDigest()),token=Digest.parse(value.tokenDigest());
        jdbc.update("""
            insert into identity_link_proofs(id,site_id,origin,mode,issuer_key,subject_digest,subject_digest_key_version,
              token_digest,token_digest_key_version,verified_at,created_at,expires_at,consumed_at,version)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,0)
            """,value.id(),value.siteId().value(),value.origin(),value.mode().name(),value.issuerKey(),subject.value(),subject.keyVersion(),
            token.value(),token.keyVersion(),timestamp(value.verifiedAt()),timestamp(value.createdAt()),timestamp(value.expiresAt()),timestamp(value.consumedAt()));
        for(String reference:value.subjectDigests()) {
            Digest alias=Digest.parse(reference);
            jdbc.update("""
                insert into identity_link_proof_subject_aliases(site_id,proof_id,subject_digest,subject_digest_key_version)
                values (?,?,?,?)
                """,value.siteId().value(),value.id(),alias.value(),alias.keyVersion());
        }
        return new IdentityLinkProof(value.id(),value.siteId(),value.origin(),value.mode(),value.issuerKey(),value.subjectDigest(),
            value.subjectDigests(),value.tokenDigest(),
            canonical(value.verifiedAt()),canonical(value.createdAt()),canonical(value.expiresAt()),
            value.consumedAt()==null?null:canonical(value.consumedAt()),value.version());
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<IdentityLinkProof> findForUpdate(UUID id) {
        return jdbc.query("select * from identity_link_proofs where id=? for update",this::map,id).stream().findFirst().map(this::withAliases);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<IdentityLinkProof> findForUpdate(SiteId site,List<String> references) {
        if(references==null||references.isEmpty())return Optional.empty();
        List<Digest> values=references.stream().map(Digest::parse).distinct().toList();
        String predicates=java.util.stream.IntStream.range(0,values.size()).mapToObj(index -> "(token_digest=? and token_digest_key_version=?)")
            .collect(java.util.stream.Collectors.joining(" or "));
        List<Object> arguments=new ArrayList<>();arguments.add(site.value());
        values.forEach(value -> {arguments.add(value.value());arguments.add(value.keyVersion());});
        var matches=jdbc.query("select * from identity_link_proofs where site_id=? and ("+predicates+") for update",this::map,arguments.toArray());
        if(matches.size()>1)throw new IllegalStateException("Multiple identity link proofs matched one credential");
        return matches.stream().findFirst().map(this::withAliases);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public IdentityLinkProof consume(IdentityLinkProof proof,java.time.Instant consumedAt) {
        IdentityLinkProof consumed=proof.consume(canonical(consumedAt));
        int updated=jdbc.update("update identity_link_proofs set consumed_at=?,version=version+1 where site_id=? and id=? and version=? and consumed_at is null",
            timestamp(consumed.consumedAt()),proof.siteId().value(),proof.id(),proof.version());
        if(updated!=1)throw new org.springframework.dao.OptimisticLockingFailureException("Identity link proof is no longer available");
        return consumed;
    }
    private IdentityLinkProof map(java.sql.ResultSet r,int n)throws java.sql.SQLException {
        String subject=new Digest(r.getString("subject_digest"),r.getString("subject_digest_key_version")).reference();
        return new IdentityLinkProof(r.getObject("id",UUID.class),new SiteId(r.getObject("site_id",UUID.class)),r.getString("origin"),
            CustomerAuthenticationMode.valueOf(r.getString("mode")),r.getString("issuer_key"),
            subject,List.of(subject),
            new Digest(r.getString("token_digest"),r.getString("token_digest_key_version")).reference(),canonical(r.getTimestamp("verified_at").toInstant()),
            canonical(r.getTimestamp("created_at").toInstant()),canonical(r.getTimestamp("expires_at").toInstant()),
            r.getTimestamp("consumed_at")==null?null:canonical(r.getTimestamp("consumed_at").toInstant()),r.getLong("version"));
    }
    private IdentityLinkProof withAliases(IdentityLinkProof proof) {
        var aliases=new LinkedHashSet<String>();
        aliases.add(proof.subjectDigest());
        jdbc.query("""
            select subject_digest,subject_digest_key_version from identity_link_proof_subject_aliases
             where site_id=? and proof_id=? order by subject_digest_key_version,subject_digest
            """,(result,index) -> new Digest(result.getString("subject_digest"),
                result.getString("subject_digest_key_version")).reference(),proof.siteId().value(),proof.id()).forEach(aliases::add);
        return new IdentityLinkProof(proof.id(),proof.siteId(),proof.origin(),proof.mode(),proof.issuerKey(),proof.subjectDigest(),
            List.copyOf(aliases),proof.tokenDigest(),proof.verifiedAt(),proof.createdAt(),proof.expiresAt(),proof.consumedAt(),proof.version());
    }
    private static Timestamp timestamp(java.time.Instant value) { return value==null?null:Timestamp.from(canonical(value)); }
}
