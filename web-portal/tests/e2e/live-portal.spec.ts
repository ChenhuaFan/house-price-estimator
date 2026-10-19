import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { createEstimateThroughPortal } from "./helpers";

test.skip(process.env.E2E_MOCKS === "1", "These regressions exercise the live portal and services.");

test.describe("live portal regressions", () => {
  test("keeps estimator history after reload and cross-application navigation", async ({ page }) => {
    await page.goto("/estimator");
    const created = await createEstimateThroughPortal(page, randomUUID());
    expect(created.status).toBe(201);
    const estimateId = created.body.estimate_id;
    expect(estimateId).toBeTruthy();

    try {
      await page.reload();
      await expect(page.getByRole("checkbox", { name: `Compare estimate ${estimateId}` })).toBeVisible();

      await page.getByRole("link", { name: "Market analysis" }).first().click();
      await expect(page.getByRole("heading", { level: 1, name: "Market analysis" })).toBeVisible();
      await page.getByRole("link", { name: "Property estimator" }).first().click();
      await expect(page.getByRole("checkbox", { name: `Compare estimate ${estimateId}` })).toBeVisible();
    } finally {
      if (estimateId) {
        await page.evaluate(async (id) => {
          await fetch(`/api/estimator/estimates/${id}`, { method: "DELETE" });
        }, estimateId);
      }
    }
  });

  test("applies human-entered market filters against the strict live API contract", async ({ page }) => {
    const marketRouteReads: string[] = [];
    const dashboardReads: string[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (request.method() === "GET" && url.pathname === "/market") marketRouteReads.push(request.url());
      if (request.method() === "GET" && url.pathname === "/api/market/dashboard") dashboardReads.push(request.url());
    });
    await page.goto("/market");
    await page.getByText(/Market filters/).click();
    await page.locator("#filter-price_gte").fill("250000");
    await page.getByRole("button", { name: "Apply filters" }).click();

    await expect(page).toHaveURL(/price_gte=250000/);
    await expect(page.getByRole("heading", { level: 1, name: "Market analysis" })).toBeVisible();
    await expect(page.getByRole("heading", { name: /Matching properties/ })).toBeVisible();
    await expect(page.getByText(/Price ≥ 250000\.00/)).toBeVisible();
    await expect(page.getByRole("heading", { name: "We could not load this page" })).toHaveCount(0);
    expect(marketRouteReads).toHaveLength(1);
    expect(dashboardReads).toHaveLength(1);
  });
});
