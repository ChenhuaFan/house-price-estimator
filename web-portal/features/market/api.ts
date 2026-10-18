/** Browser API client for isolated what-if and asynchronous export actions. */
import type { ApiErrorBody, ExportJob, PropertyInput, WhatIfResponse } from "@/lib/api/types";
import type { ExportCreateRequestWire, WhatIfResponseWire } from "@/features/market/model";
import { PortalApiError } from "@/features/estimator/api";

async function parseResponse<T>(response: Response): Promise<T> {
  if (response.ok) return (await response.json()) as T;
  let body: ApiErrorBody = {};
  try {
    body = (await response.json()) as ApiErrorBody;
  } catch {
    // Use the stable fallback below for non-JSON responses.
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

function correlationHeaders(extra: HeadersInit = {}): Headers {
  const headers = new Headers(extra);
  headers.set("X-Correlation-ID", crypto.randomUUID());
  return headers;
}

function whatIfInput(input: PropertyInput): WhatIfResponseWire["inputs"] {
  return {
    square_footage: input.square_footage,
    bedrooms: input.bedrooms,
    bathrooms: input.bathrooms.toFixed(1),
    year_built: input.year_built,
    lot_size: input.lot_size,
    distance_to_city_center: input.distance_to_city_center.toFixed(3),
    school_rating: input.school_rating.toFixed(3),
  };
}

/** Requests a model prediction without persisting estimator history. */
export async function runWhatIf(input: PropertyInput): Promise<WhatIfResponse> {
  const response = await fetch("/api/market/what-if", {
    method: "POST",
    headers: correlationHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(whatIfInput(input)),
  });
  return parseResponse<WhatIfResponse>(response);
}

/** Starts a durable export and returns the server-directed polling interval. */
export async function createExport(
  request: ExportCreateRequestWire,
  idempotencyKey: string,
): Promise<{ job: ExportJob; retryAfterSeconds: number }> {
  const response = await fetch("/api/market/exports", {
    method: "POST",
    headers: correlationHeaders({
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    }),
    body: JSON.stringify(request),
  });
  const job = await parseResponse<ExportJob>(response);
  return { job, retryAfterSeconds: Number(response.headers.get("retry-after")) || 2 };
}

/** Reads one export job and allows the caller to cancel obsolete polling. */
export async function getExport(
  exportId: string,
  signal?: AbortSignal,
): Promise<{ job: ExportJob; retryAfterSeconds: number }> {
  const response = await fetch(`/api/market/exports/${encodeURIComponent(exportId)}`, {
    cache: "no-store",
    headers: correlationHeaders(),
    signal,
  });
  const job = await parseResponse<ExportJob>(response);
  return { job, retryAfterSeconds: Number(response.headers.get("retry-after")) || 2 };
}

/** Cancels an active export job owned by the current browser session. */
export async function cancelExport(exportId: string): Promise<void> {
  const response = await fetch(`/api/market/exports/${encodeURIComponent(exportId)}`, {
    method: "DELETE",
    headers: correlationHeaders(),
  });
  if (!response.ok) await parseResponse<never>(response);
}
