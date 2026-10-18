import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, test } from "@playwright/test";

const modelOrigin = process.env.MODEL_PUBLIC_ORIGIN ?? "http://127.0.0.1:8000";
const property = {
  square_footage: 1500,
  bedrooms: 3,
  bathrooms: 2,
  year_built: 1995,
  lot_size: 6000,
  distance_to_city_center: 5,
  school_rating: 8,
};

test.skip(process.env.E2E_MOCKS === "1", "The mock E2E suite covers Task 2 only.");

test.describe("Task 1 live model API", () => {
  test("exposes Swagger, health, OpenAPI, prediction, and version-matched model info", async ({ page, request }) => {
    await page.goto(`${modelOrigin}/docs`);
    await expect(page.locator("#swagger-ui")).toBeVisible();
    await expect(page.getByText("POST", { exact: true }).first()).toBeVisible();

    const health = await request.get(`${modelOrigin}/health`);
    expect(health.ok()).toBeTruthy();
    expect(await health.json()).toMatchObject({ status: "ok" });

    const contract = await request.get(`${modelOrigin}/openapi.json`);
    expect(contract.ok()).toBeTruthy();
    const openapi = await contract.json();
    expect(openapi.openapi).toMatch(/^3\.1\./);
    expect(Object.keys(openapi.paths)).toEqual(expect.arrayContaining([
      "/api/v1/predict", "/api/v1/model-info", "/health",
    ]));

    const predictionResponse = await request.post(`${modelOrigin}/api/v1/predict`, {
      data: { property },
      headers: { "X-Correlation-ID": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" },
    });
    expect(predictionResponse.ok()).toBeTruthy();
    const prediction = await predictionResponse.json();
    expect(prediction.predictions).toHaveLength(1);
    expect(prediction.predictions[0]).toMatchObject({
      currency: "USD",
      predicted_price: expect.stringMatching(/^\d+\.\d{2}$/),
      warnings: [],
    });

    const infoResponse = await request.get(`${modelOrigin}/api/v1/model-info`);
    expect(infoResponse.ok()).toBeTruthy();
    const info = await infoResponse.json();
    expect(info.model_version).toBe(prediction.predictions[0].model_version);
    expect(info.feature_names).toEqual([
      "square_footage", "bedrooms", "bathrooms", "year_built", "lot_size",
      "distance_to_city_center", "school_rating",
    ]);
    expect(info.coefficients).toHaveLength(7);
    expect(info.metrics).toMatchObject({ mae_usd: expect.any(String), rmse_usd: expect.any(String) });
  });

  test("predicts the supplied demonstration batch in order and enforces batch bounds", async ({ request }) => {
    const source = await readFile(resolve(process.cwd(), "../requirements/Test Data For Prediction.csv"), "utf8");
    const [headerLine, ...lines] = source.replace(/^\uFEFF/, "").trim().split(/\r?\n/);
    const headers = headerLine!.split(",");
    const properties = lines.map((line) => Object.fromEntries(
      line.split(",").map((value, index) => [headers[index], Number(value)]),
    ));

    const response = await request.post(`${modelOrigin}/api/v1/predict`, { data: { properties } });
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(body.predictions).toHaveLength(properties.length);
    for (const prediction of body.predictions) {
      expect(prediction.predicted_price).toMatch(/^\d+\.\d{2}$/);
    }

    for (const invalid of [[], Array.from({ length: 1001 }, () => property)]) {
      const rejected = await request.post(`${modelOrigin}/api/v1/predict`, {
        data: { properties: invalid },
      });
      expect(rejected.status()).toBe(422);
      expect(await rejected.json()).toMatchObject({ error: { code: "VALIDATION_ERROR" } });
    }
  });

  test("rejects hard-invalid inputs but predicts advisory values", async ({ request }) => {
    const invalidCases = [
      { ...property, bedrooms: 2.5 },
      { ...property, bathrooms: 1.3 },
      { ...property, distance_to_city_center: 5.1234 },
    ];
    for (const invalid of invalidCases) {
      const response = await request.post(`${modelOrigin}/api/v1/predict`, { data: { property: invalid } });
      expect(response.status()).toBe(422);
      expect(await response.json()).toMatchObject({ error: { code: "VALIDATION_ERROR" } });
    }

    const advisory = await request.post(`${modelOrigin}/api/v1/predict`, {
      data: { property: { ...property, school_rating: 11 } },
    });
    expect(advisory.ok()).toBeTruthy();
    expect((await advisory.json()).predictions[0].warnings[0]).toMatchObject({
      field: "school_rating",
      submitted_value: "11",
      advisory_min: "1",
      advisory_max: "10",
    });
  });
});
