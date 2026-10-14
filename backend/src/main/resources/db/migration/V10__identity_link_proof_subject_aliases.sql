-- Proofs retain every subject digest verified at issuance so key rotation cannot split identity ownership.
alter table identity_link_proofs
    add constraint uq_identity_link_proofs_site_id unique(site_id,id);

create table identity_link_proof_subject_aliases (
 site_id uuid not null,
 proof_id uuid not null,
 subject_digest varchar(512) not null
    constraint ck_identity_link_proof_aliases_digest check(subject_digest ~ '^[A-Za-z0-9_-]+$'),
 subject_digest_key_version varchar(16) not null
    constraint ck_identity_link_proof_aliases_version check(subject_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 constraint pk_identity_link_proof_subject_aliases
    primary key(site_id,proof_id,subject_digest,subject_digest_key_version),
 constraint uq_identity_link_proof_alias_version
    unique(site_id,proof_id,subject_digest_key_version),
 constraint fk_identity_link_proof_aliases_proof
    foreign key(site_id,proof_id)
    references identity_link_proofs(site_id,id) on delete cascade
);

create index idx_identity_link_proof_aliases_proof
    on identity_link_proof_subject_aliases(site_id,proof_id);

insert into identity_link_proof_subject_aliases(
    site_id,proof_id,subject_digest,subject_digest_key_version)
select site_id,id,subject_digest,subject_digest_key_version
from identity_link_proofs;
