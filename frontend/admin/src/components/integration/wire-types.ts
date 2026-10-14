import type { components } from "../../lib/api/schema";
// Task 6/8 OpenAPI uses camel case despite the accepted snake-case HTTP serialization.
// The shared CreateRequest name also collides. Keep values derived from generated reveal schemas.
type Key = components["schemas"]["KeyRevealResponse"];
type Callback = components["schemas"]["RevealResponse"];
export type KeyReveal = {
  key_id?: Key["keyId"]; site_id?: Key["siteId"]; scopes?: Key["scopes"];
  status?: Key["status"]; created_at?: Key["createdAt"]; expires_at?: Key["expiresAt"]; api_key?: Key["apiKey"];
};
export type KeyMetadata = Omit<KeyReveal, "api_key">;
export type KeyCreate = { scopes: NonNullable<Key["scopes"]>; expires_at?: Key["expiresAt"] };
export type CallbackReveal = {
  site_id?: Callback["siteId"]; endpoint_url?: Callback["endpointUrl"]; enabled?: Callback["enabled"];
  secret_version?: Callback["secretVersion"]; secret?: Callback["secret"];
};
export type CallbackMetadata = Omit<CallbackReveal, "secret">;
export type CallbackRequest = {
  endpoint_url: components["schemas"]["ConfigureRequest"]["endpointUrl"];
  enabled: components["schemas"]["ConfigureRequest"]["enabled"];
};
