import type { ApiErrorBody } from "@/lib/api/types";

export class ServerDataError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly correlationId?: string,
  ) {
    super(message);
    this.name = "ServerDataError";
  }
}

export async function fetchJson<T>(
  url: URL,
  init: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      ...init,
      cache: "no-store",
      signal: init.signal ?? AbortSignal.timeout(15_000),
    });
  } catch {
    throw new ServerDataError(
      "The service is temporarily unavailable. Please retry.",
      503,
      "SERVICE_UNAVAILABLE",
    );
  }

  if (response.ok) return (await response.json()) as T;

  let body: ApiErrorBody = {};
  try {
    body = (await response.json()) as ApiErrorBody;
  } catch {
    // The stable fallback below is used for non-JSON failures.
  }
  throw new ServerDataError(
    body.error?.message ?? "The request could not be completed. Please retry.",
    response.status,
    body.error?.code ?? "REQUEST_FAILED",
    body.correlation_id ?? response.headers.get("x-correlation-id") ?? undefined,
  );
}
