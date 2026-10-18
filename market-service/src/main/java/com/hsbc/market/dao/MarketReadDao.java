package com.hsbc.market.dao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.mapper.export.ExportMarketQueryMapper;
import com.hsbc.market.mapper.query.MarketQueryMapper;
import com.hsbc.market.model.domain.MarketDomain.CursorPosition;
import com.hsbc.market.model.domain.MarketDomain.DatasetVersion;
import com.hsbc.market.model.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.model.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.model.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.model.domain.MarketDomain.StatisticsGroup;
import com.hsbc.market.model.domain.MarketDomain.StatisticsGroupKey;
import com.hsbc.market.model.domain.MarketDomain.StatisticsResult;
import com.hsbc.market.model.persistence.DatasetVersionRecord;
import com.hsbc.market.model.persistence.PropertyRecordRow;
import com.hsbc.market.model.persistence.StatisticsRowRecord;
import com.hsbc.market.model.query.MarketSqlParameters;
import com.hsbc.market.model.query.PageSqlParameters;
import com.hsbc.market.model.query.StatisticsSqlParameters;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Repository;

/** Executes bounded market queries against interactive and export data sources. */
@Repository
public class MarketReadDao {
  private static final TypeReference<LinkedHashMap<String, BigDecimal>> VALUES_TYPE =
      new TypeReference<>() {};

  private final MarketQueryMapper queryMapper;
  private final ExportMarketQueryMapper exportMapper;
  private final ObjectMapper objectMapper;
  private final MeterRegistry registry;
  private final Cache datasetVersions;

  public MarketReadDao(
      MarketQueryMapper queryMapper,
      ExportMarketQueryMapper exportMapper,
      ObjectMapper objectMapper,
      MeterRegistry registry,
      CacheManager cacheManager) {
    this.queryMapper = queryMapper;
    this.exportMapper = exportMapper;
    this.objectMapper = objectMapper;
    this.registry = registry;
    this.datasetVersions = required(cacheManager, "datasetVersion");
  }

  /** Resolves an explicit dataset version or the current active version. */
  public DatasetVersion resolveDatasetVersion(UUID requestedVersionId) {
    String key = requestedVersionId == null ? "active" : requestedVersionId.toString();
    return datasetVersions.get(
        key,
        () ->
            observe(
                "interactive",
                "resolve_dataset",
                () -> {
                  DatasetVersionRecord record =
                      requestedVersionId == null
                          ? queryMapper.findActiveVersion()
                          : queryMapper.findVersion(requestedVersionId);
                  if (record == null) {
                    throw MarketException.notFound("dataset version was not found");
                  }
                  return new DatasetVersion(record.id(), record.contentSha256());
                }));
  }

  /** Checks that the active dataset catalog can be read. */
  public boolean catalogReadable() {
    return observe("interactive", "health", queryMapper::catalogReadable);
  }

  /** Reads one keyset-paginated property page for the resolved selection. */
  public List<PropertyRecord> listPage(
      ResolvedSelection selection, CursorPosition cursor, int limitPlusOne) {
    List<PropertyRecord> rows =
        observe(
            "interactive",
            "list_properties",
            () ->
                queryMapper
                    .selectPage(PageSqlParameters.page(selection, cursor, limitPlusOne))
                    .stream()
                    .map(MarketReadDao::toDomain)
                    .toList());
    recordRows("interactive", "list_properties", rows.size());
    return rows;
  }

  /** Aggregates statistics through the latency-bounded interactive connection pool. */
  public StatisticsResult queryStatistics(ResolvedSelection selection, StatisticsCommand command) {
    return statistics(queryMapper::selectStatistics, "interactive", selection, command);
  }

  /** Aggregates report statistics through the long-running export pool. */
  public StatisticsResult queryExportStatistics(
      ResolvedSelection selection, StatisticsCommand command) {
    return statistics(exportMapper::selectStatistics, "export", selection, command);
  }

  /** Counts matching rows through the interactive query pool. */
  public long count(ResolvedSelection selection) {
    long count =
        observe(
            "interactive", "count", () -> queryMapper.count(MarketSqlParameters.from(selection)));
    recordRows("interactive", "count", count);
    return count;
  }

  /** Counts matching rows through the export pool. */
  public long countExport(ResolvedSelection selection) {
    long count =
        observe("export", "count", () -> exportMapper.count(MarketSqlParameters.from(selection)));
    recordRows("export", "count", count);
    return count;
  }

  /** Streams a bounded property selection without retaining the full result in memory. */
  public void stream(
      ResolvedSelection selection, int maximumRows, Consumer<PropertyRecord> consumer) {
    streamWith(queryMapper::stream, "interactive", selection, maximumRows, consumer);
  }

  /** Streams a bounded selection through the isolated export pool. */
  public void streamExport(
      ResolvedSelection selection, int maximumRows, Consumer<PropertyRecord> consumer) {
    streamWith(exportMapper::stream, "export", selection, maximumRows, consumer);
  }

  private StatisticsResult statistics(
      StatisticsStatement statement,
      String lane,
      ResolvedSelection selection,
      StatisticsCommand command) {
    StatisticsSqlParameters parameters =
        new StatisticsSqlParameters(
            MarketSqlParameters.from(selection),
            command.metrics(),
            command.groupBy(),
            command.order(),
            command.limit());

    long started = System.nanoTime();
    List<StatisticsGroup> groups =
        observe(lane, "statistics", () -> statement.execute(parameters)).stream()
            .map(row -> toStatisticsGroup(row, command))
            .toList();

    Timer.builder("market.statistics.duration")
        .tag("lane", lane)
        .register(registry)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    registry.summary("market.statistics.groups", "lane", lane).record(groups.size());
    recordRows(lane, "statistics", groups.size());

    return new StatisticsResult(selection.datasetVersion(), selection.queryFingerprint(), groups);
  }

  private void streamWith(
      StreamStatement statement,
      String lane,
      ResolvedSelection selection,
      int maximumRows,
      Consumer<PropertyRecord> consumer) {
    AtomicLong rows = new AtomicLong();
    observe(
        lane,
        "stream",
        () -> {
          statement.execute(
              PageSqlParameters.stream(selection, maximumRows),
              context -> {
                rows.incrementAndGet();
                consumer.accept(toDomain(context.getResultObject()));
              });
          return null;
        });
    recordRows(lane, "stream", rows.get());
  }

  private StatisticsGroup toStatisticsGroup(StatisticsRowRecord row, StatisticsCommand command) {
    return new StatisticsGroup(
        command.groupBy() == null
            ? StatisticsGroupKey.empty()
            : new StatisticsGroupKey(
                command.groupBy().field(),
                row.groupExact(),
                row.groupGte(),
                row.groupLt(),
                row.groupLabel()),
        values(row.valuesJson()));
  }

  private Map<String, BigDecimal> values(String json) {
    try {
      return objectMapper.readValue(json, VALUES_TYPE);
    } catch (Exception error) {
      throw new IllegalStateException("database returned invalid statistics JSON", error);
    }
  }

  private <T> T observe(String lane, String operation, Supplier<T> work) {
    long started = System.nanoTime();
    String outcome = "success";
    try {
      return work.get();
    } catch (RuntimeException error) {
      outcome = "error";
      if (isTimeout(error)) {
        registry.counter("market.db.timeouts", "lane", lane, "operation", operation).increment();
      }
      throw error;
    } finally {
      Timer.builder("market.db.query.duration")
          .tags("lane", lane, "operation", operation, "outcome", outcome)
          .register(registry)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private void recordRows(String lane, String operation, long rows) {
    registry.summary("market.db.rows", "lane", lane, "operation", operation).record(rows);
  }

  private static boolean isTimeout(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof QueryTimeoutException
          || current instanceof java.sql.SQLTimeoutException
          || "57014".equals(sqlState(current))) {
        return true;
      }
    }
    return false;
  }

  private static String sqlState(Throwable error) {
    return error instanceof java.sql.SQLException sql ? sql.getSQLState() : null;
  }

  private static PropertyRecord toDomain(PropertyRecordRow row) {
    return new PropertyRecord(
        row.sourceId(),
        row.squareFootage(),
        row.bedrooms(),
        row.bathrooms(),
        row.yearBuilt(),
        row.lotSize(),
        row.distanceToCityCenter(),
        row.schoolRating(),
        row.price());
  }

  private static Cache required(CacheManager manager, String name) {
    Cache cache = manager.getCache(name);
    if (cache == null) {
      throw new IllegalStateException("missing cache: " + name);
    }
    return cache;
  }

  @FunctionalInterface
  private interface StatisticsStatement {
    List<StatisticsRowRecord> execute(StatisticsSqlParameters parameters);
  }

  @FunctionalInterface
  private interface StreamStatement {
    void execute(PageSqlParameters parameters, ResultHandler<PropertyRecordRow> handler);
  }
}
