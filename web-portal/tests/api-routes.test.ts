// @vitest-environment node

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET as health } from "@/app/api/health/route";
import {
  DELETE as clearHistory,
  GET as getHistory,
  POST as createEstimate,
} from "@/app/api/estimator/estimates/route";
import { DELETE as deleteEstimate } from "@/app/api/estimator/estimates/[estimateId]/route";
import { GET as analysis } from "@/app/api/market/analysis/route";
import { POST as whatIf } from "@/app/api/market/what-if/route";
import { GET as csv } from "@/app/api/market/exports/data.csv/route";
import { POST as pdf } from "@/app/api/market/exports/report.pdf/route";

const correlation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const estimateId = "11111111-1111-4111-8111-111111111111";

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

  it("proxies history reads with bounded query names and anonymous identity", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const cursor = "a".repeat(136);

    const response = await getHistory(apiRequest(
      `/api/estimator/estimates?limit=20&cursor=${cursor}&bad-key=no`,
    ));

    expect(response.status).toBe(200);
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString())
      .toBe(`http://estimator:8000/api/v1/estimates?limit=20&cursor=${cursor}`);
    const headers = (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(headers.get("x-estimator-client-id")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it.each([
    ["create", (request: NextRequest) => createEstimate(request)],
    ["clear", (request: NextRequest) => clearHistory(request)],
  ])("rejects cross-origin estimator %s mutations", async (_name, handler) => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await handler(apiRequest("/api/estimator/estimates", {
      method: "POST",
      headers: { origin: "https://evil.test" },
      body: "{}",
    }));

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards same-origin estimate creation JSON", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ estimate_id: estimateId }), {
      status: 201,
    }));
    vi.stubGlobal("fetch", fetchMock);
    const response = await createEstimate(apiRequest("/api/estimator/estimates", {
      method: "POST",
      headers: { origin: "http://portal.test", "content-type": "application/json" },
      body: '{"square_footage":1800}',
    }));

    expect(response.status).toBe(201);
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString())
      .toBe("http://estimator:8000/api/v1/estimates");
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).body).toBe('{"square_footage":1800}');
  });

  it("validates estimate IDs before deletion and forwards valid IDs", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const source = apiRequest(`/api/estimator/estimates/${estimateId}`, {
      method: "DELETE",
      headers: { origin: "http://portal.test" },
    });

    const invalid = await deleteEstimate(source, { params: Promise.resolve({ estimateId: "invalid" }) });
    expect(invalid.status).toBe(400);
    const valid = await deleteEstimate(source, { params: Promise.resolve({ estimateId }) });
    expect(valid.status).toBe(204);
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString())
      .toBe(`http://estimator:8000/api/v1/estimates/${estimateId}`);
  });

  it("proxies market analysis and CSV queries", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 200 }))
      .mockResolvedValueOnce(new Response("id,price\n", {
        status: 200,
        headers: {
          "content-type": "text/csv",
          "content-disposition": "attachment; filename=data.csv",
        },
      }));
    vi.stubGlobal("fetch", fetchMock);

    await analysis(apiRequest("/api/market/analysis?priceMin=100&page=1"));
    const csvResponse = await csv(apiRequest("/api/market/exports/data.csv?sort=price"));

    expect((fetchMock.mock.calls[0]?.[0] as URL).toString())
      .toBe("http://market:8080/api/v1/market/analysis?priceMin=100&page=1");
    expect((fetchMock.mock.calls[1]?.[0] as URL).toString())
      .toBe("http://market:8080/api/v1/market/exports/data.csv?sort=price");
    expect(csvResponse.headers.get("content-type")).toContain("text/csv");
  });

  it("proxies same-origin what-if and PDF bodies", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 200 }))
      .mockResolvedValueOnce(new Response("%PDF", {
        status: 200,
        headers: { "content-type": "application/pdf" },
      }));
    vi.stubGlobal("fetch", fetchMock);
    const mutationHeaders = { origin: "http://portal.test", "content-type": "application/json" };

    await whatIf(apiRequest("/api/market/what-if", {
      method: "POST", headers: mutationHeaders, body: '{"bedrooms":3}',
    }));
    const pdfResponse = await pdf(apiRequest("/api/market/exports/report.pdf?priceMin=100", {
      method: "POST", headers: mutationHeaders, body: "null",
    }));

    expect((fetchMock.mock.calls[0]?.[0] as URL).toString())
      .toBe("http://market:8080/api/v1/market/what-if");
    expect((fetchMock.mock.calls[1]?.[0] as URL).toString())
      .toBe("http://market:8080/api/v1/market/exports/report.pdf?priceMin=100");
    expect(pdfResponse.headers.get("content-type")).toBe("application/pdf");
  });
});
