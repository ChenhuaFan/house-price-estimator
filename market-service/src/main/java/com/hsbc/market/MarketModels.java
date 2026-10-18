package com.hsbc.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MarketModels {
    private MarketModels() {}

    public record DatasetVersion(UUID id, String name, String contentSha256, int rowCount) {}

    public record MarketQuery(
            BigDecimal priceMin,
            BigDecimal priceMax,
            Long squareFootageMin,
            Long squareFootageMax,
            Integer bedroomsMin,
            Integer bedroomsMax,
            BigDecimal bathroomsMin,
            BigDecimal bathroomsMax,
            Integer yearBuiltMin,
            Integer yearBuiltMax,
            Long lotSizeMin,
            Long lotSizeMax,
            BigDecimal distanceMin,
            BigDecimal distanceMax,
            BigDecimal schoolRatingMin,
            BigDecimal schoolRatingMax,
            String sort,
            String direction,
            int page,
            int size,
            UUID datasetVersionId) {

        public String normalizedSort() {
            return sort == null || sort.isBlank() ? "source_id" : sort;
        }

        public String normalizedDirection() {
            return "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
        }

        public String cacheKey() {
            return String.join("|",
                    value(priceMin), value(priceMax), value(squareFootageMin), value(squareFootageMax),
                    value(bedroomsMin), value(bedroomsMax), value(bathroomsMin), value(bathroomsMax),
                    value(yearBuiltMin), value(yearBuiltMax), value(lotSizeMin), value(lotSizeMax),
                    value(distanceMin), value(distanceMax), value(schoolRatingMin), value(schoolRatingMax),
                    normalizedSort(), normalizedDirection(), Integer.toString(page), Integer.toString(size));
        }

        private static String value(Object value) {
            if (value instanceof BigDecimal decimal) {
                return decimal.stripTrailingZeros().toPlainString();
            }
            return value == null ? "" : value.toString();
        }
    }

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
            @NotNull @Min(0) @Max(Integer.MAX_VALUE) Integer bedrooms,
            @NotNull @DecimalMin("0.5") @Digits(integer = 11, fraction = 1) BigDecimal bathrooms,
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) @JsonProperty("year_built") Integer yearBuilt,
            @NotNull @Min(0) @JsonProperty("lot_size") Long lotSize,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 3)
                @JsonProperty("distance_to_city_center") BigDecimal distanceToCityCenter,
            @NotNull @Digits(integer = 12, fraction = 3)
                @JsonProperty("school_rating") BigDecimal schoolRating) {

        public void validateStep() {
            if (bathrooms.remainder(new BigDecimal("0.5")).compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("bathrooms must be in increments of 0.5");
            }
        }
    }

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

    public record PredictRequest(PropertyInput property) {}
    public record PredictResponse(List<Prediction> predictions) {}
    public record WhatIfResponse(PropertyInput inputs, Prediction prediction) {}

    public record PdfReportRequest(
            MarketQuery query,
            WhatIfResponse whatIf,
            @JsonProperty("generated_at") Instant generatedAt) {}
}
