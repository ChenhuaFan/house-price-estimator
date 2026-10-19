package com.hsbc.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.model.domain.MarketDomain;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCache;

class MarketQueryServiceTest {
  private static final long DATA_REVISION = 1;
  private final List<MarketDomain.PropertyRecord> rows =
      List.of(
          row(1, 100000, 1000), row(2, 200000, 1200), row(3, 200000, 1400), row(4, 300000, 1600));

  private MarketReadDao marketReadDao;
  private MarketQueryService marketQueryService;
  private StatisticsService statisticsService;

  @BeforeEach
  void setUp() {
    marketReadDao = mock(MarketReadDao.class);
    when(marketReadDao.currentDataRevision()).thenReturn(DATA_REVISION);
    when(marketReadDao.listPage(any(), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              MarketDomain.CursorPosition cursor = invocation.getArgument(1);
              int limit = invocation.getArgument(2);
              int start = 0;
              if (cursor != null) {
                while (start < rows.size() && rows.get(start).sourceId() <= cursor.sourceId()) {
                  start++;
                }
              }
              return new ArrayList<>(rows.subList(start, Math.min(rows.size(), start + limit)));
            });
    marketQueryService =
        new MarketQueryService(
            marketReadDao,
            new SignedCursorCodec("a-test-secret-that-is-longer-than-thirty-two-characters"),
            new ConcurrentMapCache("firstPage"));
    ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("statistics");
    statisticsService = new StatisticsService(marketQueryService, marketReadDao, cacheManager);
  }

  @Test
  void keysetPagesUseSignedQueryBoundCursor() {
    MarketDomain.Selection selection = selection(MarketDomain.SortField.PRICE);

    MarketDomain.PropertyPage first = marketQueryService.list(selection, 2, null);
    MarketDomain.PropertyPage second = marketQueryService.list(selection, 2, first.nextCursor());

    assertThat(first.items())
        .extracting(MarketDomain.PropertyRecord::sourceId)
        .containsExactly(1L, 2L);
    assertThat(first.hasMore()).isTrue();
    assertThat(second.items())
        .extracting(MarketDomain.PropertyRecord::sourceId)
        .containsExactly(3L, 4L);
    assertThat(second.hasMore()).isFalse();
  }

  @Test
  void cursorRejectsTamperingAndChangedQuery() {
    MarketDomain.Selection selection = selection(MarketDomain.SortField.PRICE);
    String cursor = marketQueryService.list(selection, 2, null).nextCursor();

    assertThatThrownBy(() -> marketQueryService.list(selection, 2, cursor + "x"))
        .isInstanceOf(MarketException.class)
        .extracting(error -> ((MarketException) error).code())
        .isEqualTo("QUERY_STATE_MISMATCH");

    MarketDomain.Selection changed = selection(MarketDomain.SortField.SQUARE_FOOTAGE);
    assertThatThrownBy(() -> marketQueryService.list(changed, 2, cursor))
        .isInstanceOf(MarketException.class)
        .extracting(error -> ((MarketException) error).code())
        .isEqualTo("QUERY_STATE_MISMATCH");
  }

  @Test
  void statisticsRejectDuplicateIdsAndInvalidMethodFieldPair() {
    MarketDomain.StatisticsCommand duplicate =
        new MarketDomain.StatisticsCommand(
            MarketDomain.Filters.empty(),
            List.of(
                new MarketDomain.Metric(
                    "value", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL),
                new MarketDomain.Metric(
                    "value", MarketDomain.MetricMethod.AVG, MarketDomain.MetricField.PRICE)),
            null,
            null,
            10);
    MarketDomain.StatisticsCommand invalid =
        new MarketDomain.StatisticsCommand(
            MarketDomain.Filters.empty(),
            List.of(
                new MarketDomain.Metric(
                    "bad", MarketDomain.MetricMethod.AVG, MarketDomain.MetricField.ALL)),
            null,
            null,
            10);

    assertThatThrownBy(() -> statisticsService.query(duplicate))
        .isInstanceOf(MarketException.class);
    assertThatThrownBy(() -> statisticsService.query(invalid)).isInstanceOf(MarketException.class);
  }

  @Test
  void fingerprintIsStableForEquivalentDecimalScales() {
    MarketDomain.Filters left =
        new MarketDomain.Filters(
            MarketDomain.Range.of(new BigDecimal("200000.00"), null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    MarketDomain.Filters right =
        new MarketDomain.Filters(
            MarketDomain.Range.of(new BigDecimal("200000.0"), null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(
            QueryFingerprint.selection(
                DATA_REVISION,
                left,
                new MarketDomain.Sort(
                    MarketDomain.SortField.PRICE, MarketDomain.SortDirection.ASC)))
        .isEqualTo(
            QueryFingerprint.selection(
                DATA_REVISION,
                right,
                new MarketDomain.Sort(
                    MarketDomain.SortField.PRICE, MarketDomain.SortDirection.ASC)));
  }

  @Test
  void firstPageAndStatisticsCachesUseNormalizedVersionedKeys() {
    MarketDomain.Selection selection = selection(MarketDomain.SortField.PRICE);
    marketQueryService.list(selection, 2, null);
    marketQueryService.list(selection, 2, null);
    verify(marketReadDao, times(1)).listPage(any(), any(), anyInt());

    MarketDomain.StatisticsCommand command =
        new MarketDomain.StatisticsCommand(
            MarketDomain.Filters.empty(),
            List.of(
                new MarketDomain.Metric(
                    "count", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL)),
            null,
            null,
            10);
    when(marketReadDao.queryStatistics(any(), any()))
        .thenAnswer(
            invocation -> {
              MarketDomain.ResolvedSelection resolved = invocation.getArgument(0);
              return new MarketDomain.StatisticsResult(
                  resolved.dataRevision(), resolved.queryFingerprint(), List.of());
            });
    statisticsService.query(command);
    statisticsService.query(command);
    verify(marketReadDao, times(1)).queryStatistics(any(), any());
  }

  @Test
  void cacheKeysSeparateEveryQueryDimensionAndRestartOrDisablePreservesResults() {
    AtomicLong dataRevision = new AtomicLong(DATA_REVISION);
    when(marketReadDao.currentDataRevision()).thenAnswer(ignored -> dataRevision.get());
    var sharedCache = new ConcurrentMapCache("firstPage");
    MarketQueryService cached = service(sharedCache);
    MarketDomain.Selection base = selection(MarketDomain.SortField.PRICE);

    MarketDomain.PropertyPage baseline = cached.list(base, 2, null);
    assertThat(cached.list(base, 2, null)).isEqualTo(baseline);

    MarketDomain.Filters filtered =
        new MarketDomain.Filters(
            MarketDomain.Range.of(new BigDecimal("200000.00"), null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    cached.list(
        new MarketDomain.Selection(
            filtered,
            new MarketDomain.Sort(MarketDomain.SortField.PRICE, MarketDomain.SortDirection.ASC)),
        2,
        null);
    cached.list(selection(MarketDomain.SortField.SQUARE_FOOTAGE), 2, null);
    cached.list(base, 3, null);

    dataRevision.set(2);
    MarketDomain.PropertyPage activated = cached.list(base, 2, null);
    assertThat(activated.dataRevision()).isEqualTo(2);
    assertThat(activated.items()).isEqualTo(baseline.items());
    verify(marketReadDao, times(5)).listPage(any(), any(), anyInt());

    MarketQueryService restarted = service(new ConcurrentMapCache("firstPage"));
    assertThat(restarted.list(base, 2, null)).isEqualTo(activated);
    verify(marketReadDao, times(6)).listPage(any(), any(), anyInt());

    MarketQueryService cacheDisabled = service(new NoOpCache("firstPage"));
    assertThat(cacheDisabled.list(base, 2, null)).isEqualTo(activated);
    assertThat(cacheDisabled.list(base, 2, null)).isEqualTo(activated);
    verify(marketReadDao, times(8)).listPage(any(), any(), anyInt());
  }

  @Test
  void rejectsEveryInvalidStatisticsShapeAndPageLimit() {
    assertThatThrownBy(
            () -> marketQueryService.list(selection(MarketDomain.SortField.PRICE), 0, null))
        .isInstanceOf(MarketException.class);
    assertThatThrownBy(
            () -> marketQueryService.list(selection(MarketDomain.SortField.PRICE), 201, null))
        .isInstanceOf(MarketException.class);

    List<MarketDomain.StatisticsCommand> invalid = new ArrayList<>();
    invalid.add(null);
    invalid.add(command(List.of(), null, null, 10));
    List<MarketDomain.Metric> tooMany = new ArrayList<>();
    for (int index = 0; index < 21; index++) {
      tooMany.add(
          new MarketDomain.Metric(
              "metric_" + index, MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL));
    }
    invalid.add(command(tooMany, null, null, 10));
    invalid.add(command(List.of(metric("count")), null, null, 0));
    invalid.add(command(List.of(metric("count")), null, null, 201));
    invalid.add(command(java.util.Collections.singletonList(null), null, null, 10));
    invalid.add(
        command(
            List.of(
                new MarketDomain.Metric(
                    "INVALID", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL)),
            null,
            null,
            10));
    invalid.add(
        command(
            List.of(
                new MarketDomain.Metric(
                    "source_sum",
                    MarketDomain.MetricMethod.SUM,
                    MarketDomain.MetricField.SOURCE_ID)),
            null,
            null,
            10));
    invalid.add(
        command(
            List.of(metric("count")),
            new MarketDomain.GroupBy(MarketDomain.GroupField.PRICE, null),
            null,
            10));
    invalid.add(
        command(
            List.of(metric("count")),
            new MarketDomain.GroupBy(MarketDomain.GroupField.BEDROOMS, BigDecimal.ZERO),
            null,
            10));
    invalid.add(
        command(
            List.of(metric("count")),
            null,
            new MarketDomain.GroupOrder("unknown", MarketDomain.SortDirection.ASC),
            10));

    for (MarketDomain.StatisticsCommand command : invalid) {
      assertThatThrownBy(() -> statisticsService.query(command))
          .isInstanceOf(MarketException.class);
    }
  }

  private static MarketDomain.Metric metric(String id) {
    return new MarketDomain.Metric(
        id, MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL);
  }

  private static MarketDomain.StatisticsCommand command(
      List<MarketDomain.Metric> metrics,
      MarketDomain.GroupBy group,
      MarketDomain.GroupOrder order,
      int limit) {
    return new MarketDomain.StatisticsCommand(
        MarketDomain.Filters.empty(), metrics, group, order, limit);
  }

  private static MarketDomain.Selection selection(MarketDomain.SortField field) {
    return new MarketDomain.Selection(
        MarketDomain.Filters.empty(), new MarketDomain.Sort(field, MarketDomain.SortDirection.ASC));
  }

  private MarketQueryService service(org.springframework.cache.Cache cache) {
    return new MarketQueryService(
        marketReadDao,
        new SignedCursorCodec("a-test-secret-that-is-longer-than-thirty-two-characters"),
        cache);
  }

  private static MarketDomain.PropertyRecord row(long id, long price, long squareFootage) {
    return new MarketDomain.PropertyRecord(
        id,
        squareFootage,
        3,
        new BigDecimal("2.0"),
        2000,
        5000,
        new BigDecimal("5.000"),
        new BigDecimal("8.000"),
        BigDecimal.valueOf(price));
  }
}
