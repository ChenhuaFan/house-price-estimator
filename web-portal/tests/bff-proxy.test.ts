// @vitest-environment node

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import {
  copyAllowedQuery,
  errorResponse,
  proxyRequest,
  requireSameOrigin,
  serviceUrl,
} from "@/lib/bff/proxy";

const correlation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const clientId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

function request(
  path: string,
  init: ConstructorParameters<typeof NextRequest>[1] = {},
): NextRequest {
  return new NextRequest(`http://portal.test${path}`, init);
}

describe("BFF proxy security and transport contract", () => {
  beforeEach(() => {
    process.env.PORTAL_PUBLIC_ORIGIN = "http://portal.test";
    process.env.ESTIMATOR_SERVICE_URL = "http://estimator:8000";
    process.env.MARKET_SERVICE_URL = "http://market:8080";
    vi.spyOn(crypto, "randomUUID").mockReturnValue(correlation);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    delete process.env.PORTAL_PUBLIC_ORIGIN;
    delete process.env.ESTIMATOR_SERVICE_URL;
    delete process.env.MARKET_SERVICE_URL;
  });

  it("accepts the configured origin or an explicit same-origin browser request", () => {
    expect(requireSameOrigin(request("/api", { headers: { origin: "http://portal.test" } }))).toBeNull();
    expect(requireSameOrigin(request("/api", { headers: { "sec-fetch-site": "same-origin" } }))).toBeNull();
    process.env.PORTAL_PUBLIC_ORIGIN = "http://localhost:3000";
    expect(requireSameOrigin(request("/api", { headers: { origin: "http://portal.test" } }))).toBeNull();
    expect(requireSameOrigin(request("/api", {
      headers: { origin: "http://127.0.0.1:3000", "sec-fetch-site": "same-origin" },
    }))).toBeNull();
  });

  it("rejects missing or cross-site origin evidence", async () => {
    const cases: HeadersInit[] = [
      {},
      { origin: "https://evil.test", "sec-fetch-site": "cross-site" },
    ];
    for (const headers of cases) {
      const response = requireSameOrigin(request("/api", { headers }));
      expect(response?.status).toBe(403);
      expect(await response?.json()).toMatchObject({ error: { code: "CSRF_REJECTED" } });
    }
  });

  it("forwards only canonical headers, body, identity, and downstream media metadata", async () => {
    const downstream = new Response(JSON.stringify({ ok: true }), {
      status: 201,
      headers: {
        "content-type": "application/json",
        "content-disposition": "attachment; filename=result.json",
        "x-correlation-id": correlation,
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(downstream);
    vi.stubGlobal("fetch", fetchMock);
    const source = request("/api", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-correlation-id": correlation.toUpperCase(),
        cookie: `hsbc_estimator_client=${clientId.toUpperCase()}`,
        authorization: "must-not-be-forwarded",
      },
      body: JSON.stringify({ value: 1 }),
    });

    const response = await proxyRequest(source, new URL("http://service.test/value"), {
      includeClient: true,
      jsonBody: true,
    });

    expect(response.status).toBe(201);
    expect(await response.json()).toEqual({ ok: true });
    expect(response.headers.get("content-disposition")).toContain("result.json");
    expect(response.cookies.get("hsbc_estimator_client")).toBeUndefined();
    const [target, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(target.toString()).toBe("http://service.test/value");
    const headers = init.headers as Headers;
    expect(headers.get("x-correlation-id")).toBe(correlation);
    expect(headers.get("x-estimator-client-id")).toBe(clientId);
    expect(headers.get("authorization")).toBeNull();
    expect(init.body).toBe('{"value":1}');
    expect(init.cache).toBe("no-store");
  });

  it("rejects malformed correlation IDs before calling a service", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await proxyRequest(request("/api", {
      headers: { "x-correlation-id": "not-a-uuid" },
    }), new URL("http://service.test"));

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ error: { code: "INVALID_CORRELATION_ID" } });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["declared", { "content-length": String(65 * 1024) }, "{}"],
    ["actual", {}, "x".repeat(65 * 1024)],
  ])("rejects %s oversized JSON bodies", async (_case, headers, body) => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await proxyRequest(request("/api", {
      method: "POST",
      headers,
      body,
    }), new URL("http://service.test"), { jsonBody: true });

    expect(response.status).toBe(413);
    expect(await response.json()).toMatchObject({ error: { code: "REQUEST_TOO_LARGE" } });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("creates a secure anonymous cookie for direct HTTPS API callers", async () => {
    process.env.PORTAL_PUBLIC_ORIGIN = "https://portal.test";
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    const response = await proxyRequest(request("/api"), new URL("http://service.test"), {
      includeClient: true,
    });

    expect(response.status).toBe(204);
    const cookie = response.headers.get("set-cookie") ?? "";
    expect(cookie).toContain("hsbc_estimator_client=");
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("SameSite=lax");
    expect(cookie).toContain("Path=/");
    expect(cookie).toContain("Max-Age=31536000");
    expect(cookie).toContain("Secure");
    expect(cookie).toContain("Expires=");
  });

  it("returns a stable 503 and identity cookie when the service is unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("secret network detail")));

    const response = await proxyRequest(request("/api"), new URL("http://service.test"));

    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({
      error: { code: "SERVICE_UNAVAILABLE", message: expect.not.stringContaining("secret") },
    });
    expect(response.headers.get("set-cookie")).toContain("hsbc_estimator_client=");
  });

  it("builds only configured service URLs and bounded safe query parameters", () => {
    expect(serviceUrl("estimator", "/api/v1/estimates").toString())
      .toBe("http://estimator:8000/api/v1/estimates");
    expect(serviceUrl("market", "/api/v1/market/analysis").toString())
      .toBe("http://market:8080/api/v1/market/analysis");
    const target = copyAllowedQuery(
      new URLSearchParams([
        ["page", "1"],
        ["bad-key", "ignored"],
        ["oversized", "x".repeat(129)],
        ["page", "2"],
      ]),
      new URL("http://service.test/path"),
    );
    expect(target.toString()).toBe("http://service.test/path?page=1&page=2");
  });

  it("creates the stable error envelope with correlation response header", async () => {
    const response = errorResponse(422, "VALIDATION_ERROR", "Invalid fields.", correlation);
    expect(response.headers.get("x-correlation-id")).toBe(correlation);
    expect(await response.json()).toEqual({
      error: { code: "VALIDATION_ERROR", message: "Invalid fields." },
      correlation_id: correlation,
    });
  });
});
