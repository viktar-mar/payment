create table products (
  id uuid primary key,
  site_id uuid not null references sites(id),
  product_key varchar(63) not null,
  name varchar(120) not null,
  description varchar(1000),
  status varchar(16) not null check (status in ('ACTIVE','ARCHIVED')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version bigint not null default 0,
  unique (site_id, product_key)
);

create table prices (
  id uuid primary key,
  site_id uuid not null references sites(id),
  product_id uuid not null references products(id),
  price_key varchar(63) not null,
  price_version integer not null check (price_version > 0),
  price_type varchar(16) not null check (price_type in ('ONE_TIME','SUBSCRIPTION')),
  amount_minor bigint not null check (amount_minor > 0),
  currency char(3) not null,
  billing_interval varchar(16),
  trial_days integer not null default 0 check (trial_days >= 0),
  tax_mode varchar(16) not null,
  tax_code varchar(64),
  status varchar(16) not null check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
  published_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version bigint not null default 0,
  unique (site_id, price_key, price_version)
);

create unique index uq_prices_one_published
  on prices(site_id, price_key) where status = 'PUBLISHED';

create table price_version_counters (
  site_id uuid not null references sites(id),
  price_key varchar(63) not null,
  last_version integer not null check (last_version > 0),
  primary key (site_id, price_key)
);

create table provider_product_mappings (
  payment_account_id uuid not null references payment_accounts(id),
  product_id uuid not null references products(id),
  provider_product_id varchar(255) not null,
  created_at timestamptz not null,
  primary key (payment_account_id, product_id),
  unique (payment_account_id, provider_product_id)
);

create table provider_price_mappings (
  payment_account_id uuid not null references payment_accounts(id),
  price_id uuid not null references prices(id),
  provider_price_id varchar(255) not null,
  created_at timestamptz not null,
  primary key (payment_account_id, price_id),
  unique (payment_account_id, provider_price_id)
);
