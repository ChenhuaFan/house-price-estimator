import AxeBuilder from "@axe-core/playwright";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { expect, test } from "@playwright/test";
import {
  clearHistory,
  createEstimateThroughPortal,
  fillProperty,
  historySection,
  propertyA,
  propertyB,
} from "./helpers";

const repositoryRoot = resolve(process.cwd(), "..");

function compose(...args: string[]): void {
  execFileSync("docker", ["compose", ...args], {
    cwd: repositoryRoot,
    stdio: "ignore",
  });
}

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

  test("supports keyboard navigation and reduced motion", async ({ browserName, page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/estimator");

    await page.keyboard.press(browserName === "webkit" ? "Alt+Tab" : "Tab");
    const skipLink = page.getByRole("link", { name: "Skip to main content" });
    await expect(skipLink).toBeFocused();
    await expect(skipLink).toBeVisible();
    await page.keyboard.press("Enter");
    await expect(page.locator("#main-content")).toBeFocused();

    const durations = await page.getByRole("button", { name: "Estimate value" }).evaluate((element) =>
      getComputedStyle(element).transitionDuration.split(",").map((value) => Number.parseFloat(value)),
    );
    expect(durations.every((duration) => duration <= 0.001)).toBe(true);
  });

  test("shows route loading and a recoverable error without losing the shared layout", async ({
    browserName,
    page,
    request,
  }) => {
    test.skip(browserName !== "chromium", "Controlled route failure runs once in Chromium.");
    await page.goto("/estimator");
    compose("pause", "market-service");

    try {
      const navigation = page.getByRole("link", { name: "Market analysis" }).click();
      await expect(page.locator('[aria-busy="true"]')).toBeVisible();
      compose("unpause", "market-service");
      await navigation;
      await expect(page.getByRole("heading", { level: 1, name: /Explore the evidence/ })).toBeVisible();

      compose("stop", "market-service");
      await page.goto("/market?priceMin=123456");
      await expect(page.getByRole("heading", { name: "We could not load this page" })).toBeVisible();
      await expect(page.getByRole("banner")).toBeVisible();
      await expect(page.getByText(/stack trace/i)).toHaveCount(0);

      compose("start", "market-service");
      await expect
        .poll(async () => (await request.get("/api/market/analysis?page=1&pageSize=1")).status(), {
          timeout: 30_000,
        })
        .toBe(200);
      await page.getByRole("button", { name: "Retry" }).click();
      await expect(page.getByRole("heading", { level: 1, name: /Explore the evidence/ })).toBeVisible();
    } finally {
      try {
        compose("unpause", "market-service");
      } catch {
        // The service was already unpaused or stopped.
      }
      compose("start", "market-service");
    }
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

  test("paginates, deletes one record, and clears a 21-record history", async ({ browserName, page }) => {
    test.skip(browserName !== "chromium", "The history stress journey runs once in Chromium.");
    await page.goto("/estimator");

    for (let index = 0; index < 21; index += 1) {
      const response = await createEstimateThroughPortal(page, randomUUID(), {
        ...propertyA,
        square_footage: String(1_500 + index),
      });
      expect(response.status).toBe(201);
    }

    await page.reload();
    const history = historySection(page);
    await expect(history.locator("tbody tr")).toHaveCount(20);
    await history.getByRole("button", { name: "Load more history" }).click();
    await expect(history.locator("tbody tr")).toHaveCount(21);

    page.once("dialog", (dialog) => dialog.accept());
    await history.getByRole("button", { name: "Delete" }).first().click();
    await expect(history.locator("tbody tr")).toHaveCount(20);
    await clearHistory(page);
  });

  test("replays an identical request without creating duplicate history", async ({ page }) => {
    await page.goto("/estimator");
    const requestId = randomUUID();
    const first = await createEstimateThroughPortal(page, requestId);
    const replay = await createEstimateThroughPortal(page, requestId);

    expect(first.status).toBe(201);
    expect(replay.status).toBe(200);
    expect(replay.body.estimate_id).toBe(first.body.estimate_id);

    await page.reload();
    await expect(historySection(page).locator("tbody tr")).toHaveCount(1);
    await clearHistory(page);
  });

  test("isolates anonymous estimate histories between browser contexts", async ({ browser, page }) => {
    await page.goto("/estimator");
    const first = await createEstimateThroughPortal(page, randomUUID(), propertyA);
    expect(first.status).toBe(201);
    await page.reload();
    await expect(historySection(page).locator("tbody tr")).toHaveCount(1);

    const secondContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
    const secondPage = await secondContext.newPage();
    try {
      await secondPage.goto("/estimator");
      await expect(secondPage.getByText("No estimates yet")).toBeVisible();
      const second = await createEstimateThroughPortal(secondPage, randomUUID(), propertyB);
      expect(second.status).toBe(201);
      await secondPage.reload();
      await expect(historySection(secondPage).locator("tbody tr")).toHaveCount(1);

      await page.reload();
      await expect(historySection(page).locator("tbody tr")).toHaveCount(1);
      await clearHistory(page);
      await clearHistory(secondPage);
    } finally {
      await secondContext.close();
    }
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

    const priceSort = page.getByRole("button", { name: /Actual price/ });
    await expect.poll(async () => {
      if (!page.url().includes("sort=price")) await priceSort.click();
      return page.url();
    }).toMatch(/sort=price/);
    await expect.poll(async () => {
      if (!page.url().includes("direction=desc")) await priceSort.click();
      return page.url();
    }).toMatch(/direction=desc/);
    const deepLink = page.url();
    await page.goto(deepLink);
    await expect(page.locator("#filter-priceMin")).toHaveValue("300000");

    await page.getByRole("button", { name: "Run what-if" }).click();
    await expect(page.locator("#what-if-square_footage-error")).toHaveText("Enter a value.");
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
    compose("stop", "model-service");

    try {
      await page.getByRole("button", { name: "Run what-if" }).click();
      await expect(page.locator(".status-error")).toContainText("Prediction is temporarily unavailable");
      await expect(page.getByText("50 total records")).toBeVisible();
      await expect(page.getByRole("heading", { name: "Market summary" })).toBeVisible();
    } finally {
      compose("start", "model-service");
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
