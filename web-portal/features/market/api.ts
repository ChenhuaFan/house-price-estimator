import type { ApiErrorBody, PropertyInput, WhatIfResponse } from "@/lib/api/types";
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
  );
}

export async function runWhatIf(input: PropertyInput): Promise<WhatIfResponse> {
  const response = await fetch("/api/market/what-if", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-ID": crypto.randomUUID() },
    body: JSON.stringify(input),
  });
  return parseResponse<WhatIfResponse>(response);
}

export async function downloadPdf(query: URLSearchParams, whatIf: WhatIfResponse | null): Promise<void> {
  const response = await fetch(`/api/market/exports/report.pdf?${query}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-ID": crypto.randomUUID() },
    body: JSON.stringify(whatIf),
  });
  if (!response.ok) await parseResponse<never>(response);
  const blob = await response.blob();
  triggerDownload(blob, "property-market-analysis.pdf");
}

function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
