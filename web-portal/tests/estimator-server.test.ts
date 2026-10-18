// @vitest-environment node

import { afterEach, describe, expect, it, vi } from "vitest";
import { getEstimatorPageModel } from "@/features/estimator/server";

vi.mock("next/headers", () => ({ headers: () => Promise.resolve(new Headers({ "x-estimator-client-id": "client-id" })) }));

describe("estimator Server Component model", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    delete process.env.ESTIMATOR_SERVICE_URL;
  });

  it("loads visitor history directly from the estimator service", async () => {
    process.env.ESTIMATOR_SERVICE_URL = "http://estimator.test";
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], next_cursor: null }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(getEstimatorPageModel()).resolves.toEqual({ history: { items: [], next_cursor: null }, warning: null });
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString()).toBe("http://estimator.test/api/v1/estimates?limit=20");
    expect((fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>)["X-Estimator-Client-ID"]).toBe("client-id");
  });

  it("preserves estimator functionality when initial history is unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(getEstimatorPageModel()).resolves.toMatchObject({ history: { items: [], next_cursor: null }, warning: expect.stringContaining("temporarily unavailable") });
  });
});
