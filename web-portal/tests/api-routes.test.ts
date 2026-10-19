// @vitest-environment node

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET as health } from "@/app/api/health/route";
import { DELETE as clearHistory, GET as getHistory, POST as createEstimate } from "@/app/api/estimator/estimates/route";
import { DELETE as deleteEstimate } from "@/app/api/estimator/estimates/[estimateId]/route";
import { GET as properties } from "@/app/api/market/properties/route";
import { GET as dashboard } from "@/app/api/market/dashboard/route";
import { POST as statistics } from "@/app/api/market/statistics/route";
import { POST as whatIf } from "@/app/api/market/what-if/route";
import { POST as createExport } from "@/app/api/market/exports/route";
import { DELETE as cancelExport, GET as getExport } from "@/app/api/market/exports/[exportId]/route";
import { GET as downloadExport } from "@/app/api/market/exports/[exportId]/content/route";

const correlation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const resourceId = "11111111-1111-4111-8111-111111111111";

function apiRequest(path: string, init: ConstructorParameters<typeof NextRequest>[1] = {}): NextRequest {
  const headers = new Headers(init.headers);
  headers.set("x-correlation-id", correlation);
  return new NextRequest(`http://portal.test${path}`, { ...init, headers });
}

describe("portal route handlers", () => {
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

  it("reports portal health", async () => {
    const response = health();
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ status: "ok", service: "web-portal" });
  });

  it("proxies estimator reads with a bounded query and anonymous identity", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const cursor = "a".repeat(136);
    await getHistory(apiRequest(`/api/estimator/estimates?limit=20&cursor=${cursor}&bad-key=no`));
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString()).toBe(`http://estimator:8000/api/v1/estimates?limit=20&cursor=${cursor}`);
    const headers = (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(headers.get("x-estimator-client-id")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it.each([
    ["create", (request: NextRequest) => createEstimate(request)],
    ["clear", (request: NextRequest) => clearHistory(request)],
  ])("rejects cross-origin estimator %s mutations", async (_name, handler) => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await handler(apiRequest("/api/estimator/estimates", { method: "POST", headers: { origin: "https://evil.test" }, body: "{}" }));
    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards estimator create and validates delete IDs", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ estimate_id: resourceId }), { status: 201 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const headers = { origin: "http://portal.test", "content-type": "application/json" };
    expect((await createEstimate(apiRequest("/api/estimator/estimates", { method: "POST", headers, body: "{}" }))).status).toBe(201);
    const invalid = await deleteEstimate(apiRequest("/api/estimator/estimates/invalid", { method: "DELETE", headers }), { params: Promise.resolve({ estimateId: "invalid" }) });
    expect(invalid.status).toBe(400);
    const valid = await deleteEstimate(apiRequest(`/api/estimator/estimates/${resourceId}`, { method: "DELETE", headers }), { params: Promise.resolve({ estimateId: resourceId }) });
    expect(valid.status).toBe(204);
  });

  it("proxies v2 property queries without interpreting opaque cursors", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await properties(apiRequest("/api/market/properties?price_gte=100000.00&cursor=opaque%2Fvalue&bad-key=no"));
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString()).toBe("http://market:8080/api/v2/market/properties?price_gte=100000.00&cursor=opaque%2Fvalue");
  });

  it("assembles a version-pinned dashboard for post-hydration client reads", async () => {
    const dataset = { id: "00000000-0000-4000-8000-000000000001", content_sha256: "a".repeat(64) };
    const propertyPage = {
      dataset_version: dataset,
      query_fingerprint: `sha256:${"b".repeat(64)}`,
      items: [],
      page: { limit: 20, has_more: false },
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(propertyPage), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ dataset_version: dataset, query_fingerprint: "overview", groups: [{ key: {}, values: [{ metric_id: "count", value: "0" }] }] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ dataset_version: dataset, query_fingerprint: "bedrooms", groups: [] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ dataset_version: dataset, query_fingerprint: "schools", groups: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await dashboard(apiRequest("/api/market/dashboard?price_gte=250000"));
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ overview: { count: "0" }, query: { price_gte: "250000.00" } });
    expect((fetchMock.mock.calls[0]?.[0] as URL).searchParams.get("price_gte")).toBe("250000.00");
    expect(response.headers.get("cache-control")).toBe("no-store");
  });

  it("forwards v2 JSON mutations and the export idempotency key", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 200 }))
      .mockResolvedValueOnce(new Response("{}", { status: 200 }))
      .mockResolvedValueOnce(new Response("{}", { status: 202, headers: { "retry-after": "3" } }));
    vi.stubGlobal("fetch", fetchMock);
    const headers = { origin: "http://portal.test", "content-type": "application/json" };
    await statistics(apiRequest("/api/market/statistics", { method: "POST", headers, body: "{}" }));
    await whatIf(apiRequest("/api/market/what-if", { method: "POST", headers, body: "{}" }));
    const accepted = await createExport(apiRequest("/api/market/exports", { method: "POST", headers: { ...headers, "idempotency-key": resourceId }, body: "{}" }));
    expect((fetchMock.mock.calls[0]?.[0] as URL).pathname).toBe("/api/v2/market/statistics/query");
    expect((fetchMock.mock.calls[1]?.[0] as URL).pathname).toBe("/api/v2/market/what-if");
    expect((fetchMock.mock.calls[2]?.[0] as URL).pathname).toBe("/api/v2/market/exports");
    const forwarded = (fetchMock.mock.calls[2]?.[1] as RequestInit).headers as Headers;
    expect(forwarded.get("idempotency-key")).toBe(resourceId);
    expect(accepted.headers.get("retry-after")).toBe("3");
  });

  it("validates export IDs and preserves streaming response headers", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(new Response("csv", { status: 200, headers: {
        "content-type": "text/csv", "content-disposition": "attachment; filename=data.csv",
        "content-length": "3", "content-digest": "sha-256=:abc:", etag: '"abc"',
      } }));
    vi.stubGlobal("fetch", fetchMock);
    const validParams = { params: Promise.resolve({ exportId: resourceId }) };
    expect((await getExport(apiRequest(`/api/market/exports/${resourceId}`), validParams)).status).toBe(200);
    expect((await cancelExport(apiRequest(`/api/market/exports/${resourceId}`, { method: "DELETE", headers: { origin: "http://portal.test" } }), validParams)).status).toBe(202);
    const content = await downloadExport(apiRequest(`/api/market/exports/${resourceId}/content`), validParams);
    expect(content.headers.get("content-disposition")).toContain("data.csv");
    expect(content.headers.get("content-digest")).toBe("sha-256=:abc:");
    expect(content.headers.get("etag")).toBe('"abc"');
  });
});
