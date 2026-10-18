import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { fillProperty, propertyA } from "./helpers";

test.describe("Task 2 portal with contract-faithful mock backends", () => {
  test("renders both applications with server-loaded initial data", async ({ page, request }) => {
    const estimatorResponse = await request.get("/estimator");
    expect(estimatorResponse.ok()).toBeTruthy();
    const estimatorHtml = await estimatorResponse.text();
    expect(estimatorHtml).toContain("Property estimator");
    expect(estimatorHtml).toContain("Previous estimates");

    await page.goto("/estimator");
    await expect(page.getByRole("heading", { level: 1, name: "Property estimator" })).toBeVisible();
    await expect(page.getByText("$412,845.00")).toBeVisible();
    await page.getByRole("link", { name: "Market analysis" }).first().click();
    await expect(page.getByRole("heading", { level: 1, name: "Market analysis" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("50 matching")).toBeVisible();
    await expect(page.getByText(/server-rendered and pinned/)).toBeVisible();
  });

  test("validates, estimates, reuses and compares without a page reload", async ({ page }) => {
    await page.goto("/estimator");
    await page.getByRole("button", { name: "Estimate value" }).click();
    await expect(page.locator("#estimate-square_footage-error")).toHaveText("Enter a value.");
    await page.getByRole("button", { name: "Family house" }).click();
    await page.getByRole("button", { name: "Estimate value" }).click();
    await expect(page.getByRole("heading", { name: "Estimated property value" })).toBeVisible();
    await expect(page.getByText("$427,630.00").first()).toBeVisible();
    await expect(page.getByRole("status")).toContainText("Estimate created");
    const compare = page.getByRole("checkbox", { name: /Compare estimate/ });
    await compare.nth(0).check();
    await compare.nth(1).check();
    await expect(page.getByRole("heading", { name: "Properties side by side" })).toBeVisible();
    await page.getByRole("button", { name: "Use as scenario" }).nth(1).click();
    await expect(page.locator("#estimate-square_footage")).toHaveValue("1800");
  });

  test("keeps the dashboard visible and scroll position stable during URL transitions", async ({ page }) => {
    await page.goto("/market");
    await page.getByText(/Market filters/).click();
    await page.locator("#filter-price_gte").fill("250000");
    const before = await page.evaluate(() => window.scrollY);
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(page.getByText("Updating dashboard…")).toBeVisible();
    await expect(page.getByRole("heading", { name: /Matching properties/ })).toBeVisible();
    await expect(page).toHaveURL(/price_gte=250000/);
    expect(Math.abs((await page.evaluate(() => window.scrollY)) - before)).toBeLessThan(4);
    await expect(page.getByText(/Price ≥ 250000/)).toBeVisible();
    await page.getByRole("button", { name: /Actual price/ }).click();
    await expect(page).toHaveURL(/sort=price%3Aasc/);
    await page.getByRole("button", { name: /Actual price/ }).click();
    await expect(page).toHaveURL(/sort=price%3Adesc/);
  });

  test("uses opaque next/back cursor navigation", async ({ page }) => {
    await page.goto("/market");
    await page.getByRole("button", { name: "Next results" }).click();
    await expect(page).toHaveURL(/cursor=cursor-20/);
    await expect(page.getByText("21", { exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: "Previous results" }).click();
    await expect(page).not.toHaveURL(/cursor=/);
    await expect(page.getByText("1", { exact: true }).first()).toBeVisible();
  });

  test("runs an isolated what-if and completes an asynchronous streaming export", async ({ page }) => {
    await page.goto("/market");
    await fillProperty(page, "what-if", propertyA);
    await page.getByRole("button", { name: "Run what-if" }).click();
    await expect(page.getByText(/Estimated property value · model/)).toBeVisible();
    await expect(page.getByText(/Filtered-market context/)).toBeVisible();
    await expect(page.getByText("50 matching")).toBeVisible();
    await page.getByRole("button", { name: "Export CSV" }).click();
    await expect(page.getByText(/CSV · queued/)).toBeVisible();
    await expect(page.getByText(/CSV · succeeded/)).toBeVisible({ timeout: 15_000 });
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("link", { name: "Download" }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe("market.csv");
  });

  test("honours reduced motion and has no serious accessibility violations", async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    for (const route of ["/estimator", "/market"]) {
      await page.goto(route);
      const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
      expect(results.violations.filter((item) => item.impact === "serious" || item.impact === "critical"), JSON.stringify(results.violations, null, 2)).toEqual([]);
    }
  });

  test("keeps document width stable on mobile while the table scrolls internally", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/market");
    await expect(page.getByRole("button", { name: "Run what-if" })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    await expect(page.getByLabel(/Market records; scroll horizontally/)).toHaveCSS("overflow-x", "auto");
  });
});
