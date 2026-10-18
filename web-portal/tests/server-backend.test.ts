// @vitest-environment node

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cookies, headers } from "next/headers";
import { marketAnalysis } from "@/tests/fixtures";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({ cookies: vi.fn(), headers: vi.fn() }));

const mockedCookies = vi.mocked(cookies);
const mockedHeaders = vi.mocked(headers);

async function backend() {
  return import("@/lib/server/backend");
}

describe("server-side backend adapter", () => {
  beforeEach(() => {
    vi.resetModules();
    process.env.ESTIMATOR_SERVICE_URL = "http://estimator:8000";
    process.env.MARKET_SERVICE_URL = "http://market:8080";
    mockedCookies.mockResolvedValue({ get: () => undefined } as Awaited<ReturnType<typeof cookies>>);
    mockedHeaders.mockResolvedValue(new Headers() as Awaited<ReturnType<typeof headers>>);
    vi.spyOn(crypto, "randomUUID").mockReturnValue("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    delete process.env.ESTIMATOR_SERVICE_URL;
    delete process.env.MARKET_SERVICE_URL;
  });

  it("normalizes only the market query fields accepted by the Java API", async () => {
    const { marketQueryString } = await backend();
    expect(marketQueryString({
      priceMin: "100000",
      sort: ["price", "source_id"],
      page: "",
      unexpected: "drop me",
    }).toString()).toBe("priceMin=100000&sort=price");
  });

  it("returns empty history before an anonymous client identity exists", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const { getInitialHistory } = await backend();

    await expect(getInitialHistory()).resolves.toEqual({ items: [], next_cursor: null });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("loads history with the cookie identity and canonical headers", async () => {
    mockedCookies.mockResolvedValue({
      get: (name: string) => name === "hsbc_estimator_client" ? { value: "client-cookie" } : undefined,
    } as Awaited<ReturnType<typeof cookies>>);
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ items: [], next_cursor: "next" }),
      { status: 200, headers: { "content-type": "application/json" } },
    ));
    vi.stubGlobal("fetch", fetchMock);
    const { getInitialHistory } = await backend();

    await expect(getInitialHistory()).resolves.toEqual({ items: [], next_cursor: "next" });
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe("http://estimator:8000/api/v1/estimates?limit=20");
    expect((init.headers as Headers).get("x-estimator-client-id")).toBe("client-cookie");
    expect((init.headers as Headers).get("x-correlation-id")).toMatch(/^[0-9a-f-]{36}$/);
    expect(init.cache).toBe("no-store");
  });

  it("uses the middleware request identity during the first server render", async () => {
    mockedHeaders.mockResolvedValue(new Headers({
      "x-estimator-client-id": "client-from-middleware",
    }) as Awaited<ReturnType<typeof headers>>);
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ items: [], next_cursor: null }),
      { status: 200 },
    ));
    vi.stubGlobal("fetch", fetchMock);
    const { getInitialHistory } = await backend();

    await getInitialHistory();

    const forwarded = (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(forwarded.get("x-estimator-client-id")).toBe("client-from-middleware");
  });

  it("loads market data with whitelisted filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(marketAnalysis), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const { getInitialMarketAnalysis } = await backend();

    await expect(getInitialMarketAnalysis({ priceMin: "100", ignored: "value" }))
      .resolves.toEqual(marketAnalysis);
    expect((fetchMock.mock.calls[0]?.[0] as URL).toString())
      .toBe("http://market:8080/api/v1/market/analysis?priceMin=100");
  });

  it("preserves safe dependency errors and their correlation reference", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ error: { message: "Dataset version was not found." } }),
      { status: 404, headers: { "x-correlation-id": "reference-id" } },
    )));
    const { BackendError, getInitialMarketAnalysis } = await backend();

    await expect(getInitialMarketAnalysis({})).rejects.toEqual(
      new BackendError("Dataset version was not found.", 404, "reference-id"),
    );
  });

  it("uses a stable fallback for non-JSON errors and connection failures", async () => {
    const { BackendError, getInitialMarketAnalysis } = await backend();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("proxy detail", { status: 502 }))
      .mockRejectedValueOnce(new Error("secret connection detail"));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getInitialMarketAnalysis({})).rejects.toEqual(
      new BackendError("The requested information could not be loaded.", 502),
    );
    await expect(getInitialMarketAnalysis({})).rejects.toEqual(
      new BackendError("A required service is temporarily unavailable.", 503),
    );
  });
});
