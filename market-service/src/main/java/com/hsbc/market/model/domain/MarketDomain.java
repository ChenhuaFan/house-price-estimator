package com.hsbc.market.model.domain;

import com.hsbc.market.exception.MarketException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Namespace for immutable market selections, records, aggregates, and predictions. */
public final class MarketDomain {
  private MarketDomain() {}

  public record DatasetVersion(UUID id, String contentSha256) {}

  public record Range<T extends Comparable<? super T>>(T gte, T lte) {
    public Range {
      if (gte != null && lte != null && gte.compareTo(lte) > 0) {
        throw MarketException.validation("INVALID_FILTER", "range minimum cannot exceed maximum");
      }
    }

    public static <T extends Comparable<? super T>> Range<T> of(T gte, T lte) {
      return gte == null && lte == null ? null : new Range<>(gte, lte);
    }
  }

  public record Filters(
      Range<BigDecimal> price,
      Range<Long> squareFootage,
      Range<Integer> bedrooms,
      Range<BigDecimal> bathrooms,
      Range<Integer> yearBuilt,
      Range<Long> lotSize,
      Range<BigDecimal> distanceToCityCenter,
      Range<BigDecimal> schoolRating) {
    public static Filters empty() {
      return new Filters(null, null, null, null, null, null, null, null);
    }
  }

  public enum SortField {
    SOURCE_ID("source_id"),
    PRICE("price"),
    SQUARE_FOOTAGE("square_footage"),
    BEDROOMS("bedrooms"),
    BATHROOMS("bathrooms"),
    YEAR_BUILT("year_built"),
    LOT_SIZE("lot_size"),
    DISTANCE_TO_CITY_CENTER("distance_to_city_center"),
    SCHOOL_RATING("school_rating");

    private final String apiName;

    SortField(String apiName) {
      this.apiName = apiName;
    }

    public String apiName() {
      return apiName;
    }

    public static SortField fromApi(String value) {
      for (SortField field : values()) {
        if (field.apiName.equals(value)) return field;
      }
      throw MarketException.validation("INVALID_SORT", "unsupported sort field: " + value);
    }
  }

  public enum SortDirection {
    ASC,
    DESC
  }

  public record Sort(SortField field, SortDirection direction) {
    public Sort {
      field = field == null ? SortField.SOURCE_ID : field;
      direction = direction == null ? SortDirection.ASC : direction;
    }

    public static Sort parse(String value) {
      String normalized = value == null || value.isBlank() ? "source_id:asc" : value;
      String[] parts = normalized.split(":", -1);
      if (parts.length != 2) {
        throw MarketException.validation("INVALID_SORT", "sort must use field:direction");
      }
      SortDirection direction =
          switch (parts[1]) {
            case "asc" -> SortDirection.ASC;
            case "desc" -> SortDirection.DESC;
            default ->
                throw MarketException.validation(
                    "INVALID_SORT", "sort direction must be asc or desc");
          };
      return new Sort(SortField.fromApi(parts[0]), direction);
    }
  }

  public record Selection(UUID requestedDatasetVersionId, Filters filters, Sort sort) {
    public Selection {
      filters = filters == null ? Filters.empty() : filters;
      sort = sort == null ? new Sort(SortField.SOURCE_ID, SortDirection.ASC) : sort;
    }
  }

  public record ResolvedSelection(
      DatasetVersion datasetVersion, Filters filters, Sort sort, String queryFingerprint) {}

  public record PropertyRecord(
      long sourceId,
      long squareFootage,
      int bedrooms,
      BigDecimal bathrooms,
      int yearBuilt,
      long lotSize,
      BigDecimal distanceToCityCenter,
      BigDecimal schoolRating,
      BigDecimal price) {
    public String sortValue(SortField field) {
      return switch (field) {
        case SOURCE_ID -> Long.toString(sourceId);
        case PRICE -> decimal(price);
        case SQUARE_FOOTAGE -> Long.toString(squareFootage);
        case BEDROOMS -> Integer.toString(bedrooms);
        case BATHROOMS -> decimal(bathrooms);
        case YEAR_BUILT -> Integer.toString(yearBuilt);
        case LOT_SIZE -> Long.toString(lotSize);
        case DISTANCE_TO_CITY_CENTER -> decimal(distanceToCityCenter);
        case SCHOOL_RATING -> decimal(schoolRating);
      };
    }

    private static String decimal(BigDecimal value) {
      return value.stripTrailingZeros().toPlainString();
    }
  }

  public record CursorPosition(String sortValue, long sourceId) {}

  public record PropertyPage(
      DatasetVersion datasetVersion,
      String queryFingerprint,
      List<PropertyRecord> items,
      int limit,
      boolean hasMore,
      String nextCursor) {}

  public enum MetricMethod {
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX,
    STDDEV_POP
  }

  public enum MetricField {
    ALL("*"),
    SOURCE_ID("source_id"),
    PRICE("price"),
    SQUARE_FOOTAGE("square_footage"),
    BEDROOMS("bedrooms"),
    BATHROOMS("bathrooms"),
    YEAR_BUILT("year_built"),
    LOT_SIZE("lot_size"),
    DISTANCE_TO_CITY_CENTER("distance_to_city_center"),
    SCHOOL_RATING("school_rating");

    private final String apiName;

    MetricField(String apiName) {
      this.apiName = apiName;
    }

    public String apiName() {
      return apiName;
    }
  }

  public record Metric(String id, MetricMethod method, MetricField field) {}

  public enum GroupField {
    PRICE,
    SQUARE_FOOTAGE,
    BEDROOMS,
    BATHROOMS,
    YEAR_BUILT,
    LOT_SIZE,
    DISTANCE_TO_CITY_CENTER,
    SCHOOL_RATING
  }

  public record GroupBy(GroupField field, BigDecimal fixedWidth) {}

  public record GroupOrder(String by, SortDirection direction) {}

  public record StatisticsCommand(
      UUID requestedDatasetVersionId,
      Filters filters,
      List<Metric> metrics,
      GroupBy groupBy,
      GroupOrder order,
      int limit) {}

  public record StatisticsGroupKey(
      GroupField field, BigDecimal exactValue, BigDecimal gte, BigDecimal lt, String label) {
    public static StatisticsGroupKey empty() {
      return new StatisticsGroupKey(null, null, null, null, null);
    }
  }

  public record StatisticsGroup(StatisticsGroupKey key, Map<String, BigDecimal> values) {}

  public record StatisticsResult(
      DatasetVersion datasetVersion, String queryFingerprint, List<StatisticsGroup> groups) {}

  public record PropertyFeatures(
      long squareFootage,
      int bedrooms,
      BigDecimal bathrooms,
      int yearBuilt,
      long lotSize,
      BigDecimal distanceToCityCenter,
      BigDecimal schoolRating) {
    public PropertyFeatures {
      if (squareFootage < 1
          || bedrooms < 0
          || bathrooms == null
          || bathrooms.compareTo(new BigDecimal("0.5")) < 0
          || yearBuilt < 1
          || yearBuilt > 9999
          || lotSize < 0
          || distanceToCityCenter == null
          || distanceToCityCenter.signum() < 0
          || schoolRating == null) {
        throw MarketException.validation(
            "VALIDATION_ERROR", "property inputs are outside the supported domain");
      }
      if (bathrooms.scale() > 1 || bathrooms.remainder(new BigDecimal("0.5")).signum() != 0) {
        throw MarketException.validation(
            "VALIDATION_ERROR", "bathrooms must be in increments of 0.5");
      }
      if (distanceToCityCenter.scale() > 3 || schoolRating.scale() > 3) {
        throw MarketException.validation(
            "VALIDATION_ERROR", "distance and school rating allow at most three decimals");
      }
    }
  }

  public record AdvisoryWarning(
      String field,
      String submittedValue,
      String advisoryMinimum,
      String advisoryMaximum,
      String message) {}

  public record Prediction(
      BigDecimal predictedPrice,
      String currency,
      String modelVersion,
      List<AdvisoryWarning> warnings) {}

  public record WhatIfResult(PropertyFeatures inputs, Prediction prediction) {}

  public record ExportAudit(Instant generatedAt, long rows, long bytes, String sha256) {}
}
