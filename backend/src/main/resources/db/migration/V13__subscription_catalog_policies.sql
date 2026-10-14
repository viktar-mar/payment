alter table prices add column trial_payment_method_policy varchar(32) not null
  default 'OPTIONAL_DURING_TRIAL'
  check (trial_payment_method_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL'));

-- Once published, retiring an offer must not make its historical contract editable.
create function protect_published_price_terms() returns trigger language plpgsql as $$
begin
  if old.published_at is not null or old.status = 'PUBLISHED' then
    if new.status = 'DRAFT' or
      row(new.id,new.site_id,new.product_id,new.price_key,new.price_version,new.price_type,
          new.amount_minor,new.currency,new.billing_interval,new.trial_days,new.trial_payment_method_policy,
          new.tax_mode,new.tax_code,new.published_at,new.created_at)
      is distinct from
      row(old.id,old.site_id,old.product_id,old.price_key,old.price_version,old.price_type,
          old.amount_minor,old.currency,old.billing_interval,old.trial_days,old.trial_payment_method_policy,
          old.tax_mode,old.tax_code,old.published_at,old.created_at) then
      raise exception 'published price terms are immutable' using errcode = '23514';
    end if;
  end if;
  return new;
end;
$$;

create trigger published_price_terms_immutable before update on prices
  for each row execute function protect_published_price_terms();

alter table products add constraint uq_products_site_id unique (site_id,id);
alter table prices add constraint uq_prices_site_product_id unique (site_id,product_id,id);
alter table prices add constraint fk_prices_site_product
  foreign key (site_id,product_id) references products(site_id,id);

create table site_subscription_policies (
  site_id uuid primary key references sites(id),
  version bigint not null default 0 check (version >= 0),
  enabled boolean not null default false,
  grace_days integer not null default 3 check (grace_days >= 0),
  allow_immediate_cancellation boolean not null default false,
  portal_enabled boolean not null default false,
  defer_upgrades boolean not null default false,
  portal_return_urls text[] not null default '{}'
);

create table subscription_plan_transitions (
  site_id uuid not null references site_subscription_policies(site_id),
  product_id uuid not null,
  from_price_id uuid not null,
  to_price_id uuid not null,
  direction varchar(16) not null check (direction in ('UPGRADE','DOWNGRADE','LATERAL')),
  immediate_allowed boolean not null,
  policy_version bigint not null check (policy_version >= 0),
  primary key (site_id,from_price_id,to_price_id),
  check (from_price_id <> to_price_id),
  foreign key (site_id,product_id) references products(site_id,id),
  foreign key (site_id,product_id,from_price_id) references prices(site_id,product_id,id),
  foreign key (site_id,product_id,to_price_id) references prices(site_id,product_id,id)
);

create index ix_subscription_transitions_product on subscription_plan_transitions(site_id,product_id);
