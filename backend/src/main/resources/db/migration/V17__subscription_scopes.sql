-- Widen allowlists only. Existing authenticated sessions and Partner keys keep their scopes.
alter table customer_sessions drop constraint ck_customer_sessions_scopes;
alter table customer_sessions add constraint ck_customer_sessions_scopes check (
 jsonb_typeof(scopes)='array' and jsonb_array_length(scopes)>0
 and scopes <@ '["CUSTOMER_READ","CHECKOUT_CREATE","IDENTITY_LINK","SUBSCRIPTION_READ","SUBSCRIPTION_MANAGE"]'::jsonb);
alter table site_api_keys drop constraint ck_api_keys_scopes;
alter table site_api_keys add constraint ck_api_keys_scopes check (
 jsonb_typeof(scopes)='array' and jsonb_array_length(scopes)>0
 and scopes <@ '["payments:create","payments:read","customers:read","customers:manage","donations:read","subscriptions:read","subscriptions:manage"]'::jsonb);
