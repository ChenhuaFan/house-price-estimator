export const propertyFieldNames = [
  "square_footage",
  "bedrooms",
  "bathrooms",
  "year_built",
  "lot_size",
  "distance_to_city_center",
  "school_rating",
] as const;

export type PropertyFieldName = (typeof propertyFieldNames)[number];

export interface PropertyInput {
  square_footage: number;
  bedrooms: number;
  bathrooms: number;
  year_built: number;
  lot_size: number;
  distance_to_city_center: number;
  school_rating: number;
}

export interface AdvisoryWarning {
  field: string;
  submitted_value: string;
  advisory_min: string | null;
  advisory_max: string | null;
  reason: string;
}

export interface Estimate {
  estimate_id: string;
  request_id: string;
  created_at: string;
  inputs: PropertyInput;
  predicted_price: string;
  currency: "USD";
  model_version: string;
  warnings: AdvisoryWarning[];
}

export interface HistoryPage {
  items: Estimate[];
  next_cursor: string | null;
}

export interface PropertyRecord {
  id: number;
  squareFootage: number;
  bedrooms: number;
  bathrooms: number;
  yearBuilt: number;
  lotSize: number;
  distanceToCityCenter: number;
  schoolRating: number;
  price: number;
}

export interface ChartPoint {
  label: string;
  value: number;
}

export interface MarketAnalysis {
  datasetVersionId: string;
  datasetContentSha256: string;
  matchingCount: number;
  page: number;
  size: number;
  records: PropertyRecord[];
  aggregates: {
    count: number;
    minimumPrice: number;
    maximumPrice: number;
    averagePrice: number;
    averageSquareFootage: number;
  };
  charts: {
    average_price_by_bedrooms: ChartPoint[];
    property_count_by_school_band: ChartPoint[];
  };
}

export interface Prediction {
  predicted_price: string;
  currency: "USD";
  model_version: string;
  warnings: AdvisoryWarning[];
}

export interface WhatIfResponse {
  inputs: PropertyInput;
  prediction: Prediction;
}

export interface ApiErrorBody {
  error?: {
    code?: string;
    message?: string;
    fields?: Array<{ field: string; message: string }>;
  };
  correlation_id?: string;
}
