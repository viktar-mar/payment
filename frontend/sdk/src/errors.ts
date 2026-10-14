const messages = {
  INVALID_SESSION: "Customer session unavailable", SESSION_EXPIRED: "Customer session expired",
  AUTHENTICATION_FAILED: "Authentication unavailable", RATE_LIMIT_EXCEEDED: "Please try again later",
  INVALID_CONFIGURATION: "Invalid payment configuration", INVALID_RESPONSE: "Invalid payment response",
  UNSUPPORTED_PROVIDER: "Payment provider unavailable", TRANSPORT_UNAVAILABLE: "Payment service unavailable",
  REQUEST_FAILED: "Payment request failed", SITE_NOT_AVAILABLE: "Site unavailable", ORIGIN_NOT_ALLOWED: "Origin not allowed",
  PRICE_NOT_AVAILABLE: "Price unavailable", PAYMENT_CAPABILITY_UNAVAILABLE: "Payment capability unavailable",
  CHECKOUT_TOKEN_INVALID: "Checkout unavailable", CHECKOUT_EXPIRED: "Checkout expired",
  IDEMPOTENCY_CONFLICT: "Payment request conflict", PAYMENT_STATE_CONFLICT: "Payment state conflict",
  PROVIDER_DECLINED: "Payment declined", PROVIDER_TEMPORARILY_UNAVAILABLE: "Payment service unavailable",
  PROVIDER_RESULT_UNKNOWN: "Payment result unknown",
} as const;
export type PaymentErrorCode = keyof typeof messages;
export class PaymentSdkError extends Error {
  readonly code: PaymentErrorCode;
  constructor(code: PaymentErrorCode) { super(messages[code]); this.name = "PaymentSdkError"; this.code = code; }
}
export function problemError(body: unknown): PaymentSdkError {
  const code = body && typeof body === "object" && "code" in body ? body.code : undefined;
  return new PaymentSdkError(typeof code === "string" && code.length <= 64 && Object.hasOwn(messages, code) ? code as PaymentErrorCode : "REQUEST_FAILED");
}
export function isAbort(error: unknown): boolean { return !!error && typeof error === "object" && "name" in error && error.name === "AbortError"; }
export function checkAbort(signal?: AbortSignal): void { if (signal?.aborted) throw signal.reason ?? new DOMException("Aborted", "AbortError"); }
