import { expect, type Locator, type Page } from "@playwright/test";

export interface PropertyValues {
  square_footage: string;
  bedrooms: string;
  bathrooms: string;
  year_built: string;
  lot_size: string;
  distance_to_city_center: string;
  school_rating: string;
}

export const propertyA: PropertyValues = {
  square_footage: "1500",
  bedrooms: "3",
  bathrooms: "2.0",
  year_built: "1995",
  lot_size: "6000",
  distance_to_city_center: "5.0",
  school_rating: "8.0",
};

export const propertyB: PropertyValues = {
  square_footage: "2200",
  bedrooms: "4",
  bathrooms: "2.5",
  year_built: "2012",
  lot_size: "9000",
  distance_to_city_center: "12.5",
  school_rating: "9.0",
};

export async function fillProperty(
  page: Page,
  prefix: "estimate" | "what-if",
  values: PropertyValues,
): Promise<void> {
  for (const [field, value] of Object.entries(values)) {
    await page.locator(`#${prefix}-${field}`).fill(value);
  }
}

export async function clearHistory(page: Page): Promise<void> {
  const button = page.getByRole("button", { name: "Clear history" });
  if (await button.isEnabled()) {
    page.once("dialog", (dialog) => dialog.accept());
    await button.click();
    await expect(page.getByText("No estimates yet")).toBeVisible();
  }
}

export function historySection(page: Page): Locator {
  return page.getByRole("region", { name: "Previous estimates" });
}
