package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketRepositoryIntegrationTest {
    private static MarketRepository repository;

    @BeforeAll
    static void prepareDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                requiredEnvironment("MARKET_TEST_JDBC_URL"),
                requiredEnvironment("MARKET_TEST_DATABASE_USER"),
                requiredEnvironment("MARKET_TEST_DATABASE_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA catalog");
        jdbc.execute("""
                CREATE TABLE catalog.dataset_versions (
                    dataset_version_id uuid PRIMARY KEY,
                    version_name text NOT NULL,
                    content_sha256 char(64) NOT NULL,
                    row_count integer NOT NULL,
                    is_active boolean NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE catalog.housing_records (
                    dataset_version_id uuid NOT NULL,
                    source_id bigint NOT NULL,
                    square_footage bigint NOT NULL,
                    bedrooms integer NOT NULL,
                    bathrooms numeric(12,1) NOT NULL,
                    year_built integer NOT NULL,
                    lot_size bigint NOT NULL,
                    distance_to_city_center numeric(15,3) NOT NULL,
                    school_rating numeric(15,3) NOT NULL,
                    price numeric(18,2) NOT NULL
                )
                """);
        jdbc.update(
                "INSERT INTO catalog.dataset_versions VALUES (?, ?, ?, ?, true)",
                MarketFixtures.DATASET_ID,
                "housing-v1",
                MarketFixtures.DATASET_HASH,
                4);
        insert(jdbc, 1, 1000, 2, "1.5", 1990, 4000, "10.000", "5.000", "100000.00");
        insert(jdbc, 2, 1500, 3, "2.0", 2000, 5000, "8.000", "7.000", "300000.00");
        insert(jdbc, 3, 1600, 3, "2.5", 2010, 6000, "5.000", "8.500", "300000.00");
        insert(jdbc, 4, 2000, 4, "3.0", 2020, 8000, "2.000", "9.000", "400000.00");
        repository = new MarketRepository(jdbc);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required; run the repository test through scripts/test-market.sh");
        }
        return value;
    }

    @Test
    void resolvesActiveAndExplicitImmutableVersions() {
        assertThat(repository.resolveVersion(null)).isEqualTo(
                new DatasetVersion(
                        MarketFixtures.DATASET_ID,
                        "housing-v1",
                        MarketFixtures.DATASET_HASH,
                        4));
        assertThat(repository.resolveVersion(MarketFixtures.DATASET_ID).rowCount()).isEqualTo(4);
        assertThatThrownBy(() -> repository.resolveVersion(java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void defaultAnalysisUsesOneVersionForRowsAggregatesAndCharts() {
        MarketQuery query = query(null, null, "source_id", "asc", 0, 2);

        MarketAnalysis result = repository.analyse(MarketFixtures.version(), query);

        assertThat(result.datasetVersionId()).isEqualTo(MarketFixtures.DATASET_ID);
        assertThat(result.datasetContentSha256()).isEqualTo(MarketFixtures.DATASET_HASH);
        assertThat(result.matchingCount()).isEqualTo(4);
        assertThat(result.records()).extracting(PropertyRecord::id).containsExactly(1L, 2L);
        assertThat(result.aggregates()).isEqualTo(new Aggregates(
                4,
                new BigDecimal("100000.00"),
                new BigDecimal("400000.00"),
                new BigDecimal("275000.00"),
                new BigDecimal("1525.00")));
        assertThat(result.charts().get("average_price_by_bedrooms"))
                .extracting(ChartPoint::label)
                .containsExactly("2", "3", "4");
        assertThat(result.charts().get("property_count_by_school_band"))
                .extracting(ChartPoint::value)
                .containsExactly(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("2"));
    }

    @Test
    void filtersAreInclusiveAndCanCoverEveryDomainField() {
        MarketQuery query = new MarketQuery(
                new BigDecimal("300000.00"), new BigDecimal("300000.00"),
                1500L, 1500L, 3, 3,
                new BigDecimal("2.0"), new BigDecimal("2.0"),
                2000, 2000, 5000L, 5000L,
                new BigDecimal("8.000"), new BigDecimal("8.000"),
                new BigDecimal("7.000"), new BigDecimal("7.000"),
                "price", "desc", 0, 20, MarketFixtures.DATASET_ID);

        MarketAnalysis result = repository.analyse(MarketFixtures.version(), query);

        assertThat(result.matchingCount()).isOne();
        assertThat(result.records()).extracting(PropertyRecord::id).containsExactly(2L);
        assertThat(result.aggregates().averagePrice()).isEqualByComparingTo("300000.00");
    }

    @Test
    void sortingUsesSourceIdTieBreakerAndPaginationDoesNotDrift() {
        MarketQuery firstPage = query(
                new BigDecimal("300000"), null, "price", "desc", 0, 2);
        MarketQuery secondPage = query(
                new BigDecimal("300000"), null, "price", "desc", 1, 2);

        assertThat(repository.analyse(MarketFixtures.version(), firstPage).records())
                .extracting(PropertyRecord::id)
                .containsExactly(4L, 2L);
        assertThat(repository.analyse(MarketFixtures.version(), secondPage).records())
                .extracting(PropertyRecord::id)
                .containsExactly(3L);
        assertThat(repository.allMatching(MarketFixtures.version(), firstPage))
                .extracting(PropertyRecord::id)
                .containsExactly(4L, 2L, 3L);
    }

    @Test
    void emptyResultHasStableZeroAggregates() {
        MarketAnalysis result = repository.analyse(
                MarketFixtures.version(),
                query(new BigDecimal("999999"), null, "source_id", "asc", 0, 20));

        assertThat(result.matchingCount()).isZero();
        assertThat(result.records()).isEmpty();
        assertThat(result.aggregates().count()).isZero();
        assertThat(result.aggregates().averagePrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.charts().values()).allMatch(List::isEmpty);
    }

    @Test
    void repositoryDefendsAgainstUnsafeSortAndInvertedRanges() {
        MarketQuery unsafe = query(null, null, "price; drop table", "asc", 0, 20);
        MarketQuery inverted = query(
                new BigDecimal("20"), new BigDecimal("10"), "price", "asc", 0, 20);

        assertThatThrownBy(() -> repository.analyse(MarketFixtures.version(), unsafe))
                .hasMessageContaining("unsupported sort");
        assertThatThrownBy(() -> repository.analyse(MarketFixtures.version(), inverted))
                .hasMessageContaining("minimum cannot exceed maximum");
    }

    private static void insert(
            JdbcTemplate jdbc,
            long id,
            long squareFootage,
            int bedrooms,
            String bathrooms,
            int yearBuilt,
            long lotSize,
            String distance,
            String rating,
            String price) {
        jdbc.update("""
                INSERT INTO catalog.housing_records (
                    dataset_version_id, source_id, square_footage, bedrooms, bathrooms,
                    year_built, lot_size, distance_to_city_center, school_rating, price
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                MarketFixtures.DATASET_ID,
                id,
                squareFootage,
                bedrooms,
                new BigDecimal(bathrooms),
                yearBuilt,
                lotSize,
                new BigDecimal(distance),
                new BigDecimal(rating),
                new BigDecimal(price));
    }

    private static MarketQuery query(
            BigDecimal priceMin,
            BigDecimal priceMax,
            String sort,
            String direction,
            int page,
            int size) {
        return new MarketQuery(
                priceMin, priceMax, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                sort, direction, page, size, MarketFixtures.DATASET_ID);
    }
}
