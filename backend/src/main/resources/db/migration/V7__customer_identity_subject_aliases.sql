-- Durable ownership aliases make one semantic identity discoverable across retained digest-key versions.
alter table customer_identities
    add constraint uq_customer_identities_site_id_issuer unique(site_id,id,issuer_key);

create table customer_identity_subject_aliases (
 site_id uuid not null,
 issuer_key varchar(63) not null
    constraint ck_customer_identity_aliases_issuer check(issuer_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
 subject_digest varchar(512) not null
    constraint ck_customer_identity_aliases_digest check(subject_digest ~ '^[A-Za-z0-9_-]+$'),
 subject_digest_key_version varchar(16) not null
    constraint ck_customer_identity_aliases_version check(subject_digest_key_version ~ '^v[1-9][0-9]{0,8}$'),
 identity_id uuid not null,
 constraint pk_customer_identity_subject_aliases
    primary key(site_id,issuer_key,subject_digest,subject_digest_key_version),
 constraint fk_customer_identity_aliases_identity
    foreign key(site_id,identity_id,issuer_key)
    references customer_identities(site_id,id,issuer_key) on delete cascade
);

create index idx_customer_identity_aliases_identity
    on customer_identity_subject_aliases(site_id,identity_id);

insert into customer_identity_subject_aliases(
    site_id,issuer_key,subject_digest,subject_digest_key_version,identity_id)
select site_id,issuer_key,subject_digest,subject_digest_key_version,id
from customer_identities;
