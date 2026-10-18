package com.hsbc.market.api.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class V1Models {
  private V1Models() {}

  public record PropertyRecord(
      long id,
      long squareFootage,
      int bedrooms,
      BigDecimal bathrooms,
      int yearBuilt,
      long lotSize,
      BigDecimal distanceToCityCenter,
      BigDecimal schoolRating,
      BigDecimal price) {}

  public record Aggregates(
      long count,
      BigDecimal minimumPrice,
      BigDecimal maximumPrice,
      BigDecimal averagePrice,
      BigDecimal averageSquareFootage) {}

  public record ChartPoint(String label, BigDecimal value) {}

  public record MarketAnalysis(
      UUID datasetVersionId,
      String datasetContentSha256,
      long matchingCount,
      int page,
      int size,
      List<PropertyRecord> records,
      Aggregates aggregates,
      Map<String, List<ChartPoint>> charts) {}

  public record PropertyInput(
      @NotNull @Min(1) @JsonProperty("square_footage") Long squareFootage,
      @NotNull @Min(0) Integer bedrooms,
      @NotNull @DecimalMin("0.5") BigDecimal bathrooms,
      @NotNull @Min(1) @Max(9999) @JsonProperty("year_built") Integer yearBuilt,
      @NotNull @Min(0) @JsonProperty("lot_size") Long lotSize,
      @NotNull @DecimalMin("0") @JsonProperty("distance_to_city_center")
          BigDecimal distanceToCityCenter,
      @NotNull @JsonProperty("school_rating") BigDecimal schoolRating) {}

  public record Warning(
      String field,
      @JsonProperty("submitted_value") String submittedValue,
      @JsonProperty("advisory_min") String advisoryMin,
      @JsonProperty("advisory_max") String advisoryMax,
      String reason) {}

  public record Prediction(
      @JsonProperty("predicted_price") String predictedPrice,
      String currency,
      @JsonProperty("model_version") String modelVersion,
      List<Warning> warnings) {}

  public record WhatIfResponse(PropertyInput inputs, Prediction prediction) {}
}
