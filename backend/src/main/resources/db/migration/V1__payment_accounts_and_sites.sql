create table payment_accounts (
  id uuid primary key,
  account_key varchar(63) not null unique,
  provider varchar(16) not null check (provider in ('FAKE','STRIPE')),
  account_mode varchar(16) not null check (account_mode in ('SANDBOX','LIVE')),
  account_status varchar(16) not null check (account_status in ('ACTIVE','DISABLED')),
  external_account_id varchar(255) not null,
  credentials_ref varchar(255) not null,
  created_at timestamptz not null,
  version bigint not null default 0
);

create table sites (
  id uuid primary key,
  site_key varchar(63) not null unique,
  display_name varchar(120) not null,
  payment_account_id uuid not null references payment_accounts(id),
  status varchar(16) not null check (status in ('ACTIVE','DISABLED')),
  payment_method_policy varchar(16) not null check (payment_method_policy in ('AUTOMATIC','ALLOWLIST')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version bigint not null default 0
);

create table site_allowed_origins (
  site_id uuid not null references sites(id) on delete cascade,
  origin varchar(255) not null,
  primary key (site_id, origin)
);

create table site_authentication_modes (
  site_id uuid not null references sites(id) on delete cascade,
  authentication_mode varchar(24) not null,
  primary key (site_id, authentication_mode)
);

create table site_allowed_payment_methods (
  site_id uuid not null references sites(id) on delete cascade,
  payment_method varchar(24) not null,
  primary key (site_id, payment_method)
);
