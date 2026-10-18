// @vitest-environment node

import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchJson, ServerDataError } from "@/lib/server/request";

describe("server JSON transport", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("returns successful JSON without caching", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(fetchJson<{ ok: boolean }>(new URL("http://service.test/data"))).resolves.toEqual({ ok: true });
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ cache: "no-store" });
  });

  it("normalises JSON and non-JSON backend errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ error: { code: "CONFLICT", message: "Dataset changed." }, correlation_id: "reference" }), { status: 409 })).mockResolvedValueOnce(new Response("bad gateway", { status: 502 })));
    await expect(fetchJson(new URL("http://service.test/conflict"))).rejects.toEqual(new ServerDataError("Dataset changed.", 409, "CONFLICT", "reference"));
    await expect(fetchJson(new URL("http://service.test/bad"))).rejects.toMatchObject({ status: 502, code: "REQUEST_FAILED" });
  });

  it("maps network failures to service unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(fetchJson(new URL("http://service.test/offline"))).rejects.toMatchObject({ status: 503, code: "SERVICE_UNAVAILABLE" });
  });
});
