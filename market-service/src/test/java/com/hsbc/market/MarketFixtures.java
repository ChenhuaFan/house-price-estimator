package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MarketFixtures {
    static final UUID DATASET_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final String DATASET_HASH = "a".repeat(64);

    private MarketFixtures() {}

    static DatasetVersion version() {
        return new DatasetVersion(DATASET_ID, "housing-v1", DATASET_HASH, 2);
    }

    static MarketQuery query() {
        return new MarketQuery(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "source_id", "asc", 0, 20, DATASET_ID);
    }

    static PropertyRecord record(long id, BigDecimal price) {
        return new PropertyRecord(
                id,
                1800 + id,
                3,
                new BigDecimal("2.5"),
                2005,
                6500,
                new BigDecimal("4.125"),
                new BigDecimal("8.500"),
                price);
    }

    static PropertyInput input() {
        return new PropertyInput(
                1800L,
                3,
                new BigDecimal("2.5"),
                2005,
                6500L,
                new BigDecimal("4.125"),
                new BigDecimal("8.500"));
    }

    static Prediction prediction() {
        return new Prediction("250000.00", "USD", "housing-linear-test", List.of());
    }

    static MarketAnalysis analysis() {
        List<PropertyRecord> records = List.of(
                record(1, new BigDecimal("250000.00")),
                record(2, new BigDecimal("275000.00")));
        Map<String, List<ChartPoint>> charts = new LinkedHashMap<>();
        charts.put(
                "average_price_by_bedrooms",
                List.of(new ChartPoint("3", new BigDecimal("262500.00"))));
        charts.put(
                "property_count_by_school_band",
                List.of(new ChartPoint("8+", new BigDecimal("2"))));
        return new MarketAnalysis(
                DATASET_ID,
                DATASET_HASH,
                2,
                0,
                20,
                records,
                new Aggregates(
                        2,
                        new BigDecimal("250000.00"),
                        new BigDecimal("275000.00"),
                        new BigDecimal("262500.00"),
                        new BigDecimal("1801.50")),
                charts);
    }
}
