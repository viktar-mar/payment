-- Local accepted-read sequence, independent of lifecycle/quote and provider revisions.
alter table subscriptions add column observation_sequence bigint not null default 0
    constraint ck_subscription_observation_sequence check (observation_sequence >= 0);
