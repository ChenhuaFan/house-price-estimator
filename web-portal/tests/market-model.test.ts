import { afterEach, describe, expect, it, vi } from "vitest";
import { getMarketPageModel } from "@/features/market/server";
import { parseMarketQuery, queryToFilters, queryToSearchParams } from "@/features/market/model";

describe("market v2 page model", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("allow-lists URL state without parsing opaque cursors", () => {
    const query = parseMarketQuery({ price_gte: "100000.00", sort: "price:desc", cursor: "opaque/value", limit: "500", unknown: "x" });
    expect(query).toEqual({ price_gte: "100000.00", sort: "price:desc", cursor: "opaque/value", limit: 20 });
    expect(queryToFilters(query)).toEqual({ price: { gte: "100000.00" } });
  });

  it("normalizes browser-friendly filters to the fixed-scale query contract", () => {
    const query = parseMarketQuery({
      price_gte: "250000",
      bathrooms_lte: "2.5",
      distance_to_city_center_gte: "3",
      school_rating_lte: "8.25",
      bedrooms_gte: "03",
    });

    expect(query).toMatchObject({
      price_gte: "250000.00",
      bathrooms_lte: "2.5",
      distance_to_city_center_gte: "3.000",
      school_rating_lte: "8.250",
      bedrooms_gte: "3",
    });
    expect(queryToSearchParams(query).toString()).toContain("price_gte=250000.00");
  });

  it("drops filter values that cannot be represented by the market contract", () => {
    const query = parseMarketQuery({
      square_footage_gte: "0",
      bedrooms_gte: "2.5",
      year_built_lte: "10000",
      price_gte: "1.234",
      school_rating_gte: "-1",
    });

    expect(query).toEqual({ sort: "source_id:asc", limit: 20 });
  });

  it("assembles properties and statistics from the same business-data revision", async () => {
    const propertyPage = {
      data_revision: 7,
      query_fingerprint: `sha256:${"b".repeat(64)}`,
      items: [{ source_id: 1, square_footage: 1800, bedrooms: 3, bathrooms: "2.5", year_built: 2005, lot_size: 6500, distance_to_city_center: "4.125", school_rating: "8.500", price: "250000.00" }],
      page: { limit: 20, has_more: false },
    };
    const stats = [
      { key: {}, values: [
        { metric_id: "count", value: "1" }, { metric_id: "minimum_price", value: "250000.00" },
        { metric_id: "maximum_price", value: "250000.00" }, { metric_id: "average_price", value: "250000.00" },
        { metric_id: "average_square_footage", value: "1800" }, { metric_id: "price_stddev", value: "0" },
      ] },
    ];
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(propertyPage), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data_revision: 7, query_fingerprint: "x", groups: stats }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data_revision: 7, query_fingerprint: "y", groups: [{ key: { exact_value: "3" }, values: [{ metric_id: "average_price", value: "250000.00" }] }] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data_revision: 7, query_fingerprint: "z", groups: [{ key: { gte: "8", lt: "10" }, values: [{ metric_id: "property_count", value: "1" }] }] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const model = await getMarketPageModel({ price_gte: "100000.00" });
    expect(model.dataRevision).toBe(7);
    expect(model.records[0]?.price).toBe("250000.00");
    for (const call of fetchMock.mock.calls.slice(1)) {
      expect(JSON.parse(call[1]?.body as string).scope).toEqual({
        filters: { price: { gte: "100000.00" } },
      });
    }
  });
});
