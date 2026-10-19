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
  advisory_minimum: string;
  advisory_maximum: string;
  message: string;
}

export interface Estimate {
  estimate_id: string;
  request_id: string;
  created_at: string;
  inputs: PropertyInput;
  predicted_price: string;
  currency: "USD";
  model_version: string;
}

export interface HistoryPage {
  items: Estimate[];
  next_cursor: string | null;
}

export interface PropertyRecord {
  id: number;
  squareFootage: number;
  bedrooms: number;
  bathrooms: string;
  yearBuilt: number;
  lotSize: number;
  distanceToCityCenter: string;
  schoolRating: string;
  price: string;
}

export interface ChartPoint {
  label: string;
  value: number;
  rawValue: string;
}

export interface MarketOverview {
  count: string;
  minimumPrice: string | null;
  maximumPrice: string | null;
  averagePrice: string | null;
  averageSquareFootage: string | null;
  priceStandardDeviation: string | null;
}

export interface MarketPageModel {
  dataRevision: number;
  queryFingerprint: string;
  records: PropertyRecord[];
  page: {
    limit: number;
    hasMore: boolean;
    nextCursor: string | null;
  };
  overview: MarketOverview;
  charts: {
    averagePriceByBedrooms: ChartPoint[];
    propertyCountBySchoolBand: ChartPoint[];
  };
  query: MarketQueryState;
  warning: string | null;
}

export interface MarketQueryState {
  price_gte?: string;
  price_lte?: string;
  square_footage_gte?: string;
  square_footage_lte?: string;
  bedrooms_gte?: string;
  bedrooms_lte?: string;
  bathrooms_gte?: string;
  bathrooms_lte?: string;
  year_built_gte?: string;
  year_built_lte?: string;
  lot_size_gte?: string;
  lot_size_lte?: string;
  distance_to_city_center_gte?: string;
  distance_to_city_center_lte?: string;
  school_rating_gte?: string;
  school_rating_lte?: string;
  sort: string;
  limit: number;
  cursor?: string;
}

export interface Prediction {
  predicted_price: string;
  currency: "USD";
  model_version: string;
  warnings: AdvisoryWarning[];
}

export interface WhatIfResponse {
  inputs: {
    square_footage: number;
    bedrooms: number;
    bathrooms: string;
    year_built: number;
    lot_size: number;
    distance_to_city_center: string;
    school_rating: string;
  };
  prediction: Prediction;
}

export type ExportStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "EXPIRED";
export type ExportFormat = "CSV" | "PDF";

export interface ExportJob {
  export_id: string;
  status: ExportStatus;
  format: ExportFormat;
  data_revision: number;
  query_fingerprint: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
  expires_at: string;
  row_count?: number;
  detail_row_count?: number;
  details_truncated?: boolean;
  content_length?: number;
  content_sha256?: string;
  download_url?: string;
  failure_code?: string;
}

export interface ApiErrorBody {
  error?: {
    code?: string;
    message?: string;
    fields?: Array<{ field: string; message: string }>;
  };
  correlation_id?: string;
}
