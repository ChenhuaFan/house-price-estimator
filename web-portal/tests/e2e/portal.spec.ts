import AxeBuilder from "@axe-core/playwright";
import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { expect, test } from "@playwright/test";
import { clearHistory, fillProperty, historySection, propertyA, propertyB } from "./helpers";

test.describe("unified portal user journeys", () => {
  test("navigates one shared accessible layout and renders initial RSC content", async ({ page, request }) => {
    const serverResponse = await request.get("/estimator");
    expect(serverResponse.ok()).toBeTruthy();
    expect(await serverResponse.text()).toContain("A clear estimate");
    await page.goto("/estimator");
    await expect(page.getByRole("heading", { level: 1, name: /A clear estimate/ })).toBeVisible();
    await page.getByRole("link", { name: "Market analysis" }).click();
    await expect(page.getByRole("heading", { level: 1, name: /Explore the evidence/ })).toBeVisible();
    await expect(page.getByText("50 total records")).toBeVisible();
    await page.goBack();
    await expect(page.getByRole("heading", { level: 1, name: /A clear estimate/ })).toBeVisible();
    await page.goForward();
    await expect(page.getByRole("heading", { level: 1, name: /Explore the evidence/ })).toBeVisible();
  });

  test("validates, estimates, persists, reopens, compares, and clears history", async ({ page }) => {
    await page.goto("/estimator");
    await page.getByRole("button", { name: "Estimate value" }).click();
    await expect(page.locator("#estimate-square_footage-error")).toHaveText("Enter a value.");
    await expect(page.locator("#estimate-square_footage")).toBeFocused();

    await fillProperty(page, "estimate", propertyA);
    await page.getByRole("button", { name: "Estimate value" }).click();
    await expect(page.getByRole("heading", { name: "Estimated property value" })).toBeVisible();
    const firstPrice = await page.getByRole("region", { name: "Estimated property value" })
      .locator(".numeric").first().textContent();
    expect(firstPrice).toMatch(/^\$[\d,]+\.\d{2}$/);
    await expect(historySection(page).getByText(firstPrice!, { exact: true })).toBeVisible();

    await page.reload();
    await expect(historySection(page).getByText(firstPrice!, { exact: true })).toBeVisible();
    await historySection(page).getByRole("button", { name: "View" }).first().click();
    await expect(page.getByRole("heading", { name: "Estimated property value" })).toBeVisible();

    await fillProperty(page, "estimate", propertyB);
    await page.getByRole("button", { name: "Estimate value" }).click();
    await expect(historySection(page).locator("tbody tr")).toHaveCount(2);
    const compare = historySection(page).getByRole("checkbox");
    await compare.nth(0).check();
    await compare.nth(1).check();
    await expect(page.getByRole("heading", { name: "Properties side by side" })).toBeVisible();

    await clearHistory(page);
  });

  test("retains advisory warnings with the persisted estimate", async ({ page }) => {
    await page.goto("/estimator");
    await fillProperty(page, "estimate", { ...propertyA, school_rating: "11" });
    await page.getByRole("button", { name: "Estimate value" }).click();
    await expect(page.getByRole("status")).toContainText("Advisory note for school_rating");
    await page.reload();
    await historySection(page).getByRole("button", { name: "View" }).first().click();
    await expect(page.getByRole("status")).toContainText("Submitted value 11");
    await clearHistory(page);
  });

  test("filters, sorts, deep-links, resets, runs what-if, and downloads current exports", async ({ page }) => {
    await page.goto("/market");
    await expect(page.getByText("50 total records")).toBeVisible();
    await page.locator("#filter-priceMin").fill("300000");
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(page).toHaveURL(/priceMin=300000/);
    await expect(page.getByRole("button", { name: "Apply filters" })).toBeEnabled();
    const countText = await page.getByText(/total records$/).textContent();
    expect(Number(countText?.split(" ")[0])).toBeGreaterThan(0);
    expect(Number(countText?.split(" ")[0])).toBeLessThan(50);

    await page.getByRole("button", { name: "Actual price" }).click();
    await expect(page).toHaveURL(/sort=price/);
    await page.getByRole("button", { name: /Actual price/ }).click();
    await expect(page).toHaveURL(/direction=desc/);
    const deepLink = page.url();
    await page.goto(deepLink);
    await expect(page.locator("#filter-priceMin")).toHaveValue("300000");

    await fillProperty(page, "what-if", propertyB);
    await page.getByRole("button", { name: "Run what-if" }).click();
    await expect(page.getByText(/Estimated value · model/)).toBeVisible();

    const [csvDownload] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("link", { name: "Download CSV" }).click(),
    ]);
    expect(csvDownload.suggestedFilename()).toBe("property-market-analysis.csv");
    const csv = await csvDownload.createReadStream();
    const csvChunks: Buffer[] = [];
    for await (const chunk of csv) csvChunks.push(Buffer.from(chunk));
    expect(Buffer.concat(csvChunks).toString("utf8")).toMatch(/^id,square_footage,/);

    const [pdfDownload] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Download PDF" }).click(),
    ]);
    expect(pdfDownload.suggestedFilename()).toBe("property-market-analysis.pdf");
    const pdf = await pdfDownload.createReadStream();
    const pdfChunks: Buffer[] = [];
    for await (const chunk of pdf) pdfChunks.push(Buffer.from(chunk));
    expect(Buffer.concat(pdfChunks).subarray(0, 5).toString("ascii")).toBe("%PDF-");

    await page.getByRole("button", { name: "Reset filters" }).click();
    await expect(page).toHaveURL(/\/market$/);
    await expect(page.getByText("50 total records")).toBeVisible();
  });

  for (const route of ["/estimator", "/market"]) {
    test(`has no serious accessibility violations on ${route}`, async ({ page }) => {
      await page.goto(route);
      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
        .analyze();
      const blocking = results.violations.filter((violation) =>
        violation.impact === "serious" || violation.impact === "critical");
      expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([]);
    });
  }

  test("keeps required actions reachable without page-level overflow on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/market");
    await expect(page.getByRole("button", { name: "Apply filters" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Run what-if" })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test("keeps market exploration usable while the shared model is unavailable", async ({
    browserName,
    page,
    request,
  }) => {
    test.skip(browserName !== "chromium", "Controlled dependency failure runs once in Chromium.");
    await page.goto("/market");
    await fillProperty(page, "what-if", propertyA);
    const repositoryRoot = resolve(process.cwd(), "..");
    execFileSync("docker", ["compose", "stop", "model-service"], {
      cwd: repositoryRoot,
      stdio: "ignore",
    });

    try {
      await page.getByRole("button", { name: "Run what-if" }).click();
      await expect(page.locator(".status-error")).toContainText("Prediction is temporarily unavailable");
      await expect(page.getByText("50 total records")).toBeVisible();
      await expect(page.getByRole("heading", { name: "Market summary" })).toBeVisible();
    } finally {
      execFileSync("docker", ["compose", "up", "-d", "model-service"], {
        cwd: repositoryRoot,
        stdio: "ignore",
      });
    }

    await expect
      .poll(
        async () => {
          try {
            return (await request.get("http://127.0.0.1:8000/health/ready")).status();
          } catch {
            return 0;
          }
        },
        { timeout: 30_000 },
      )
      .toBe(200);
    const runWhatIf = page.getByRole("button", { name: "Run what-if" });
    const recoveredResult = page.getByText(/Estimated value · model/);
    await expect
      .poll(
        async () => {
          try {
            await runWhatIf.click();
            await recoveredResult.waitFor({ state: "visible", timeout: 3_000 });
            return true;
          } catch {
            return false;
          }
        },
        { timeout: 30_000, intervals: [1_000, 2_000, 5_000] },
      )
      .toBe(true);
  });
});
