-- Additive protocol reservation and immutable server-confirm receipts. Never migrates a bound v1 grant.
create table subscription_confirmation_chains (
 id uuid primary key references subscription_action_capabilities(id),
 operation_id uuid not null unique references subscription_operations(id),
 root_digest varchar(64) not null unique references subscription_action_capabilities(token_digest),
 session_id uuid not null, origin varchar(2048) not null,
 expires_at timestamptz not null, policy_version bigint not null, subscription_revision bigint not null
);
create table subscription_confirmation_attempts (
 id uuid primary key, chain_id uuid not null references subscription_confirmation_chains(id),
 predecessor uuid unique references subscription_confirmation_attempts(id),
 key_version varchar(10) not null, issued_at timestamptz not null, expires_at timestamptz not null,
 state varchar(32) not null check(state in ('READY','DISPATCHING','REQUIRES_ACTION','OBSERVE','DECLINED','UNKNOWN')),
 claimed_at timestamptz, confirm_key varchar(64), token_digest varchar(64), fingerprint varchar(64), provider_key varchar(255) not null unique,
 request_id varchar(255), charge_reference varchar(255), decline_code varchar(64),
 successor uuid unique references subscription_confirmation_attempts(id), retry_key varchar(64), version bigint not null default 0,
 unique(chain_id,confirm_key), unique(chain_id,retry_key),
 check(expires_at>issued_at), check(claimed_at is null or (claimed_at>=issued_at and claimed_at<expires_at)),
 check(key_version ~ '^v[1-9][0-9]{0,8}$'), check(provider_key='billing-confirm-v2:'||id::text),
 check(confirm_key is null or (confirm_key ~ '^[0-9a-f]{64}$' and token_digest ~ '^[0-9a-f]{64}$' and fingerprint ~ '^[0-9a-f]{64}$')),
 check(request_id is null or (request_id ~ '^req_[A-Za-z0-9_]{1,200}$' and charge_reference ~ '^ch_[A-Za-z0-9_]{1,200}$' and decline_code in ('generic_decline','insufficient_funds','do_not_honor'))), check(num_nonnulls(confirm_key,token_digest,fingerprint) in (0,3)),
 check((state='READY')=(confirm_key is null)), check((state='READY')=(claimed_at is null)),
 check((state='DECLINED')=(request_id is not null)),
 check(num_nonnulls(request_id,charge_reference,decline_code) in (0,3)),
 check(num_nonnulls(successor,retry_key) in (0,2)), check(successor is null or state='DECLINED')
);
create unique index uq_confirmation_initial on subscription_confirmation_attempts(chain_id) where predecessor is null;
create function protect_confirmation_chain() returns trigger language plpgsql as $$
declare g subscription_action_capabilities%rowtype;
begin
 if tg_op='UPDATE' then raise exception 'Confirmation chain immutable' using errcode='23514'; end if;
 select * into g from subscription_action_capabilities where id=new.id for update;
 if g.id is null or g.operation_id<>new.operation_id or g.token_digest<>new.root_digest or g.billing_key_version is null or g.billing_schema='setup-v1'
  or g.client_session_id is not null or g.consumed_at is not null or g.superseded_at is not null or not g.recurring_consent
  or new.expires_at>g.expires_at or new.expires_at<=g.issued_at then
  raise exception 'Confirmation protocol unavailable' using errcode='23514'; end if;
 return new;
end; $$;
create trigger confirmation_chain_identity before insert or update on subscription_confirmation_chains for each row execute function protect_confirmation_chain();
create function protect_confirmation_root() returns trigger language plpgsql as $$
begin
 if exists(select 1 from subscription_confirmation_chains where id=old.id) and
 (new.superseded_at is distinct from old.superseded_at or new.client_session_id is distinct from old.client_session_id
 or new.confirmation_key_digest is distinct from old.confirmation_key_digest) then
 raise exception 'Reserved server confirmation root' using errcode='23514'; end if;
 return new;
end; $$;
create trigger confirmation_root_protocol before update on subscription_action_capabilities for each row execute function protect_confirmation_root();
create function protect_confirmation_attempt() returns trigger language plpgsql as $$
declare parent subscription_confirmation_attempts%rowtype;
begin
 if tg_op='INSERT' then
  if not exists(select 1 from subscription_confirmation_chains c join subscription_action_capabilities g on g.id=c.id where c.id=new.chain_id and new.expires_at=c.expires_at and new.key_version=g.billing_key_version and new.issued_at>=g.issued_at) then raise exception 'Invalid confirmation binding' using errcode='23514'; end if;
  if new.predecessor is not null then
   select * into parent from subscription_confirmation_attempts where id=new.predecessor for update;
   if parent.chain_id<>new.chain_id or parent.state<>'DECLINED' or parent.request_id is null or parent.successor is not null or new.expires_at<>parent.expires_at then
    raise exception 'Invalid successor' using errcode='23514'; end if;
  end if;
 else
  if new.successor is not null and old.successor is null and not exists(select 1 from subscription_confirmation_attempts child where child.id=new.successor and child.predecessor=old.id and child.chain_id=old.chain_id and child.expires_at=old.expires_at) then raise exception 'Invalid successor binding' using errcode='23514'; end if;
  if row(new.id,new.chain_id,new.predecessor,new.key_version,new.issued_at,new.expires_at,new.provider_key) is distinct from row(old.id,old.chain_id,old.predecessor,old.key_version,old.issued_at,old.expires_at,old.provider_key)
    or new.version<>old.version+1
    or old.claimed_at is not null and new.claimed_at is distinct from old.claimed_at
    or old.confirm_key is not null and row(new.confirm_key,new.token_digest,new.fingerprint) is distinct from row(old.confirm_key,old.token_digest,old.fingerprint)
    or old.state not in ('READY','DISPATCHING') and row(new.state,new.request_id,new.charge_reference,new.decline_code) is distinct from row(old.state,old.request_id,old.charge_reference,old.decline_code)
    or old.successor is not null and row(new.successor,new.retry_key) is distinct from row(old.successor,old.retry_key)
    or old.state='READY' and new.state<>'DISPATCHING'
    or old.state='DISPATCHING' and new.state not in ('REQUIRES_ACTION','OBSERVE','DECLINED','UNKNOWN')
   then raise exception 'Confirmation receipt immutable' using errcode='23514'; end if;
 end if;
 return new;
end; $$;
create trigger confirmation_attempt_identity before insert or update on subscription_confirmation_attempts for each row execute function protect_confirmation_attempt();
