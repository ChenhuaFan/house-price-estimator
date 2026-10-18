package com.hsbc.market.config;

import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.MarketDomain.CursorPosition;
import com.hsbc.market.domain.MarketDomain.DatasetVersion;
import com.hsbc.market.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class MeteredMarketReadPort implements MarketReadPort {
  private final MarketReadPort delegate;
  private final MeterRegistry registry;
  private final String lane;

  MeteredMarketReadPort(MarketReadPort delegate, MeterRegistry registry, String lane) {
    this.delegate = delegate;
    this.registry = registry;
    this.lane = lane;
  }

  @Override
  public DatasetVersion resolveDatasetVersion(UUID requestedVersionId) {
    return observe("resolve_dataset", () -> delegate.resolveDatasetVersion(requestedVersionId));
  }

  @Override
  public List<PropertyRecord> listPage(
      ResolvedSelection selection, CursorPosition cursor, int limitPlusOne) {
    List<PropertyRecord> rows =
        observe("list_properties", () -> delegate.listPage(selection, cursor, limitPlusOne));
    recordRows("list_properties", rows.size());
    return rows;
  }

  @Override
  public StatisticsResult queryStatistics(ResolvedSelection selection, StatisticsCommand command) {
    long started = System.nanoTime();
    StatisticsResult result =
        observe("statistics", () -> delegate.queryStatistics(selection, command));
    Timer.builder("market.statistics.duration")
        .tag("lane", lane)
        .register(registry)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    registry.summary("market.statistics.groups", "lane", lane).record(result.groups().size());
    recordRows("statistics", result.groups().size());
    return result;
  }

  @Override
  public long count(ResolvedSelection selection) {
    long count = observe("count", () -> delegate.count(selection));
    recordRows("count", count);
    return count;
  }

  @Override
  public void stream(
      ResolvedSelection selection, int maximumRows, Consumer<PropertyRecord> consumer) {
    AtomicLong rows = new AtomicLong();
    observe(
        "stream",
        () -> {
          delegate.stream(
              selection,
              maximumRows,
              row -> {
                rows.incrementAndGet();
                consumer.accept(row);
              });
          return null;
        });
    recordRows("stream", rows.get());
  }

  private <T> T observe(String operation, Supplier<T> work) {
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

  private void recordRows(String operation, long rows) {
    registry.summary("market.db.rows", "lane", lane, "operation", operation).record(rows);
  }

  private static boolean isTimeout(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current.getClass().getSimpleName().toLowerCase().contains("timeout")) return true;
      current = current.getCause();
    }
    return false;
  }
}
