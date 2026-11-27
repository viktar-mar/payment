function matchesScope(fields: URLSearchParams, key: string, secret: string): boolean {
  for (const name of fields.keys()) {
    if (/(?:^|\[)(?:key|client_secret|stripe_account|stripe_context)(?:\]|\[|$)/i.test(name) &&
        name !== 'key' && name !== 'client_secret') return false;
  }
  return ([['key', key], ['client_secret', secret]] as const).every(([name, expected]) => {
    const values = fields.getAll(name);
    return values.length === 0 || values.length === 1 && values[0] === expected;
  });
}

export function contractProviderRequestAllowed(
  url: URL,
  method: string,
  headers: Record<string, string>,
  owner: { key: string; secret: string },
  body?: string | null,
): boolean {
  // The caller admits the origin and owns the checkout. This layer bounds
  // browser authority without modeling the issuer's request sequence.
  if (headers.cookie !== undefined || headers['proxy-authorization'] !== undefined ||
      headers['stripe-account'] !== undefined || headers['stripe-context'] !== undefined) return false;
  const preflight = method === 'OPTIONS';
  const intended = preflight ? headers['access-control-request-method'] : method;
  if (intended !== 'GET' && intended !== 'POST') return false;

  const authorization = headers.authorization;
  if (url.origin !== 'https://api.stripe.com') return authorization === undefined;
  const intent = /^(pi_[A-Za-z0-9]+)_secret_[A-Za-z0-9_-]+$/.exec(owner.secret)?.[1];
  if (!intent || !/^pk_test_[A-Za-z0-9_]+$/.test(owner.key)) return false;
  if (authorization !== undefined && (preflight || authorization !== 'Bearer ' + owner.key)) return false;
  const params = url.searchParams;
  if (!matchesScope(params, owner.key, owner.secret)) return false;
  let form: URLSearchParams | undefined;
  if (method === 'POST') {
    if (!body || body.length > 65_536 || /%(?![a-f0-9]{2})/i.test(body) ||
        !/^application\/x-www-form-urlencoded(?:\s*;\s*charset=utf-8)?$/i.test(headers['content-type'] ?? '')) return false;
    form = new URLSearchParams(body);
    if (!matchesScope(form, owner.key, owner.secret)) return false;
  }
  if (!preflight && authorization === undefined && !params.has('key') && !form?.has('key')) return false;
  if (url.pathname === '/v1/payment_intents/' + intent) return intended === 'GET' && params.has('client_secret');
  // Stripe.js can change the steps within its authentication namespace. Other
  // API resources, including confirmation, capture, and refunds, remain closed.
  return /^\/v1\/3ds2\/[a-z0-9_-]+(?:\/[a-z0-9_-]+)*$/i.test(url.pathname);
}
