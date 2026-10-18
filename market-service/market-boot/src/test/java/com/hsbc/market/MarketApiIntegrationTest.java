package com.hsbc.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.adapter.export.ExportWorker;
import com.hsbc.market.application.port.out.ExportJobPort;
import com.hsbc.market.domain.ExportDomain.Artifact;
import com.hsbc.market.domain.ExportDomain.Status;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  @Autowired private ExportWorker exportWorker;
  @Autowired private ExportJobPort exportJobs;
  @Autowired private MeterRegistry meterRegistry;

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
