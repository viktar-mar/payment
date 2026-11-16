// Private shared origin predicates. Contextual authorization remains with each caller.
export function providerOrigin(url:URL) {
  return url.protocol==='https:' && !url.port && !url.username && !url.password &&
    (['api.stripe.com','hooks.stripe.com','js.stripe.com'].includes(url.hostname) || url.hostname.endsWith('.js.stripe.com'));
}
export function providerNetworkOrigin(url:URL):boolean {
  return url.protocol==='https:' && !url.port && !url.username && !url.password &&
    (url.hostname==='merchant-ui-api.stripe.com' || url.hostname==='r.stripe.com' || providerOrigin(url));
}
