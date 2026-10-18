import type { ApiErrorBody, Estimate, HistoryPage, PropertyInput } from "@/lib/api/types";

export class PortalApiError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly correlationId?: string,
    public readonly fields: Array<{ field: string; message: string }> = [],
    public readonly status = 0,
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
  }
}

async function responseBody<T>(response: Response): Promise<T> {
  if (response.ok) return (await response.json()) as T;
  let body: ApiErrorBody = {};
  try {
    body = (await response.json()) as ApiErrorBody;
  } catch {
    // A stable message is used when a dependency does not return JSON.
  }
  throw new PortalApiError(
    body.error?.message ?? "The request could not be completed. Please retry.",
    body.error?.code ?? "REQUEST_FAILED",
    body.correlation_id ?? response.headers.get("x-correlation-id") ?? undefined,
    body.error?.fields ?? [],
    response.status,
    Number(response.headers.get("retry-after")) || undefined,
  );
}

export async function createEstimate(input: PropertyInput, requestId: string): Promise<Estimate> {
  const response = await fetch("/api/estimator/estimates", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-ID": crypto.randomUUID() },
    body: JSON.stringify({ ...input, request_id: requestId }),
  });
  return responseBody<Estimate>(response);
}

export async function loadHistory(cursor?: string | null): Promise<HistoryPage> {
  const query = new URLSearchParams({ limit: "20" });
  if (cursor) query.set("cursor", cursor);
  const response = await fetch(`/api/estimator/estimates?${query}`, { cache: "no-store" });
  return responseBody<HistoryPage>(response);
}

export async function deleteEstimate(estimateId: string): Promise<void> {
  const response = await fetch(`/api/estimator/estimates/${estimateId}`, {
    method: "DELETE",
    headers: { "X-Correlation-ID": crypto.randomUUID() },
  });
  if (!response.ok) await responseBody<never>(response);
}

export async function clearEstimateHistory(): Promise<void> {
  const response = await fetch("/api/estimator/estimates", {
    method: "DELETE",
    headers: { "X-Correlation-ID": crypto.randomUUID() },
  });
  if (!response.ok) await responseBody<never>(response);
}
