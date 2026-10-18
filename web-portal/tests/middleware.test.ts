// @vitest-environment node

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { config, middleware } from "@/middleware";

const clientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

describe("anonymous estimator identity middleware", () => {
  beforeEach(() => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue(clientId);
  });

  afterEach(() => delete process.env.PORTAL_PUBLIC_ORIGIN);

  it("keeps an existing UUIDv4 identity without replacing it", () => {
    const request = new NextRequest("http://portal.test/estimator", {
      headers: { cookie: `hsbc_estimator_client=${clientId}` },
    });

    const response = middleware(request);

    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("creates an HTTP-only one-year identity and forwards it to the first server render", () => {
    const response = middleware(new NextRequest("http://portal.test/estimator"));

    const cookie = response.headers.get("set-cookie") ?? "";
    expect(cookie).toContain(`hsbc_estimator_client=${clientId}`);
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("SameSite=lax");
    expect(cookie).toContain("Max-Age=31536000");
    expect(cookie).not.toContain("Secure");
    expect(response.headers.get("x-middleware-request-x-estimator-client-id")).toBe(clientId);
  });

  it("sets Secure when the public portal uses HTTPS and replaces malformed cookies", () => {
    process.env.PORTAL_PUBLIC_ORIGIN = "https://portal.test";
    const response = middleware(new NextRequest("https://portal.test/market", {
      headers: { cookie: "hsbc_estimator_client=not-a-uuid" },
    }));

    expect(response.headers.get("set-cookie")).toContain("Secure");
    expect(response.headers.get("set-cookie")).toContain(clientId);
  });

  it("excludes API and Next static assets from middleware identity mutation", () => {
    expect(config.matcher).toEqual(["/((?!api|_next/static|_next/image|favicon.ico).*)"]);
  });
});
