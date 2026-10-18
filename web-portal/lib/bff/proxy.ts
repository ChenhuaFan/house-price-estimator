import { NextRequest, NextResponse } from "next/server";
import type { ApiErrorBody } from "@/lib/api/types";

const MAX_JSON_BYTES = 64 * 1024;
const MAX_QUERY_VALUE_LENGTH = 256;
const CLIENT_COOKIE = "hsbc_estimator_client";

interface ClientIdentity {
  id: string;
  shouldSetCookie: boolean;
}

function validUuid(value: string | null, requireVersionFour = false): value is string {
  if (!value) return false;
  const match = /^([0-9a-f]{8})-([0-9a-f]{4})-([1-5][0-9a-f]{3})-([89ab][0-9a-f]{3})-([0-9a-f]{12})$/i.exec(value);
  return Boolean(match && (!requireVersionFour || match[3]?.startsWith("4")));
}

function clientIdentity(request: NextRequest): ClientIdentity {
  const existing = request.cookies.get(CLIENT_COOKIE)?.value ?? null;
  if (validUuid(existing, true)) return { id: existing.toLowerCase(), shouldSetCookie: false };
  return { id: crypto.randomUUID(), shouldSetCookie: true };
}

function correlationId(request: NextRequest): string | null {
  const supplied = request.headers.get("x-correlation-id");
  if (!supplied) return crypto.randomUUID();
  return validUuid(supplied) ? supplied.toLowerCase() : null;
}

export function errorResponse(
  status: number,
  code: string,
  message: string,
  correlation: string = crypto.randomUUID(),
): NextResponse {
  return NextResponse.json(
    { error: { code, message }, correlation_id: correlation } satisfies ApiErrorBody,
    { status, headers: { "X-Correlation-ID": correlation } },
  );
}

export function requireSameOrigin(request: NextRequest): NextResponse | null {
  const publicOrigin = process.env.PORTAL_PUBLIC_ORIGIN ?? request.nextUrl.origin;
  const allowedOrigins = new Set([new URL(publicOrigin).origin, request.nextUrl.origin]);
  const origin = request.headers.get("origin");
  const fetchSite = request.headers.get("sec-fetch-site");
  if ((origin && allowedOrigins.has(origin)) || fetchSite === "same-origin") return null;
  return errorResponse(403, "CSRF_REJECTED", "The request did not originate from this portal.");
}

function applyClientCookie(response: NextResponse, identity: ClientIdentity): NextResponse {
  if (!identity.shouldSetCookie) return response;
  const publicOrigin = process.env.PORTAL_PUBLIC_ORIGIN ?? "http://localhost:3000";
  response.cookies.set({
    name: CLIENT_COOKIE,
    value: identity.id,
    httpOnly: true,
    sameSite: "lax",
    secure: new URL(publicOrigin).protocol === "https:",
    path: "/",
    maxAge: 31_536_000,
    expires: new Date(Date.now() + 31_536_000_000),
  });
  return response;
}

export async function proxyRequest(
  request: NextRequest,
  target: URL,
  options: { includeClient?: boolean; jsonBody?: boolean } = {},
): Promise<NextResponse> {
  const correlation = correlationId(request);
  if (!correlation) {
    return errorResponse(
      400,
      "INVALID_CORRELATION_ID",
      "X-Correlation-ID must be a valid UUID.",
    );
  }
  const identity = clientIdentity(request);
  const outgoingHeaders = new Headers({ "X-Correlation-ID": correlation });
  if (options.includeClient) outgoingHeaders.set("X-Estimator-Client-ID", identity.id);

  let body: string | undefined;
  if (options.jsonBody) {
    const contentLength = Number(request.headers.get("content-length") ?? "0");
    if (contentLength > MAX_JSON_BYTES) {
      return errorResponse(413, "REQUEST_TOO_LARGE", "The request body is too large.", correlation);
    }
    body = await request.text();
    if (new TextEncoder().encode(body).byteLength > MAX_JSON_BYTES) {
      return errorResponse(413, "REQUEST_TOO_LARGE", "The request body is too large.", correlation);
    }
    outgoingHeaders.set("Content-Type", "application/json");
  }

  let downstream: Response;
  try {
    downstream = await fetch(target, {
      method: request.method,
      headers: outgoingHeaders,
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    return applyClientCookie(
      errorResponse(
        503,
        "SERVICE_UNAVAILABLE",
        "The service is temporarily unavailable. Please retry.",
        correlation,
      ),
      identity,
    );
  }

  const responseHeaders = new Headers();
  const contentType = downstream.headers.get("content-type");
  const disposition = downstream.headers.get("content-disposition");
  if (contentType) responseHeaders.set("Content-Type", contentType);
  if (disposition) responseHeaders.set("Content-Disposition", disposition);
  responseHeaders.set("X-Correlation-ID", downstream.headers.get("x-correlation-id") ?? correlation);
  const response = new NextResponse(downstream.status === 204 ? null : downstream.body, {
    status: downstream.status,
    headers: responseHeaders,
  });
  return applyClientCookie(response, identity);
}

export function serviceUrl(service: "estimator" | "market", path: string): URL {
  const base = service === "estimator"
    ? process.env.ESTIMATOR_SERVICE_URL ?? "http://localhost:8001"
    : process.env.MARKET_SERVICE_URL ?? "http://localhost:8080";
  return new URL(path, base);
}

export function copyAllowedQuery(source: URLSearchParams, target: URL): URL {
  for (const [key, value] of source.entries()) {
    if (/^[A-Za-z][A-Za-z0-9]*$/.test(key) && value.length <= MAX_QUERY_VALUE_LENGTH) {
      target.searchParams.append(key, value);
    }
  }
  return target;
}
