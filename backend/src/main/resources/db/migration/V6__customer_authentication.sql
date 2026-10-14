alter table oidc_authentication_attempts
    add column provider_config_version bigint not null default 0
        constraint ck_oidc_attempts_provider_version check(provider_config_version>=0);

