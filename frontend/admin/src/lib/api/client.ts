export class ApiError extends Error {
  constructor(
    public title: string,
    public status: number,
    public readonly outcome: "definitive" | "ambiguous" = "definitive",
  ) {
    super(title);
  }
}

type Scalar = string | number | boolean | null | undefined;
export type ApiOptions = {
  method?: string; body?: unknown; signal?: AbortSignal;
  query?: Readonly<Record<string, Scalar | readonly Scalar[]>>;
  idempotencyKey?: string;
  headers?: { "X-Request-ID"?: string };
};

export function queryParams(query: NonNullable<ApiOptions["query"]>) {
  const result = new URLSearchParams();
  for (const key of Object.keys(query).sort()) {
    const value = query[key];
    for (const item of Array.isArray(value) ? value : [value]) {
      if (item === null || item === undefined || item === "") continue;
      if (!["string", "number", "boolean"].includes(typeof item) ||
          (typeof item === "number" && !Number.isFinite(item))) throw new Error("Invalid query value");
      result.append(key, String(item));
    }
  }
  return result;
}

export async function api<T = void>(
  path: string,
  options: ApiOptions = {},
): Promise<T> {
  if (!/^[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_-]+)*$/.test(path))
    throw new Error("Invalid API path");
  const method = options.method ?? "GET";
  const headers = new Headers({ Accept: "application/json" });
  if (options.headers) {
    for (const [name, value] of Object.entries(options.headers)) {
      if (name !== "X-Request-ID" || typeof value !== "string" || !/^[A-Za-z0-9._:-]{1,64}$/.test(value))
        throw new Error("Invalid caller headers");
      headers.set(name, value);
    }
  }
  if (options.idempotencyKey !== undefined) {
    if (typeof options.idempotencyKey !== "string" || !options.idempotencyKey.trim() ||
        options.idempotencyKey.length > 255 || /[\x00-\x1f\x7f]/.test(options.idempotencyKey))
      throw new Error("Invalid idempotency key");
    headers.set("Idempotency-Key", options.idempotencyKey);
  }
  const query = options.query ? queryParams(options.query).toString() : "";
  if (options.body !== undefined)
    headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method.toUpperCase())) {
    const cookie = document.cookie
      .split(";")
      .map((value) => value.trim())
      .find((value) => value.startsWith("XSRF-TOKEN="));
    if (cookie) {
      try {
        headers.set("X-XSRF-TOKEN", decodeURIComponent(cookie.slice(11)));
      } catch {
        /* Invalid cookie is handled by backend CSRF validation. */
      }
    }
  }
  let response: Response;
  try {
    response = await fetch(`/api/backend/${path}${query ? `?${query}` : ""}`, {
      method,
      headers,
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError("Unable to connect", 0, "ambiguous");
  }
  if (!response.ok) {
    let title = "Request failed";
    let outcome: ApiError["outcome"] = "definitive";
    try {
      const problem: unknown = await response.json();
      // Only the BFF's exact transport-failure problem is ambiguous, not arbitrary 5xx/code fields.
      if (response.status === 502 && response.headers.get("content-type")?.split(";")[0].trim() === "application/problem+json" &&
          typeof problem === "object" && problem !== null &&
          "type" in problem && problem.type === "urn:payment:problem:backend_unavailable" &&
          "code" in problem && problem.code === "backend_unavailable" &&
          "status" in problem && problem.status === 502 &&
          "title" in problem && problem.title === "Backend unavailable") outcome = "ambiguous";
      if (
        typeof problem === "object" &&
        problem !== null &&
        "title" in problem &&
        typeof problem.title === "string" &&
        problem.title.length <= 160 &&
        !/[\x00-\x1f<>]/.test(problem.title)
      )
        title = problem.title;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      /* Response bodies are deliberately never surfaced. */
    }
    throw new ApiError(title, response.status, outcome);
  }
  if (response.status === 204) return undefined as T;
  try {
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    // Successful headers can follow an accepted mutation even when its projection
    // cannot be consumed/decoded. Keep its key, including on malformed JSON.
    if (error instanceof TypeError) throw new ApiError("Unable to connect", response.status, "ambiguous");
    throw new ApiError("Request failed", response.status, "ambiguous");
  }
}

export function errorTitle(error: unknown) {
  return error instanceof ApiError ? error.title : "Request failed";
}
