create table admin_users (
  id uuid primary key,
  username varchar(120) not null unique,
  password_hash varchar(255) not null,
  status varchar(16) not null check (status in ('ACTIVE','DISABLED')),
  created_at timestamptz not null,
  version bigint not null default 0
);

create table admin_sessions (
  id uuid primary key,
  admin_user_id uuid not null references admin_users(id),
  token_hash char(64) not null unique,
  created_at timestamptz not null,
  last_seen_at timestamptz not null,
  expires_at timestamptz not null,
  absolute_expires_at timestamptz not null,
  revoked_at timestamptz
);

create table audit_log (
  id uuid primary key,
  actor_type varchar(24) not null,
  actor_id varchar(255) not null,
  action varchar(64) not null,
  resource_type varchar(64),
  resource_id varchar(255),
  request_id varchar(64) not null,
  source_ip varchar(64),
  details jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null
);

create index idx_audit_log_occurred_at on audit_log(occurred_at desc);
