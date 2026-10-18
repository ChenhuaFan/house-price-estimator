import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  clearEstimateHistory,
  createEstimate,
  deleteEstimate,
  loadHistory,
  PortalApiError,
} from "@/features/estimator/api";
import { downloadPdf, runWhatIf } from "@/features/market/api";
import { estimate, propertyInput, whatIfResponse } from "@/tests/fixtures";

const correlation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

describe("browser API adapters", () => {
  beforeEach(() => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue(correlation);
  });

  afterEach(() => vi.unstubAllGlobals());

  it("submits an idempotent estimate request with a correlation ID", async () => {
    const result = estimate();
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(result), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createEstimate(propertyInput, result.request_id)).resolves.toEqual(result);
    expect(fetchMock).toHaveBeenCalledWith("/api/estimator/estimates", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ ...propertyInput, request_id: result.request_id }),
      headers: expect.objectContaining({ "X-Correlation-ID": correlation }),
    }));
  });

  it("loads cursor history and supports single and complete deletion", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [], next_cursor: null }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadHistory("opaque cursor/value")).resolves.toEqual({ items: [], next_cursor: null });
    await deleteEstimate("estimate-id");
    await clearEstimateHistory();

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/estimator/estimates?limit=20&cursor=opaque+cursor%2Fvalue",
    );
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/estimator/estimates/estimate-id");
    expect(fetchMock.mock.calls[2]?.[0]).toBe("/api/estimator/estimates");
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({ method: "DELETE" });
  });

  it("exposes structured validation details from estimator failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      error: {
        code: "VALIDATION_ERROR",
        message: "Invalid property.",
        fields: [{ field: "bathrooms", message: "Use 0.5 increments." }],
      },
      correlation_id: correlation,
    }), { status: 422 })));

    await expect(createEstimate(propertyInput, correlation)).rejects.toMatchObject({
      name: "Error",
      message: "Invalid property.",
      code: "VALIDATION_ERROR",
      correlationId: correlation,
      fields: [{ field: "bathrooms", message: "Use 0.5 increments." }],
    });
  });

  it("uses a stable fallback for malformed error responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not-json", {
      status: 502,
      headers: { "x-correlation-id": "fallback-reference" },
    })));

    await expect(clearEstimateHistory()).rejects.toEqual(new PortalApiError(
      "The request could not be completed. Please retry.",
      "REQUEST_FAILED",
      "fallback-reference",
    ));
  });

  it("submits market what-if requests and parses the shared model response", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(whatIfResponse), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(runWhatIf(propertyInput)).resolves.toEqual(whatIfResponse);
    expect(fetchMock).toHaveBeenCalledWith("/api/market/what-if", expect.objectContaining({
      method: "POST",
      body: JSON.stringify(propertyInput),
    }));
  });

  it("downloads a successful PDF using the canonical filename", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(new Blob(["%PDF-test"]), {
      status: 200,
      headers: { "content-type": "application/pdf" },
    }));
    vi.stubGlobal("fetch", fetchMock);
    const createObjectURL = vi.fn().mockReturnValue("blob:report");
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);

    await downloadPdf(new URLSearchParams({ priceMin: "100000" }), whatIfResponse);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/market/exports/report.pdf?priceMin=100000");
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ body: JSON.stringify(whatIfResponse) });
    expect(click).toHaveBeenCalledOnce();
    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:report");
  });

  it("does not create a download when PDF generation fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      error: { code: "EXPORT_FAILED", message: "Could not render." },
    }), { status: 500 })));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);

    await expect(downloadPdf(new URLSearchParams(), null)).rejects.toMatchObject({
      code: "EXPORT_FAILED",
      message: "Could not render.",
    });
    expect(click).not.toHaveBeenCalled();
  });
});
