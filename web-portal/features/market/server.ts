/** Server-only loader that assembles one revision-consistent live market page model. */
import type { MarketPageModel } from "@/lib/api/types";
import { fetchJson } from "@/lib/server/request";
import {
  mapChart,
  mapOverview,
  mapPropertyPage,
  parseMarketQuery,
  queryToFilters,
  queryToSearchParams,
  type PropertyPageWire,
  type StatisticsQueryWire,
  type StatisticsResponseWire,
} from "@/features/market/model";

type SearchParams = Record<string, string | string[] | undefined>;

const overviewMetrics: StatisticsQueryWire["metrics"] = [
  { id: "count", method: "COUNT", field: "*" },
  { id: "minimum_price", method: "MIN", field: "price" },
  { id: "maximum_price", method: "MAX", field: "price" },
  { id: "average_price", method: "AVG", field: "price" },
  { id: "average_square_footage", method: "AVG", field: "square_footage" },
  { id: "price_stddev", method: "STDDEV_POP", field: "price" },
];

async function statistics(body: StatisticsQueryWire): Promise<StatisticsResponseWire> {
  const target = new URL(
    "/api/v2/market/statistics/query",
    process.env.MARKET_SERVICE_URL ?? "http://localhost:8080",
  );
  return fetchJson<StatisticsResponseWire>(target, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-ID": crypto.randomUUID() },
    body: JSON.stringify(body),
  });
}

/** Loads live properties and aggregates, retrying if a concurrent write splits revisions. */
export async function getMarketPageModel(searchParams: SearchParams): Promise<MarketPageModel> {
  const query = parseMarketQuery(searchParams);
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const propertyUrl = new URL(
      "/api/v2/market/properties",
      process.env.MARKET_SERVICE_URL ?? "http://localhost:8080",
    );
    propertyUrl.search = queryToSearchParams(query).toString();
    const propertyPage = await fetchJson<PropertyPageWire>(propertyUrl, {
      headers: { "X-Correlation-ID": crypto.randomUUID() },
    });
    const scope = { filters: queryToFilters(query) };
    const [overview, bedrooms, schoolBands] = await Promise.all([
      statistics({ scope, metrics: overviewMetrics, limit: 1 }),
      statistics({
        scope,
        metrics: [{ id: "average_price", method: "AVG", field: "price" }],
        group_by: { field: "bedrooms" },
        order: { by: "group_key", direction: "asc" },
        limit: 20,
      }),
      statistics({
        scope,
        metrics: [{ id: "property_count", method: "COUNT", field: "*" }],
        group_by: { field: "school_rating", bucket: { method: "FIXED_WIDTH", width: "2" } },
        order: { by: "group_key", direction: "asc" },
        limit: 20,
      }),
    ]);
    const revisions = [
      propertyPage.data_revision,
      overview.data_revision,
      bedrooms.data_revision,
      schoolBands.data_revision,
    ];
    if (!revisions.every((revision) => revision === propertyPage.data_revision)) continue;

    return {
      dataRevision: propertyPage.data_revision,
      queryFingerprint: propertyPage.query_fingerprint,
      records: mapPropertyPage(propertyPage),
      page: {
        limit: propertyPage.page.limit,
        hasMore: propertyPage.page.has_more,
        nextCursor: propertyPage.page.next_cursor ?? null,
      },
      overview: mapOverview(overview),
      charts: {
        averagePriceByBedrooms: mapChart(bedrooms, "average_price"),
        propertyCountBySchoolBand: mapChart(schoolBands, "property_count"),
      },
      query,
      warning: null,
    };
  }
  throw new Error("Business data changed repeatedly while assembling the market dashboard.");
}
