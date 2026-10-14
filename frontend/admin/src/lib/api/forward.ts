import { NextRequest, NextResponse } from "next/server";

const requestHeaders = [
  "accept",
  "content-type",
  "cookie",
  "x-xsrf-token",
  "x-request-id",
  "origin",
  "idempotency-key",
];
const responseHeaders = [
  "content-type",
  "content-disposition",
  "location",
  "x-request-id",
  "www-authenticate",
  "retry-after",
];

function problem(
  request: NextRequest,
  status: number,
  code: string,
  title: string,
) {
  const incoming = request.headers.get("x-request-id");
  const requestId =
    incoming && /^[A-Za-z0-9._:-]{1,64}$/.test(incoming)
      ? incoming
      : crypto.randomUUID();
  return NextResponse.json(
    {
      type: `urn:payment:problem:${code}`,
      title,
      status,
      code,
      request_id: requestId,
    },
    {
      status,
      headers: {
        "content-type": "application/problem+json",
        "cache-control": "no-store",
        "x-request-id": requestId,
      },
    },
  );
}

// This browser-facing proxy cannot be used to reach customer/partner credential surfaces.
const routes: readonly [RegExp, readonly string[], boolean, number][] = [
  [/^admin\/v1\/auth\/sessions$/, ["POST"], false, 32768], [/^admin\/v1\/auth\/session$/, ["GET", "DELETE"], false, 32768],
  [/^admin\/v1\/(sites|payment-accounts)$/, ["GET", "POST"], false, 131072],
  [/^admin\/v1\/sites\/[^/]+\/catalog$/, ["GET"], false, 131072],
  [/^admin\/v1\/sites\/[^/]+\/products$/, ["GET", "POST"], false, 131072],
  [/^admin\/v1\/products\/[^/]+\/prices$/, ["POST"], false, 32768], [/^admin\/v1\/prices\/[^/]+\/publish$/, ["POST"], false, 32768],
  [/^v1\/admin\/subscriptions$/, ["GET", "POST"], true, 131072],
  [/^v1\/admin\/subscriptions\/[^/]+$/, ["GET"], true, 32768],
  [/^v1\/admin\/subscriptions\/[^/]+\/(events|billing-records)$/, ["GET"], true, 131072],
  [/^v1\/admin\/subscriptions\/[^/]+\/operations\/[^/]+(\/action)?$/, ["GET"], true, 32768],
  [/^v1\/admin\/subscriptions\/[^/]+\/(changes(\/clear)?|cancellation(\/withdraw)?|change-preview|method-setups|recoveries|resumptions|portal-sessions|reconcile|fake-controls|operations\/[^/]+\/action-confirmations)$/, ["POST"], true, 32768],
  [/^v1\/admin\/sites\/[^/]+\/subscription-policy$/, ["GET", "POST"], true, 32768],
  [/^v1\/admin\/sites\/[^/]+\/products\/[^/]+\/subscription-transitions$/, ["GET", "POST"], true, 131072],
  [/^v1\/admin\/sites\/[^/]+\/customers\/[^/]+\/products\/[^/]+\/subscription-offers$/, ["GET"], true, 131072],
  [/^v1\/admin\/payments(\/[^/]+)?$/, ["GET"], false, 131072],
  [/^v1\/admin\/payments\/[^/]+\/(refunds|reconcile|fake-events)$/, ["POST"], false, 131072],
  [/^v1\/admin\/outbox\/[^/]+\/retry$/, ["POST"], false, 32768],
  [/^v1\/admin\/sites\/[^/]+\/customers(\/[^/]+)?$/, ["GET"], false, 131072],
  [/^v1\/admin\/sites\/[^/]+\/customers\/[^/]+\/anonymization$/, ["POST"], false, 32768],
  [/^v1\/admin\/sites\/[^/]+\/api-keys(\/[^/]+\/(rotate|revoke))?$/, ["POST"], false, 32768],
  [/^v1\/admin\/sites\/[^/]+\/callback$/, ["PUT"], false, 32768],
  [/^v1\/admin\/sites\/[^/]+\/callback\/rotate-secret$/, ["POST"], false, 32768],
  [/^v1\/admin\/sites\/[^/]+\/identity-providers$/, ["GET", "POST"], false, 131072],
  [/^v1\/admin\/sites\/[^/]+\/identity-providers\/[^/]+$/, ["GET", "PUT"], false, 131072],
  [/^v1\/admin\/sites\/[^/]+\/identity-providers\/[^/]+\/status$/, ["POST"], false, 32768],
  [/^v1\/admin\/sites\/[^/]+\/donation-campaigns$/, ["GET", "POST"], false, 131072],
  [/^v1\/admin\/sites\/[^/]+\/donation-campaigns\/[^/]+$/, ["GET", "PUT"], false, 131072],
  [/^v1\/admin\/sites\/[^/]+\/donation-campaigns\/[^/]+\/status$/, ["POST"], false, 32768],
  [/^v1\/admin\/donations(\/[^/]+)?$/, ["GET"], false, 131072],
  [/^v1\/admin\/donations\/[^/]+\/message-redactions$/, ["POST"], false, 32768],
  [/^v1\/admin\/local\/customer-issuer\/public-keys$/, ["GET"], false, 32768],
  [/^v1\/admin\/local\/customer-issuer\/tokens$/, ["POST"], false, 32768],
];
async function bounded(stream: ReadableStream<Uint8Array> | null, limit: number) {
  if (!stream) return new Uint8Array();
  const reader = stream.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  try { while (true) { const chunk = await reader.read(); if (chunk.done) break; size += chunk.value.length; if (size > limit) { await reader.cancel(); throw new RangeError("Body limit"); } chunks.push(chunk.value); } }
  finally { reader.releaseLock(); }
  const result = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; } return result;
}

export async function forward(request: NextRequest, segments: string[]) {
  if (
    !segments.length ||
    segments.some((segment) => !/^[a-zA-Z0-9_-]+$/.test(segment))
  ) {
    return problem(request, 400, "invalid_api_path", "Invalid API path");
  }
  const path = segments.join("/"); const route = routes.find(([pattern]) => pattern.test(path));
  if (!route) return problem(request, 404, "api_route_not_found", "API route not found");
  if (!route[1].includes(request.method)) return problem(request, 405, "api_method_not_allowed", "API method not allowed");
  const subscription = route[2];
  if (subscription) {
    const query = request.nextUrl.searchParams; const allowed = path === "v1/admin/subscriptions" && request.method === "GET" ? ["site_id", "customer_id", "product_id", "state", "limit", "cursor"] : /\/(events|billing-records)$/.test(path) ? ["site_id", "limit", "cursor"] : path.startsWith("v1/admin/subscriptions/") ? ["site_id"] : [];
    for (const [key, value] of query) if (!allowed.includes(key) || query.getAll(key).length !== 1 || value.length > (key === "cursor" ? 4096 : 64)) return problem(request, 400, "invalid_api_query", "Invalid API query");
    if (allowed.includes("site_id") && !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(query.get("site_id") ?? "")) return problem(request, 400, "invalid_api_query", "Invalid API query");
    if (request.method === "POST" && (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json") || !/^[A-Za-z0-9_.:/-]{1,255}$/.test(request.headers.get("idempotency-key") ?? "") && !path.endsWith("/change-preview"))) return problem(request, 400, "invalid_api_request", "Invalid API request");
  }
  let body: ArrayBuffer | undefined;
  try { if (!["GET", "HEAD"].includes(request.method)) { const bytes = await bounded(request.body, subscription ? 8192 : 1048576); body = bytes.buffer as ArrayBuffer; } }
  catch { return problem(request, 413, "api_body_too_large", "API body too large"); }
  try {
    const target = new URL(
      `/api/${segments.join("/")}`,
      process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080",
    );
    target.search = request.nextUrl.search;
    const headers = new Headers();
    for (const name of requestHeaders) {
      const value = request.headers.get(name);
      if (value) headers.set(name, value);
    }
    const response = await fetch(target, {
      method: request.method,
      headers,
      body,
      redirect: "manual",
      cache: "no-store",
    });
    const bytes = await bounded(response.body, route[3]);
    if (subscription && !response.ok) {
      const outgoing = problem(request, response.status, "subscription_request_failed", response.status === 409 ? "Subscription conflict; refresh before retrying" : "Subscription request failed");
      for (const cookie of response.headers.getSetCookie()) outgoing.headers.append("set-cookie", cookie);
      return outgoing;
    }
    const outgoing = new NextResponse(response.status === 204 || response.status === 304 ? null : bytes, {
      status: response.status,
    });
    for (const name of responseHeaders) {
      if (subscription && ["location", "content-disposition", "www-authenticate"].includes(name)) continue;
      const value = response.headers.get(name);
      if (value) outgoing.headers.set(name, value);
    }
    for (const cookie of response.headers.getSetCookie())
      outgoing.headers.append("set-cookie", cookie);
    outgoing.headers.set("cache-control", "no-store");
    return outgoing;
  } catch {
    return problem(request, 502, "backend_unavailable", "Backend unavailable");
  }
}
