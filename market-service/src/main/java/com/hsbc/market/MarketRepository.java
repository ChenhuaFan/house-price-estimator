package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketRepository implements MarketDataRepository {
    private static final Set<String> SORT_FIELDS = Set.of(
            "source_id", "price", "square_footage", "bedrooms", "bathrooms",
            "year_built", "lot_size", "distance_to_city_center", "school_rating");

    private final JdbcTemplate jdbc;

    public MarketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DatasetVersion resolveVersion(UUID requested) {
        String sql = """
                SELECT dataset_version_id, version_name, content_sha256, row_count
                  FROM catalog.dataset_versions
                 WHERE %s
                """.formatted(requested == null ? "is_active" : "dataset_version_id = ?");
        List<DatasetVersion> versions = requested == null
                ? jdbc.query(sql, this::mapVersion)
                : jdbc.query(sql, this::mapVersion, requested);
        if (versions.size() != 1) {
            throw new IllegalArgumentException("dataset version was not found");
        }
        return versions.getFirst();
    }

    @Override
    public MarketAnalysis analyse(DatasetVersion version, MarketQuery query) {
        QueryParts parts = queryParts(version.id(), query);
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM catalog.housing_records " + parts.where(),
                Long.class,
                parts.parameters().toArray());
        Aggregates aggregates = jdbc.queryForObject(
                """
                SELECT count(*) AS count,
                       coalesce(min(price), 0) AS minimum_price,
                       coalesce(max(price), 0) AS maximum_price,
                       coalesce(avg(price), 0) AS average_price,
                       coalesce(avg(square_footage), 0) AS average_square_footage
                  FROM catalog.housing_records
                """ + parts.where(),
                (result, row) -> new Aggregates(
                        result.getLong("count"),
                        money(result.getBigDecimal("minimum_price")),
                        money(result.getBigDecimal("maximum_price")),
                        money(result.getBigDecimal("average_price")),
                        result.getBigDecimal("average_square_footage").setScale(2, RoundingMode.HALF_UP)),
                parts.parameters().toArray());
        String sort = sortColumn(query.normalizedSort());
        String direction = query.normalizedDirection().equals("desc") ? "DESC" : "ASC";
        List<Object> pageParameters = new ArrayList<>(parts.parameters());
        pageParameters.add(query.size());
        pageParameters.add((long) query.page() * query.size());
        List<PropertyRecord> records = jdbc.query(
                """
                SELECT source_id, square_footage, bedrooms, bathrooms, year_built,
                       lot_size, distance_to_city_center, school_rating, price
                  FROM catalog.housing_records
                """ + parts.where() + " ORDER BY " + sort + " " + direction
                        + (sort.equals("source_id") ? "" : ", source_id ASC") + " LIMIT ? OFFSET ?",
                this::mapRecord,
                pageParameters.toArray());

        Map<String, List<ChartPoint>> charts = new LinkedHashMap<>();
        charts.put("average_price_by_bedrooms", jdbc.query(
                """
                SELECT bedrooms::text AS label, avg(price) AS value
                  FROM catalog.housing_records
                """ + parts.where() + " GROUP BY bedrooms ORDER BY bedrooms",
                (result, row) -> new ChartPoint(result.getString("label"), money(result.getBigDecimal("value"))),
                parts.parameters().toArray()));
        charts.put("property_count_by_school_band", jdbc.query(
                """
                SELECT CASE
                         WHEN school_rating < 6 THEN '<6'
                         WHEN school_rating < 8 THEN '6-7.999'
                         ELSE '8+'
                       END AS label,
                       count(*)::numeric AS value
                  FROM catalog.housing_records
                """ + parts.where() + " GROUP BY label ORDER BY min(school_rating)",
                (result, row) -> new ChartPoint(result.getString("label"), result.getBigDecimal("value")),
                parts.parameters().toArray()));
        return new MarketAnalysis(
                version.id(), version.contentSha256(), count, query.page(), query.size(),
                records, aggregates, charts);
    }

    @Override
    public List<PropertyRecord> allMatching(DatasetVersion version, MarketQuery query) {
        QueryParts parts = queryParts(version.id(), query);
        String sort = sortColumn(query.normalizedSort());
        String direction = query.normalizedDirection().equals("desc") ? "DESC" : "ASC";
        return jdbc.query(
                """
                SELECT source_id, square_footage, bedrooms, bathrooms, year_built,
                       lot_size, distance_to_city_center, school_rating, price
                  FROM catalog.housing_records
                """ + parts.where() + " ORDER BY " + sort + " " + direction
                        + (sort.equals("source_id") ? "" : ", source_id ASC") + " LIMIT 5000",
                this::mapRecord,
                parts.parameters().toArray());
    }

    private QueryParts queryParts(UUID version, MarketQuery query) {
        StringBuilder where = new StringBuilder(" WHERE dataset_version_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(version);
        addRange(where, parameters, "price", query.priceMin(), query.priceMax());
        addRange(where, parameters, "square_footage", query.squareFootageMin(), query.squareFootageMax());
        addRange(where, parameters, "bedrooms", query.bedroomsMin(), query.bedroomsMax());
        addRange(where, parameters, "bathrooms", query.bathroomsMin(), query.bathroomsMax());
        addRange(where, parameters, "year_built", query.yearBuiltMin(), query.yearBuiltMax());
        addRange(where, parameters, "lot_size", query.lotSizeMin(), query.lotSizeMax());
        addRange(where, parameters, "distance_to_city_center", query.distanceMin(), query.distanceMax());
        addRange(where, parameters, "school_rating", query.schoolRatingMin(), query.schoolRatingMax());
        return new QueryParts(where.toString(), parameters);
    }

    private <T extends Comparable<? super T>> void addRange(
            StringBuilder where, List<Object> parameters, String column, T minimum, T maximum) {
        if (minimum != null) {
            where.append(" AND ").append(column).append(" >= ?");
            parameters.add(minimum);
        }
        if (maximum != null) {
            where.append(" AND ").append(column).append(" <= ?");
            parameters.add(maximum);
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(column + " minimum cannot exceed maximum");
        }
    }

    private String sortColumn(String requested) {
        if (!SORT_FIELDS.contains(requested)) {
            throw new IllegalArgumentException("unsupported sort field: " + requested);
        }
        return requested;
    }

    private DatasetVersion mapVersion(ResultSet result, int row) throws SQLException {
        return new DatasetVersion(
                result.getObject("dataset_version_id", UUID.class),
                result.getString("version_name"),
                result.getString("content_sha256").strip(),
                result.getInt("row_count"));
    }

    private PropertyRecord mapRecord(ResultSet result, int row) throws SQLException {
        return new PropertyRecord(
                result.getLong("source_id"),
                result.getLong("square_footage"),
                result.getInt("bedrooms"),
                result.getBigDecimal("bathrooms"),
                result.getInt("year_built"),
                result.getLong("lot_size"),
                result.getBigDecimal("distance_to_city_center"),
                result.getBigDecimal("school_rating"),
                money(result.getBigDecimal("price")));
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record QueryParts(String where, List<Object> parameters) {}
}
