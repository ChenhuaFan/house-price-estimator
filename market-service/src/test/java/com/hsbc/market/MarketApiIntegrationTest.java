package com.hsbc.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.model.domain.ExportDomain.Artifact;
import com.hsbc.market.model.domain.ExportDomain.Status;
import com.hsbc.market.service.ExportWorkerService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "market.export.scheduling-enabled=false",
      "market.export.root=target/test-exports",
      "market.model-service.base-url=http://127.0.0.1:1",
      "market.model-service.connect-timeout=50ms",
      "market.model-service.response-timeout=100ms"
    })
@AutoConfigureMockMvc
class MarketApiIntegrationTest {
  private static final UUID DATASET_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String[] SORT_FIELDS = {
    "source_id",
    "price",
    "square_footage",
    "bedrooms",
    "bathrooms",
    "year_built",
    "lot_size",
    "distance_to_city_center",
    "school_rating"
  };

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ExportWorkerService exportWorker;
  @Autowired private ExportJobDao exportJobs;
  @Autowired private MeterRegistry meterRegistry;

  @Autowired
  @Qualifier("querySqlSessionTemplate")
  private SqlSessionTemplate querySqlSessionTemplate;

  @Autowired
  @Qualifier("exportSqlSessionTemplate")
  private SqlSessionTemplate exportSqlSessionTemplate;

  @Autowired
  @Qualifier("marketQueryDataSource")
  private DataSource queryDataSource;

  @Autowired
  @Qualifier("marketExportDataSource")
  private DataSource exportDataSource;

  @Autowired
  @Qualifier("marketDatabaseHealthIndicator")
  private HealthIndicator marketDatabaseHealth;

  @Autowired
  @Qualifier("exportStorageHealthIndicator")
  private HealthIndicator exportStorageHealth;

  @BeforeEach
  void clearJobs() {
    new JdbcTemplate(exportDataSource).update("DELETE FROM market_ops.export_jobs");
  }

  @Test
  void bothSqlSessionTemplatesLoadTheirMapperXmlAgainstPostgresql() {
    assertThat(querySqlSessionTemplate).isNotSameAs(exportSqlSessionTemplate);
    assertThat(
            querySqlSessionTemplate
                .getConfiguration()
                .hasStatement("com.hsbc.market.mapper.query.MarketQueryMapper.findActiveVersion"))
        .isTrue();
    assertThat(
            querySqlSessionTemplate
                .getConfiguration()
                .hasStatement("com.hsbc.market.mapper.query.ExportJobMapper.claimNext"))
        .isTrue();
    assertThat(
            exportSqlSessionTemplate
                .getConfiguration()
                .hasStatement("com.hsbc.market.mapper.query.ExportJobMapper.claimNext"))
        .isFalse();
    assertThat(
            exportSqlSessionTemplate
                .getConfiguration()
                .hasStatement("com.hsbc.market.mapper.export.ExportMarketQueryMapper.stream"))
        .isTrue();
  }

  @Test
  void runtimeOpenApiDocumentIsAvailable() throws Exception {
    JsonNode document =
        json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());

    assertThat(document.path("openapi").asText()).startsWith("3.");
    assertThat(document.path("paths").has("/api/v2/market/properties")).isTrue();
  }

  @Test
  void everySortPagesWithoutDuplicatesOrGapsInBothDirections() throws Exception {
    for (String field : SORT_FIELDS) {
      for (String direction : List.of("asc", "desc")) {
        List<Long> sourceIds = pageAll(field + ':' + direction, 7);
        assertThat(sourceIds).as(field + ':' + direction).hasSize(50).doesNotHaveDuplicates();
        assertThat(new HashSet<>(sourceIds)).containsExactlyInAnyOrderElementsOf(allSourceIds());
      }
    }
  }

  @Test
  void signedCursorRejectsTamperingAndQueryReuse() throws Exception {
    JsonNode first =
        json(
            mvc.perform(
                    get("/api/v2/market/properties").param("sort", "price:asc").param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn());
    String cursor = first.at("/page/next_cursor").asText();

    mvc.perform(
            get("/api/v2/market/properties")
                .param("sort", "price:asc")
                .param("limit", "2")
                .param("cursor", cursor + "x"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("QUERY_STATE_MISMATCH"))
        .andExpect(header().exists("X-Correlation-ID"));

    mvc.perform(
            get("/api/v2/market/properties")
                .param("sort", "price:asc")
                .param("limit", "2")
                .param("bedrooms_gte", "3")
                .param("cursor", cursor))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("QUERY_STATE_MISMATCH"));
  }

  @Test
  void statisticsMatchTheCanonicalFixtureIncludingEmptyScope() throws Exception {
    String request =
        """
                {
                  "scope":{"dataset_version_id":"00000000-0000-4000-8000-000000000001"},
                  "metrics":[
                    {"id":"property_count","method":"COUNT","field":"*"},
                    {"id":"average_price","method":"AVG","field":"price"},
                    {"id":"price_deviation","method":"STDDEV_POP","field":"price"}
                  ]
                }
                """;
    JsonNode response =
        json(
            mvc.perform(
                    post("/api/v2/market/statistics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn());

    assertThat(metric(response, "property_count")).isEqualByComparingTo("50");
    assertThat(metric(response, "average_price")).isEqualByComparingTo("264600");
    assertThat(metric(response, "price_deviation"))
        .isCloseTo(
            new BigDecimal("79528.8626851912"),
            org.assertj.core.data.Offset.offset(new BigDecimal("0.0000001")));

    String emptyRequest =
        """
                {
                  "scope":{"filters":{"price":{"gte":"9999999.00"}}},
                  "metrics":[
                    {"id":"property_count","method":"COUNT","field":"*"},
                    {"id":"average_price","method":"AVG","field":"price"}
                  ]
                }
                """;
    JsonNode empty =
        json(
            mvc.perform(
                    post("/api/v2/market/statistics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyRequest))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(metric(empty, "property_count")).isEqualByComparingTo("0");
    JsonNode average = findMetric(empty, "average_price");
    assertThat(average.has("value")).isFalse();
  }

  @Test
  void everyFilterBoundAndGroupedChartMatchIndependentPostgresqlQueries() throws Exception {
    String[][] filters = {
      {"price", "price", "250000.00"},
      {"square_footage", "square_footage", "1700"},
      {"bedrooms", "bedrooms", "3"},
      {"bathrooms", "bathrooms", "2.0"},
      {"year_built", "year_built", "1995"},
      {"lot_size", "lot_size", "6500"},
      {"distance_to_city_center", "distance_to_city_center", "5.000"},
      {"school_rating", "school_rating", "7.500"}
    };
    JdbcTemplate jdbc = new JdbcTemplate(queryDataSource);
    for (String[] filter : filters) {
      for (String bound : List.of("gte", "lte")) {
        String operator = bound.equals("gte") ? ">=" : "<=";
        long expected =
            jdbc.queryForObject(
                "SELECT count(*) FROM catalog.housing_records WHERE dataset_version_id = ? AND "
                    + filter[1]
                    + " "
                    + operator
                    + " CAST(? AS numeric)",
                Long.class,
                DATASET_ID,
                filter[2]);
        JsonNode response =
            json(
                mvc.perform(
                        get("/api/v2/market/properties")
                            .param("dataset_version_id", DATASET_ID.toString())
                            .param(filter[0] + '_' + bound, filter[2])
                            .param("limit", "200"))
                    .andExpect(status().isOk())
                    .andReturn());
        assertThat(response.path("items").size())
            .as(filter[0] + '_' + bound)
            .isEqualTo(Math.toIntExact(expected));
      }
    }

    JsonNode combined =
        json(
            mvc.perform(
                    get("/api/v2/market/properties")
                        .param("price_gte", "200000.00")
                        .param("bedrooms_gte", "3")
                        .param("school_rating_gte", "7.000")
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn());
    long combinedExpected =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM catalog.housing_records
            WHERE dataset_version_id = ? AND price >= 200000.00
              AND bedrooms >= 3 AND school_rating >= 7.000
            """,
            Long.class,
            DATASET_ID);
    assertThat(combined.path("items").size()).isEqualTo(Math.toIntExact(combinedExpected));

    mvc.perform(
            get("/api/v2/market/properties")
                .param("price_gte", "300000.00")
                .param("price_lte", "200000.00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("INVALID_FILTER"));

    JsonNode normalizedA =
        json(
            mvc.perform(get("/api/v2/market/properties").param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode normalizedB =
        json(
            mvc.perform(
                    get("/api/v2/market/properties")
                        .param("sort", "source_id:asc")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(normalizedA.at("/page/next_cursor").asText())
        .isEqualTo(normalizedB.at("/page/next_cursor").asText());

    JsonNode grouped =
        json(
            mvc.perform(
                    post("/api/v2/market/statistics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"scope":{"dataset_version_id":"%s"},
                             "metrics":[{"id":"property_count","method":"COUNT","field":"*"}],
                             "group_by":{"field":"bedrooms"},
                             "order":{"by":"group_key","direction":"asc"},"limit":100}
                            """
                                .formatted(DATASET_ID)))
                .andExpect(status().isOk())
                .andReturn());
    var expectedGroups =
        jdbc.query(
            """
            SELECT bedrooms, count(*) FROM catalog.housing_records
            WHERE dataset_version_id = ? GROUP BY bedrooms ORDER BY bedrooms
            """,
            (row, index) -> Map.entry(row.getInt(1), row.getLong(2)),
            DATASET_ID);
    assertThat(grouped.path("groups")).hasSize(expectedGroups.size());
    for (int index = 0; index < expectedGroups.size(); index++) {
      JsonNode group = grouped.path("groups").get(index);
      assertThat(new BigDecimal(group.at("/key/exact_value").asText()).intValueExact())
          .isEqualTo(expectedGroups.get(index).getKey());
      assertThat(metricInGroup(group, "property_count").longValueExact())
          .isEqualTo(expectedGroups.get(index).getValue());
    }

    JsonNode fixedWidthGroups =
        json(
            mvc.perform(
                    post("/api/v2/market/statistics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"scope":{"dataset_version_id":"%s"},
                             "metrics":[{"id":"property_count","method":"COUNT","field":"*"}],
                             "group_by":{"field":"school_rating","bucket":{"method":"FIXED_WIDTH","width":"2.000"}},
                             "order":{"by":"group_key","direction":"asc"},"limit":100}
                            """
                                .formatted(DATASET_ID)))
                .andExpect(status().isOk())
                .andReturn());
    var expectedFixedWidthGroups =
        jdbc.query(
            """
            SELECT floor(school_rating / 2.000) * 2.000 AS lower_bound, count(*)
            FROM catalog.housing_records
            WHERE dataset_version_id = ? GROUP BY 1 ORDER BY 1
            """,
            (row, index) -> Map.entry(row.getBigDecimal(1), row.getLong(2)),
            DATASET_ID);
    assertThat(fixedWidthGroups.path("groups")).hasSize(expectedFixedWidthGroups.size());
    for (int index = 0; index < expectedFixedWidthGroups.size(); index++) {
      JsonNode group = fixedWidthGroups.path("groups").get(index);
      BigDecimal lowerBound = expectedFixedWidthGroups.get(index).getKey();
      assertThat(new BigDecimal(group.at("/key/gte").asText())).isEqualByComparingTo(lowerBound);
      assertThat(new BigDecimal(group.at("/key/lt").asText()))
          .isEqualByComparingTo(lowerBound.add(new BigDecimal("2.000")));
      assertThat(metricInGroup(group, "property_count").longValueExact())
          .isEqualTo(expectedFixedWidthGroups.get(index).getValue());
    }
  }

  @Test
  void v1CompatibilityRoutesDelegateThroughCurrentLayeredServices() throws Exception {
    mvc.perform(get("/health/live"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));

    mvc.perform(
            get("/api/v1/market/analysis")
                .param("datasetVersionId", DATASET_ID.toString())
                .param("priceMin", "150000.00")
                .param("priceMax", "400000.00")
                .param("squareFootageMin", "1000")
                .param("squareFootageMax", "3000")
                .param("bedroomsMin", "2")
                .param("bedroomsMax", "5")
                .param("bathroomsMin", "1.0")
                .param("bathroomsMax", "4.0")
                .param("yearBuiltMin", "1970")
                .param("yearBuiltMax", "2020")
                .param("lotSizeMin", "3000")
                .param("lotSizeMax", "12000")
                .param("distanceMin", "1.000")
                .param("distanceMax", "15.000")
                .param("schoolRatingMin", "5.000")
                .param("schoolRatingMax", "10.000")
                .param("sort", "price")
                .param("direction", "DESC")
                .param("page", "1")
                .param("size", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datasetVersionId").value(DATASET_ID.toString()))
        .andExpect(jsonPath("$.records.length()").value(7))
        .andExpect(jsonPath("$.charts.average_price_by_bedrooms").isArray())
        .andExpect(jsonPath("$.charts.property_count_by_school_band.length()").value(3));

    mvc.perform(get("/api/v1/market/analysis").param("page", "-1"))
        .andExpect(status().isUnprocessableEntity());

    MvcResult csvPending =
        mvc.perform(
                get("/api/v1/market/exports/data.csv")
                    .param("datasetVersionId", DATASET_ID.toString())
                    .param("priceMin", "200000.00")
                    .param("sort", "price"))
            .andExpect(request().asyncStarted())
            .andReturn();
    byte[] csv =
        mvc.perform(asyncDispatch(csvPending))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(new String(csv, StandardCharsets.UTF_8))
        .startsWith("id,square_footage")
        .contains(".00");

    String prediction =
        """
        {"inputs":{"square_footage":1800,"bedrooms":3,"bathrooms":2.5,
         "year_built":2005,"lot_size":6500,"distance_to_city_center":4.125,
         "school_rating":8.500},"prediction":{"predicted_price":"315000.00",
         "currency":"USD","model_version":"model-v1","warnings":[]}}
        """;
    MvcResult pdfPending =
        mvc.perform(
                post("/api/v1/market/exports/report.pdf")
                    .param("datasetVersionId", DATASET_ID.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(prediction))
            .andExpect(request().asyncStarted())
            .andReturn();
    byte[] pdf =
        mvc.perform(asyncDispatch(pdfPending))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertEveryPageRendersWithinBounds(pdf);
    try (PdfReader reader = new PdfReader(pdf)) {
      assertThat(reader.getPageSizeWithRotation(1).getWidth())
          .isGreaterThan(reader.getPageSizeWithRotation(1).getHeight());
      assertThat(new PdfTextExtractor(reader).getTextFromPage(1))
          .contains("Property Market Analysis", "What-if prediction: USD 315000.00");
    }
  }

  @Test
  void durableExportsAreIdempotentAndDownloadsAreVerifiable() throws Exception {
    UUID csvKey = UUID.randomUUID();
    String csvRequest = exportRequest("CSV", null);
    JsonNode created = createExport(csvKey, csvRequest, 202);
    JsonNode retried = createExport(csvKey, csvRequest, 202);
    assertThat(retried.path("export_id").asText()).isEqualTo(created.path("export_id").asText());

    createExport(
        csvKey,
        exportRequest(
            "PDF",
            """
                {"include_details":true,"detail_limit":10}
                """),
        409);

    assertThat(exportWorker.runOnce()).isTrue();
    UUID csvId = UUID.fromString(created.path("export_id").asText());
    JsonNode csvJob = getExport(csvId);
    assertThat(csvJob.path("status").asText()).isEqualTo("SUCCEEDED");
    byte[] csv = download(csvId, "text/csv;charset=UTF-8");
    String[] csvLines = new String(csv, StandardCharsets.UTF_8).split("\\r?\\n");
    assertThat(csvLines).hasSize(51);
    assertThat(csvLines[0])
        .isEqualTo(
            "source_id,square_footage,bedrooms,bathrooms,year_built,lot_size,"
                + "distance_to_city_center,school_rating,price");
    assertThat(csvLines).allSatisfy(line -> assertThat(line).doesNotContain("\n", "\r"));
    assertThat(new BigDecimal(csvLines[1].split(",")[8]))
        .isLessThanOrEqualTo(new BigDecimal(csvLines[50].split(",")[8]));
    assertThat(sha256(csv)).isEqualTo(csvJob.path("content_sha256").asText());

    JsonNode filteredCsvCreated =
        createExport(
            UUID.randomUUID(),
            exportRequest("CSV", null, "{\"price\":{\"gte\":\"300000.00\"}}"),
            202);
    assertThat(exportWorker.runOnce()).isTrue();
    UUID filteredCsvId = UUID.fromString(filteredCsvCreated.path("export_id").asText());
    String[] filteredCsvLines =
        new String(download(filteredCsvId, "text/csv;charset=UTF-8"), StandardCharsets.UTF_8)
            .split("\\r?\\n");
    assertThat(filteredCsvLines)
        .hasSizeGreaterThan(1)
        .allSatisfy(
            line -> {
              if (!line.startsWith("source_id")) {
                assertThat(new BigDecimal(line.split(",")[8]))
                    .isGreaterThanOrEqualTo(new BigDecimal("300000.00"));
              }
            });

    JsonNode emptyCsvCreated =
        createExport(
            UUID.randomUUID(),
            exportRequest("CSV", null, "{\"price\":{\"gte\":\"9999999.00\"}}"),
            202);
    assertThat(exportWorker.runOnce()).isTrue();
    UUID emptyCsvId = UUID.fromString(emptyCsvCreated.path("export_id").asText());
    assertThat(
            new String(download(emptyCsvId, "text/csv;charset=UTF-8"), StandardCharsets.UTF_8)
                .strip())
        .isEqualTo(csvLines[0]);

    UUID pdfKey = UUID.randomUUID();
    String pdfFilters =
        """
                {"price":{"gte":"200000.00"},"bedrooms":{"gte":3}}
                """;
    JsonNode pdfCreated =
        createExport(
            pdfKey,
            exportRequest(
                "PDF",
                """
                {
                  "include_details":true,
                  "detail_limit":10,
                  "metrics":[
                    {"id":"property_count","method":"COUNT","field":"*"},
                    {"id":"average_price","method":"AVG","field":"price"}
                  ]
                }
                """,
                pdfFilters),
            202);
    assertThat(exportWorker.runOnce()).isTrue();
    UUID pdfId = UUID.fromString(pdfCreated.path("export_id").asText());
    JsonNode pdfJob = getExport(pdfId);
    JsonNode filteredPage =
        json(
            mvc.perform(
                    get("/api/v2/market/properties")
                        .param("dataset_version_id", DATASET_ID.toString())
                        .param("price_gte", "200000.00")
                        .param("bedrooms_gte", "3")
                        .param("sort", "price:asc")
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode filteredStatistics =
        json(
            mvc.perform(
                    post("/api/v2/market/statistics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                                {"scope":{"dataset_version_id":"%s","filters":%s},
                                "metrics":[{"id":"property_count","method":"COUNT","field":"*"}]}
                                """
                                .formatted(DATASET_ID, pdfFilters)))
                .andExpect(status().isOk())
                .andReturn());
    long filteredCount = filteredPage.path("items").size();
    assertThat(filteredPage.at("/page/has_more").asBoolean()).isFalse();
    assertThat(metric(filteredStatistics, "property_count"))
        .isEqualByComparingTo(BigDecimal.valueOf(filteredCount));
    assertThat(pdfJob.path("row_count").asLong()).isEqualTo(filteredCount);
    byte[] pdf = download(pdfId, "application/pdf");
    assertEveryPageRendersWithinBounds(pdf);
    assertThat(pdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    assertThat(sha256(pdf)).isEqualTo(pdfJob.path("content_sha256").asText());
    assertThat(pdfJob.path("detail_row_count").asLong()).isEqualTo(10);
    assertThat(pdfJob.path("details_truncated").asBoolean()).isTrue();
    String rawPdf = new String(pdf, StandardCharsets.ISO_8859_1);
    assertThat(rawPdf).contains("LiberationSans", "/FontFile2");
    try (PdfReader reader = new PdfReader(pdf)) {
      assertThat(reader.getNumberOfPages()).isGreaterThan(1);
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      String firstPage = extractor.getTextFromPage(1);
      String lastPage = extractor.getTextFromPage(reader.getNumberOfPages());
      assertThat(firstPage)
          .contains(
              "Property Market Analysis",
              "Active filters",
              "price: >= 200000.00",
              "bedrooms: >= 3",
              "Page 1");
      assertThat(lastPage)
          .contains("Showing first 10 of", "ID", "Price", "Page " + reader.getNumberOfPages());
    }

    String whatIf =
        """
        {"inputs":{"square_footage":1800,"bedrooms":3,"bathrooms":"2.5",
         "year_built":2005,"lot_size":6500,"distance_to_city_center":"4.125",
         "school_rating":"8.500"},"prediction":{"predicted_price":"315000.00",
         "currency":"USD","model_version":"model-v1","warnings":[]}}
        """;
    JsonNode whatIfPdfCreated =
        createExport(
            UUID.randomUUID(),
            exportRequest(
                "PDF", "{\"include_details\":false,\"detail_limit\":0,\"what_if\":" + whatIf + '}'),
            202);
    assertThat(exportWorker.runOnce()).isTrue();
    UUID whatIfPdfId = UUID.fromString(whatIfPdfCreated.path("export_id").asText());
    byte[] whatIfPdf = download(whatIfPdfId, "application/pdf");
    assertEveryPageRendersWithinBounds(whatIfPdf);
    assertThat(getExport(whatIfPdfId).path("row_count").asLong()).isEqualTo(50);
    try (PdfReader reader = new PdfReader(whatIfPdf)) {
      assertThat(reader.getPageSizeWithRotation(1).getWidth())
          .isGreaterThan(reader.getPageSizeWithRotation(1).getHeight());
      assertThat(new PdfTextExtractor(reader).getTextFromPage(1))
          .contains("Property Market Analysis", "315000.00", "model-v1");
    }

    JsonNode emptyPdfCreated =
        createExport(
            UUID.randomUUID(),
            exportRequest(
                "PDF",
                "{\"include_details\":true,\"detail_limit\":10}",
                "{\"price\":{\"gte\":\"9999999.00\"}}"),
            202);
    assertThat(exportWorker.runOnce()).isTrue();
    UUID emptyPdfId = UUID.fromString(emptyPdfCreated.path("export_id").asText());
    byte[] emptyPdf = download(emptyPdfId, "application/pdf");
    assertEveryPageRendersWithinBounds(emptyPdf);
    assertThat(getExport(emptyPdfId).path("row_count").asLong()).isZero();
    try (PdfReader reader = new PdfReader(emptyPdf)) {
      assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains("Matching records", "0");
    }
    assertThat(meterRegistry.find("market.export.generation.duration").timers()).isNotEmpty();
    assertThat(meterRegistry.find("market.export.rows").summaries()).isNotEmpty();
  }

  @Test
  void concurrentIdempotentCreationProducesExactlyOneJob() throws Exception {
    UUID key = UUID.randomUUID();
    String request = exportRequest("CSV", null);
    int participants = 8;
    CountDownLatch ready = new CountDownLatch(participants);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(participants);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<JsonNode>>();
      for (int index = 0; index < participants; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent export start timed out");
                  }
                  return createExport(key, request, 202);
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var ids = new HashSet<String>();
      for (var future : futures) {
        ids.add(future.get(10, TimeUnit.SECONDS).path("export_id").asText());
      }
      assertThat(ids).hasSize(1);
      assertThat(
              new JdbcTemplate(exportDataSource)
                  .queryForObject(
                      "SELECT count(*) FROM market_ops.export_jobs WHERE idempotency_key = ?",
                      Long.class,
                      key))
          .isEqualTo(1L);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void expiredLeasesRecoverAndRunningCancellationIsImmediatelyDurable() throws Exception {
    JsonNode recoverable = createExport(UUID.randomUUID(), exportRequest("CSV", null), 202);
    UUID recoverableId = UUID.fromString(recoverable.path("export_id").asText());
    var staleClaim =
        exportJobs.claimNext("crashed-worker", Instant.now().minusSeconds(1)).orElseThrow();
    assertThat(staleClaim.exportId()).isEqualTo(recoverableId);
    assertThat(staleClaim.status()).isEqualTo(Status.RUNNING);

    assertThat(exportWorker.recoverExpiredLeases()).isEqualTo(1);
    assertThat(exportJobs.find(recoverableId).orElseThrow().status()).isEqualTo(Status.QUEUED);
    Artifact staleArtifact =
        new Artifact(
            "stale.csv", "text/csv;charset=UTF-8", "stale.csv", 1, "0".repeat(64), 1, 1, false);
    assertThatThrownBy(
            () ->
                exportJobs.succeed(
                    recoverableId, staleClaim.stateRevision(), staleArtifact, Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lost its lease");

    assertThat(exportWorker.runOnce()).isTrue();
    assertThat(exportJobs.find(recoverableId).orElseThrow().status()).isEqualTo(Status.SUCCEEDED);

    JsonNode cancellable = createExport(UUID.randomUUID(), exportRequest("CSV", null), 202);
    UUID cancellableId = UUID.fromString(cancellable.path("export_id").asText());
    var cancelledClaim =
        exportJobs.claimNext("active-worker", Instant.now().plusSeconds(60)).orElseThrow();
    assertThat(cancelledClaim.exportId()).isEqualTo(cancellableId);

    mvc.perform(delete("/api/v2/market/exports/{id}", cancellableId))
        .andExpect(status().isAccepted());
    var cancelled = exportJobs.find(cancellableId).orElseThrow();
    assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);
    assertThat(cancelled.cancelRequested()).isTrue();
    assertThat(cancelled.leaseOwner()).isNull();
    assertThat(exportWorker.recoverExpiredLeases()).isZero();
    assertThatThrownBy(
            () ->
                exportJobs.succeed(
                    cancellableId, cancelledClaim.stateRevision(), staleArtifact, Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void servedContractIsTheBuildInputAndModelFailureIsIsolated() throws Exception {
    MvcResult served =
        mvc.perform(get("/openapi/market-service-v2.yaml")).andExpect(status().isOk()).andReturn();
    Path source = sourceContract();
    assertThat(sha256(served.getResponse().getContentAsByteArray()))
        .isEqualTo(sha256(Files.readAllBytes(source)));

    mvc.perform(
            post("/api/v2/market/what-if")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"square_footage":1800,"bedrooms":3,"bathrooms":"2.5",
                                 "year_built":2005,"lot_size":6500,
                                 "distance_to_city_center":"4.125","school_rating":"8.500"}
                                """))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("MODEL_SERVICE_UNAVAILABLE"));
    mvc.perform(get("/api/v2/market/properties").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
    assertThat(meterRegistry.find("market.model.client.duration").timers()).isNotEmpty();
    assertThat(meterRegistry.find("market.db.query.duration").timers()).isNotEmpty();
  }

  @Test
  void readinessChecksBusinessSchemasAndRuntimeGrantsStayLeastPrivilege() throws Exception {
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    assertThat(marketDatabaseHealth.health().getStatus())
        .isEqualTo(org.springframework.boot.actuate.health.Status.UP);
    assertThat(exportStorageHealth.health().getStatus())
        .isEqualTo(org.springframework.boot.actuate.health.Status.UP);
    assertThat(meterRegistry.get("market.export.disk.free.bytes").gauge().value()).isGreaterThan(0);

    JdbcTemplate jdbc = new JdbcTemplate(exportDataSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('market_runtime', 'catalog.housing_records', 'INSERT')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT has_table_privilege('market_runtime', 'market_ops.export_jobs', 'INSERT')
                   AND has_table_privilege('market_runtime', 'market_ops.export_jobs', 'UPDATE')
                   AND has_table_privilege('market_runtime', 'market_ops.export_jobs', 'DELETE')
                """,
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('training_runtime', 'market_ops.export_jobs', 'SELECT')",
                Boolean.class))
        .isFalse();
  }

  private List<Long> pageAll(String sort, int limit) throws Exception {
    List<Long> ids = new ArrayList<>();
    String cursor = null;
    do {
      var request =
          get("/api/v2/market/properties")
              .param("dataset_version_id", DATASET_ID.toString())
              .param("sort", sort)
              .param("limit", Integer.toString(limit));
      if (cursor != null) request.param("cursor", cursor);
      JsonNode page = json(mvc.perform(request).andExpect(status().isOk()).andReturn());
      page.path("items").forEach(item -> ids.add(item.path("source_id").asLong()));
      cursor = page.at("/page/has_more").asBoolean() ? page.at("/page/next_cursor").asText() : null;
    } while (cursor != null);
    return ids;
  }

  private JsonNode createExport(UUID key, String request, int expectedStatus) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v2/market/exports")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
            .andExpect(status().is(expectedStatus))
            .andExpect(header().exists("X-Correlation-ID"))
            .andReturn();
    return json(result);
  }

  private JsonNode getExport(UUID id) throws Exception {
    return json(
        mvc.perform(get("/api/v2/market/exports/{id}", id)).andExpect(status().isOk()).andReturn());
  }

  private byte[] download(UUID id, String contentType) throws Exception {
    return mvc.perform(get("/api/v2/market/exports/{id}/content", id))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", contentType))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().exists("Content-Digest"))
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  private String exportRequest(String format, String pdfOptions) {
    return exportRequest(format, pdfOptions, null);
  }

  private String exportRequest(String format, String pdfOptions, String filters) {
    String options = pdfOptions == null ? "" : ",\"pdf_options\":" + pdfOptions;
    String filterScope = filters == null ? "" : ",\"filters\":" + filters;
    return """
                {"format":"%s","selection":{"scope":{"dataset_version_id":"%s"%s},
                "sort":[{"field":"price","direction":"asc"}]}%s}
                """
        .formatted(format, DATASET_ID, filterScope, options);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }

  private static BigDecimal metric(JsonNode response, String id) {
    return new BigDecimal(findMetric(response, id).path("value").asText());
  }

  private static BigDecimal metricInGroup(JsonNode group, String id) {
    for (JsonNode value : group.path("values")) {
      if (id.equals(value.path("metric_id").asText())) {
        return new BigDecimal(value.path("value").asText());
      }
    }
    throw new AssertionError("missing metric " + id);
  }

  private static JsonNode findMetric(JsonNode response, String id) {
    for (JsonNode value : response.at("/groups/0/values")) {
      if (id.equals(value.path("metric_id").asText())) return value;
    }
    throw new AssertionError("missing metric " + id);
  }

  private static List<Long> allSourceIds() {
    List<Long> ids = new ArrayList<>();
    for (long id = 1; id <= 50; id++) ids.add(id);
    return ids;
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private static void assertEveryPageRendersWithinBounds(byte[] content) throws Exception {
    try (PDDocument document = Loader.loadPDF(content)) {
      PDFRenderer renderer = new PDFRenderer(document);
      assertThat(document.getNumberOfPages()).isPositive();
      for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 96, ImageType.RGB);
        assertThat(image.getWidth()).isGreaterThan(image.getHeight());

        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        int inkPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
          for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            int red = (rgb >>> 16) & 0xff;
            int green = (rgb >>> 8) & 0xff;
            int blue = rgb & 0xff;
            if (red + green + blue < 735) {
              inkPixels++;
              minX = Math.min(minX, x);
              minY = Math.min(minY, y);
              maxX = Math.max(maxX, x);
              maxY = Math.max(maxY, y);
            }
          }
        }

        assertThat(inkPixels).as("page %s must not be blank", pageIndex + 1).isGreaterThan(100);
        assertThat(minX).as("page %s left content bound", pageIndex + 1).isGreaterThan(4);
        assertThat(minY).as("page %s top content bound", pageIndex + 1).isGreaterThan(4);
        assertThat(maxX)
            .as("page %s right content bound", pageIndex + 1)
            .isLessThan(image.getWidth() - 5);
        assertThat(maxY)
            .as("page %s bottom content bound", pageIndex + 1)
            .isLessThan(image.getHeight() - 5);
      }
    }
  }

  private static Path sourceContract() {
    Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve("docs/openapi/market-service-v2.yaml");
      if (Files.isRegularFile(candidate)) return candidate;
      directory = directory.getParent();
    }
    throw new IllegalStateException("cannot locate source OpenAPI contract");
  }
}
