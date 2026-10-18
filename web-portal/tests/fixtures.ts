import type { Estimate, MarketAnalysis, PropertyInput, WhatIfResponse } from "@/lib/api/types";

export const propertyInput: PropertyInput = {
  square_footage: 1800,
  bedrooms: 3,
  bathrooms: 2.5,
  year_built: 2005,
  lot_size: 6500,
  distance_to_city_center: 4.125,
  school_rating: 8.5,
};

export function estimate(id = "11111111-1111-4111-8111-111111111111"): Estimate {
  return {
    estimate_id: id,
    request_id: "22222222-2222-4222-8222-222222222222",
    created_at: "2026-10-18T10:00:00Z",
    inputs: propertyInput,
    predicted_price: "237354.67",
    currency: "USD",
    model_version: "housing-linear-test",
  };
}

export const whatIfResponse: WhatIfResponse = {
  inputs: propertyInput,
  prediction: {
    predicted_price: "237354.67",
    currency: "USD",
    model_version: "housing-linear-test",
    warnings: [],
  },
};

export const marketAnalysis: MarketAnalysis = {
  datasetVersionId: "00000000-0000-4000-8000-000000000001",
  datasetContentSha256: "a".repeat(64),
  matchingCount: 2,
  page: 0,
  size: 20,
  records: [
    {
      id: 1,
      squareFootage: 1800,
      bedrooms: 3,
      bathrooms: 2.5,
      yearBuilt: 2005,
      lotSize: 6500,
      distanceToCityCenter: 4.125,
      schoolRating: 8.5,
      price: 250000,
    },
  ],
  aggregates: {
    count: 2,
    minimumPrice: 200000,
    maximumPrice: 300000,
    averagePrice: 250000,
    averageSquareFootage: 1800,
  },
  charts: {
    average_price_by_bedrooms: [{ label: "3", value: 250000 }],
    property_count_by_school_band: [{ label: "8+", value: 2 }],
  },
};
