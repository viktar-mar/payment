do $$
begin
    if exists (select 1 from email_otp_challenges) then
        raise exception using
            errcode = '23514',
            constraint = 'ck_email_otp_provider_migration_precondition',
            message = 'existing email OTP challenges require explicit issuer migration';
    end if;
end $$;

alter table email_otp_challenges
    add column provider_key varchar(63) not null;

alter table email_otp_challenges
    add constraint ck_email_otp_challenges_provider_key
        check (provider_key ~ '^[a-z0-9][a-z0-9-]{2,62}$'),
    add constraint fk_email_otp_challenges_provider_site
        foreign key (site_id, provider_key)
        references identity_provider_configurations (site_id, provider_key);

create index idx_email_otp_challenges_site_provider_expiry
    on email_otp_challenges (site_id, provider_key, expires_at);
