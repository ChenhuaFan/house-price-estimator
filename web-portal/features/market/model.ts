/** Contract-backed adapters between URL state, API wire types, and market view models. */
import type { components } from "@/lib/api/market-v2.generated";
import type {
  ChartPoint,
  MarketOverview,
  MarketPageModel,
  MarketQueryState,
  PropertyRecord,
} from "@/lib/api/types";

export type MarketFilters = components["schemas"]["MarketFilters"];
export type PropertyPageWire = components["schemas"]["PropertyPage"];
export type StatisticsQueryWire = components["schemas"]["StatisticsQuery"];
export type StatisticsResponseWire = components["schemas"]["StatisticsResponse"];
export type ExportCreateRequestWire = components["schemas"]["ExportCreateRequest"];
export type ExportJobWire = components["schemas"]["ExportJob"];
export type WhatIfResponseWire = components["schemas"]["WhatIfResponse"];

export const marketFilterKeys = [
  "price_gte", "price_lte", "square_footage_gte", "square_footage_lte",
  "bedrooms_gte", "bedrooms_lte", "bathrooms_gte", "bathrooms_lte",
  "year_built_gte", "year_built_lte", "lot_size_gte", "lot_size_lte",
  "distance_to_city_center_gte", "distance_to_city_center_lte",
  "school_rating_gte", "school_rating_lte",
] as const;

type MarketFilterKey = (typeof marketFilterKeys)[number];

const integerFilterBounds: Partial<Record<MarketFilterKey, { minimum: string; maximum: string }>> = {
  square_footage_gte: { minimum: "1", maximum: "9223372036854775807" },
  square_footage_lte: { minimum: "1", maximum: "9223372036854775807" },
  bedrooms_gte: { minimum: "0", maximum: "2147483647" },
  bedrooms_lte: { minimum: "0", maximum: "2147483647" },
  year_built_gte: { minimum: "1", maximum: "9999" },
  year_built_lte: { minimum: "1", maximum: "9999" },
  lot_size_gte: { minimum: "0", maximum: "9223372036854775807" },
  lot_size_lte: { minimum: "0", maximum: "9223372036854775807" },
};

const decimalFilterFormats: Partial<Record<MarketFilterKey, { scale: number; maximumWholeDigits: number }>> = {
  price_gte: { scale: 2, maximumWholeDigits: 16 },
  price_lte: { scale: 2, maximumWholeDigits: 16 },
  bathrooms_gte: { scale: 1, maximumWholeDigits: 11 },
  bathrooms_lte: { scale: 1, maximumWholeDigits: 11 },
  distance_to_city_center_gte: { scale: 3, maximumWholeDigits: 9 },
  distance_to_city_center_lte: { scale: 3, maximumWholeDigits: 9 },
  school_rating_gte: { scale: 3, maximumWholeDigits: 9 },
  school_rating_lte: { scale: 3, maximumWholeDigits: 9 },
};

function normalizedFilterValue(key: MarketFilterKey, value: string): string | undefined {
  const integerBounds = integerFilterBounds[key];
  if (integerBounds) {
    if (!/^\d+$/.test(value)) return undefined;
    const normalized = value.replace(/^0+(?=\d)/, "");
    const atLeastMinimum = normalized.length > integerBounds.minimum.length
      || (normalized.length === integerBounds.minimum.length && normalized >= integerBounds.minimum);
    const atMostMaximum = normalized.length < integerBounds.maximum.length
      || (normalized.length === integerBounds.maximum.length && normalized <= integerBounds.maximum);
    return atLeastMinimum && atMostMaximum
      ? normalized
      : undefined;
  }

  const format = decimalFilterFormats[key];
  const match = /^(\d+)(?:\.(\d+))?$/.exec(value);
  if (!format || !match) return undefined;
  const whole = match[1]!.replace(/^0+(?=\d)/, "");
  const fraction = match[2] ?? "";
  if (whole.length > format.maximumWholeDigits || fraction.length > format.scale) return undefined;
  return `${whole}.${fraction.padEnd(format.scale, "0")}`;
}

const allowedSort = new Set([
  "source_id", "price", "square_footage", "bedrooms", "bathrooms",
  "year_built", "lot_size", "distance_to_city_center", "school_rating",
]);

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** Parses untrusted search parameters into the bounded state accepted by Market API v2. */
export function parseMarketQuery(
  input: Record<string, string | string[] | undefined>,
): MarketQueryState {
  const query: MarketQueryState = { sort: "source_id:asc", limit: 20 };
  for (const key of marketFilterKeys) {
    const value = first(input[key]);
    if (!value || value.length > 64) continue;
    const normalized = normalizedFilterValue(key, value);
    if (normalized !== undefined) query[key] = normalized;
  }
  const requestedSort = first(input.sort);
  if (requestedSort) {
    const [field, direction] = requestedSort.split(":");
    if (allowedSort.has(field) && (direction === "asc" || direction === "desc")) {
      query.sort = `${field}:${direction}`;
    }
  }

  const requestedLimit = Number(first(input.limit));
  if (Number.isInteger(requestedLimit) && requestedLimit >= 1 && requestedLimit <= 200) {
    query.limit = requestedLimit;
  }

  const cursor = first(input.cursor);
  if (cursor && cursor.length <= 2048) query.cursor = cursor;

  const dataset = first(input.dataset_version_id);
  if (dataset && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(dataset)) {
    query.dataset_version_id = dataset;
  }
  return query;
}

/** Serializes committed market state without interpreting the opaque cursor. */
export function queryToSearchParams(query: MarketQueryState): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of marketFilterKeys) {
    const value = query[key];
    if (value) params.set(key, value);
  }
  params.set("sort", query.sort);
  params.set("limit", String(query.limit));
  if (query.cursor) params.set("cursor", query.cursor);
  if (query.dataset_version_id) params.set("dataset_version_id", query.dataset_version_id);
  return params;
}

/** Converts flat URL range keys into the nested OpenAPI filter shape. */
export function queryToFilters(query: MarketQueryState): MarketFilters {
  const filters: MarketFilters = {};
  const pairs = [
    ["price", "price_gte", "price_lte"],
    ["square_footage", "square_footage_gte", "square_footage_lte"],
    ["bedrooms", "bedrooms_gte", "bedrooms_lte"],
    ["bathrooms", "bathrooms_gte", "bathrooms_lte"],
    ["year_built", "year_built_gte", "year_built_lte"],
    ["lot_size", "lot_size_gte", "lot_size_lte"],
    ["distance_to_city_center", "distance_to_city_center_gte", "distance_to_city_center_lte"],
    ["school_rating", "school_rating_gte", "school_rating_lte"],
  ] as const;
  for (const [field, minimumKey, maximumKey] of pairs) {
    const minimum = query[minimumKey];
    const maximum = query[maximumKey];
    if (!minimum && !maximum) continue;
    if (["square_footage", "bedrooms", "year_built", "lot_size"].includes(field)) {
      filters[field] = {
        ...(minimum ? { gte: Number(minimum) } : {}),
        ...(maximum ? { lte: Number(maximum) } : {}),
      } as never;
    } else {
      filters[field] = {
        ...(minimum ? { gte: minimum } : {}),
        ...(maximum ? { lte: maximum } : {}),
      } as never;
    }
  }
  return filters;
}

/** Builds the immutable dataset/filter/sort selection reused by export requests. */
export function marketSelection(model: MarketPageModel): components["schemas"]["MarketSelection"] {
  const [field, direction] = model.query.sort.split(":") as [
    components["schemas"]["SortField"],
    components["schemas"]["SortDirection"],
  ];
  return {
    scope: {
      dataset_version_id: model.datasetVersion.id,
      filters: queryToFilters(model.query),
    },
    sort: [{ field, direction }],
  };
}

/** Maps exact API strings into the camel-cased record model consumed by the table. */
export function mapPropertyPage(page: PropertyPageWire): PropertyRecord[] {
  return page.items.map((item) => ({
    id: item.source_id,
    squareFootage: item.square_footage,
    bedrooms: item.bedrooms,
    bathrooms: item.bathrooms,
    yearBuilt: item.year_built,
    lotSize: item.lot_size,
    distanceToCityCenter: item.distance_to_city_center,
    schoolRating: item.school_rating,
    price: item.price,
  }));
}

function metric(response: StatisticsResponseWire, id: string): string | null {
  return response.groups[0]?.values.find((value) => value.metric_id === id)?.value ?? null;
}

/** Extracts dashboard summary metrics while preserving decimal strings. */
export function mapOverview(response: StatisticsResponseWire): MarketOverview {
  return {
    count: metric(response, "count") ?? "0",
    minimumPrice: metric(response, "minimum_price"),
    maximumPrice: metric(response, "maximum_price"),
    averagePrice: metric(response, "average_price"),
    averageSquareFootage: metric(response, "average_square_footage"),
    priceStandardDeviation: metric(response, "price_stddev"),
  };
}

/** Adapts bounded values for charts while retaining their exact text labels. */
export function mapChart(response: StatisticsResponseWire, metricId: string): ChartPoint[] {
  return response.groups.map((group) => {
    const rawValue = group.values.find((value) => value.metric_id === metricId)?.value ?? "0";
    const key = group.key.label ?? group.key.exact_value ?? ([group.key.gte, group.key.lt].filter(Boolean).join("–") || "Other");
    return { label: key, value: Number(rawValue), rawValue };
  });
}
