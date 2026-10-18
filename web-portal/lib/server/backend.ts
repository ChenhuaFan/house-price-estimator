import "server-only";

import { cookies, headers } from "next/headers";
import type { HistoryPage, MarketAnalysis } from "@/lib/api/types";

const ESTIMATOR_SERVICE_URL = process.env.ESTIMATOR_SERVICE_URL ?? "http://localhost:8001";
const MARKET_SERVICE_URL = process.env.MARKET_SERVICE_URL ?? "http://localhost:8080";

export class BackendError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly correlationId?: string,
  ) {
    super(message);
  }
}

async function readJson<T>(url: URL, requestHeaders: Headers): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      headers: requestHeaders,
      cache: "no-store",
      signal: AbortSignal.timeout(5_000),
    });
  } catch {
    throw new BackendError("A required service is temporarily unavailable.", 503);
  }
  if (!response.ok) {
    let message = "The requested information could not be loaded.";
    try {
      const body = (await response.json()) as { error?: { message?: string } };
      message = body.error?.message ?? message;
    } catch {
      // The stable portal message is intentionally used for non-JSON dependency errors.
    }
    throw new BackendError(message, response.status, response.headers.get("x-correlation-id") ?? undefined);
  }
  return (await response.json()) as T;
}

async function currentClientId(): Promise<string | null> {
  const cookieStore = await cookies();
  const fromCookie = cookieStore.get("hsbc_estimator_client")?.value;
  if (fromCookie) return fromCookie;
  return (await headers()).get("x-estimator-client-id");
}

export async function getInitialHistory(): Promise<HistoryPage> {
  const clientId = await currentClientId();
  if (!clientId) return { items: [], next_cursor: null };
  const requestHeaders = new Headers({
    "X-Estimator-Client-ID": clientId,
    "X-Correlation-ID": crypto.randomUUID(),
  });
  return readJson<HistoryPage>(new URL("/api/v1/estimates?limit=20", ESTIMATOR_SERVICE_URL), requestHeaders);
}

const MARKET_QUERY_FIELDS = new Set([
  "priceMin", "priceMax", "squareFootageMin", "squareFootageMax",
  "bedroomsMin", "bedroomsMax", "bathroomsMin", "bathroomsMax",
  "yearBuiltMin", "yearBuiltMax", "lotSizeMin", "lotSizeMax",
  "distanceMin", "distanceMax", "schoolRatingMin", "schoolRatingMax",
  "sort", "direction", "page", "size", "datasetVersionId",
]);

export function marketQueryString(
  values: Record<string, string | string[] | undefined>,
): URLSearchParams {
  const query = new URLSearchParams();
  for (const [key, rawValue] of Object.entries(values)) {
    if (!MARKET_QUERY_FIELDS.has(key)) continue;
    const value = Array.isArray(rawValue) ? rawValue[0] : rawValue;
    if (value !== undefined && value !== "") query.set(key, value);
  }
  return query;
}

export async function getInitialMarketAnalysis(
  values: Record<string, string | string[] | undefined>,
): Promise<MarketAnalysis> {
  const url = new URL("/api/v1/market/analysis", MARKET_SERVICE_URL);
  url.search = marketQueryString(values).toString();
  return readJson<MarketAnalysis>(url, new Headers({ "X-Correlation-ID": crypto.randomUUID() }));
}
