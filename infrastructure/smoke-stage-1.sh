#!/usr/bin/env bash
set -euo pipefail

api_base=${PAYMENT_API_BASE_URL:-http://localhost:8080}
cookie_jar=$(mktemp)
trap 'rm -f "$cookie_jar"' EXIT

curl --fail --silent --show-error --max-time 10 "$api_base/actuator/health/readiness" |
  jq -e '.status == "UP"' >/dev/null
: "${PAYMENT_ADMIN_BOOTSTRAP_USERNAME:?set PAYMENT_ADMIN_BOOTSTRAP_USERNAME}"
: "${PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:?set PAYMENT_ADMIN_BOOTSTRAP_PASSWORD}"
jq -n --arg username "$PAYMENT_ADMIN_BOOTSTRAP_USERNAME" --arg password "$PAYMENT_ADMIN_BOOTSTRAP_PASSWORD" \
  '{username: $username, password: $password}' |
  curl --fail --silent --show-error --max-time 20 --cookie-jar "$cookie_jar" \
    -H 'Content-Type: application/json' --data-binary @- "$api_base/api/admin/v1/auth/sessions" >/dev/null
xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$cookie_jar" | tail -1)
test -n "$xsrf"

request() {
  local method=$1 path=$2
  shift 2
  curl --fail --silent --show-error --max-time 30 --cookie "$cookie_jar" \
    --cookie-jar "$cookie_jar" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Content-Type: application/json' -X "$method" "$api_base/api/admin/v1/$path" "$@"
}

request GET sites | jq -e 'type == "array"' >/dev/null
suffix="$(date -u +%Y%m%d%H%M%S)-$$-$RANDOM"
account=$(jq -n --arg key "smoke-account-$suffix" \
  '{key:$key,provider:"FAKE",mode:"SANDBOX",external_account_id:$key,credentials_ref:"local-fake-no-secret"}' |
  request POST payment-accounts --data-binary @-)
account_id=$(jq -er '.id' <<<"$account")
site=$(jq -n --arg key "smoke-site-$suffix" --arg account "$account_id" \
  '{key:$key,display_name:"Smoke Site",payment_account_id:$account,allowed_origins:["http://localhost:3000"],authentication_modes:["SITE_TOKEN"],payment_method_policy:{mode:"AUTOMATIC",methods:[]}}' |
  request POST sites --data-binary @-)
site_id=$(jq -er '.id' <<<"$site")
product=$(jq -n --arg key "smoke-product-$suffix" '{key:$key,name:"Smoke monthly plan",description:"Repeatable Stage 1 verification"}' |
  request POST "sites/$site_id/products" --data-binary @-)
product_id=$(jq -er '.id' <<<"$product")
price=$(jq -n --arg key "smoke-price-$suffix" \
  '{price_key:$key,type:"SUBSCRIPTION",amount_minor:1999,currency:"EUR",billing_interval:"MONTH",trial_days:14,tax_mode:"INCLUSIVE"}' |
  request POST "products/$product_id/prices" --data-binary @-)
price_id=$(jq -er '.id' <<<"$price")
published=$(request POST "prices/$price_id/publish")
jq -e '.status == "PUBLISHED" and (.provider_price_id | startswith("fake_price_")) and .amount_minor == 1999 and .version == 1' <<<"$published" >/dev/null
request GET "sites/$site_id/catalog" |
  jq -e --arg id "$price_id" 'any(.prices[]; .id == $id and .status == "PUBLISHED" and (.provider_price_id | startswith("fake_price_")))' >/dev/null
jq -n '{result:"PASS",health:true,authentication:true,published:true,catalog:true}'
