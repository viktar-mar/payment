create index idx_customer_sessions_digest_lookup
    on customer_sessions(token_digest_key_version, token_digest);
