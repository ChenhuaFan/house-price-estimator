import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearEstimateHistory, createEstimate, deleteEstimate, loadHistory, PortalApiError } from "@/features/estimator/api";
import { cancelExport, createExport, getExport, loadMarketDashboard, runWhatIf } from "@/features/market/api";
import { estimate, marketPageModel, propertyInput, whatIfResponse } from "@/tests/fixtures";

const correlation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const exportId = "11111111-1111-4111-8111-111111111111";
const exportJob = {
  export_id: exportId,
  status: "QUEUED" as const,
  format: "CSV" as const,
  data_revision: 1,
  query_fingerprint: `sha256:${"a".repeat(64)}`,
  created_at: "2026-10-19T00:00:00Z",
  expires_at: "2026-10-20T00:00:00Z",
};

describe("browser API adapters", () => {
  beforeEach(() => vi.spyOn(crypto, "randomUUID").mockReturnValue(correlation));
  afterEach(() => vi.unstubAllGlobals());

  it("submits an idempotent estimate request with a correlation ID", async () => {
    const result = estimate();
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(result), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(createEstimate(propertyInput, result.request_id)).resolves.toEqual(result);
    expect(fetchMock).toHaveBeenCalledWith("/api/estimator/estimates", expect.objectContaining({ method: "POST", body: JSON.stringify({ ...propertyInput, request_id: result.request_id }), headers: expect.objectContaining({ "X-Correlation-ID": correlation }) }));
  });

  it("loads cursor history and supports deletion", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [], next_cursor: null }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await loadHistory("opaque cursor/value");
    await deleteEstimate("estimate-id");
    await clearEstimateHistory();
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/estimator/estimates?limit=20&cursor=opaque+cursor%2Fvalue");
  });

  it("keeps structured error status, fields, correlation and Retry-After", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      error: { code: "RATE_LIMITED", message: "Slow down.", fields: [{ field: "price", message: "Wait." }] },
      correlation_id: correlation,
    }), { status: 429, headers: { "retry-after": "7" } })));
    await expect(createEstimate(propertyInput, correlation)).rejects.toMatchObject({
      code: "RATE_LIMITED", status: 429, retryAfterSeconds: 7, correlationId: correlation,
      fields: [{ field: "price", message: "Wait." }],
    });
  });

  it("serialises what-if decimal values to the v2 wire contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(whatIfResponse), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(runWhatIf(propertyInput)).resolves.toEqual(whatIfResponse);
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toMatchObject({ bathrooms: "2.5", distance_to_city_center: "4.125", school_rating: "8.500" });
  });

  it("loads a complete market dashboard through the client read endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(marketPageModel), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const query = new URLSearchParams({ price_gte: "250000.00" });

    await expect(loadMarketDashboard(query)).resolves.toEqual(marketPageModel);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/market/dashboard?price_gte=250000.00",
      expect.objectContaining({ cache: "no-store", headers: expect.any(Headers) }),
    );
  });

  it("creates, polls and cancels asynchronous exports", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(exportJob), { status: 202, headers: { "retry-after": "4" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...exportJob, status: "RUNNING" }), { status: 200, headers: { "retry-after": "2" } }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);
    const request = { format: "CSV" as const, selection: { scope: {}, sort: [] } };
    await expect(createExport(request, correlation)).resolves.toEqual({ job: exportJob, retryAfterSeconds: 4 });
    await expect(getExport(exportId)).resolves.toMatchObject({ job: { status: "RUNNING" }, retryAfterSeconds: 2 });
    await expect(cancelExport(exportId)).resolves.toBeUndefined();
    expect((fetchMock.mock.calls[0]?.[1]?.headers as Headers).get("idempotency-key")).toBe(correlation);
  });

  it("uses a stable fallback for malformed responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not-json", { status: 502, headers: { "x-correlation-id": "fallback-reference" } })));
    await expect(clearEstimateHistory()).rejects.toEqual(new PortalApiError("The request could not be completed. Please retry.", "REQUEST_FAILED", "fallback-reference", [], 502));
  });
});
