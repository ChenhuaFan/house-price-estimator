package com.hsbc.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.MarketDomain;
import com.hsbc.market.domain.MarketException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MarketQueryServiceTest {
  private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private final FakeReadPort readPort = new FakeReadPort();
  private final MarketQueryService service =
      new MarketQueryService(
          readPort,
          new SignedCursorCodec("a-test-secret-that-is-longer-than-thirty-two-characters"));

  @Test
  void keysetPagesUseSignedQueryBoundCursor() {
    MarketDomain.Selection selection = selection(MarketDomain.SortField.PRICE);

    MarketDomain.PropertyPage first = service.list(selection, 2, null);
    MarketDomain.PropertyPage second = service.list(selection, 2, first.nextCursor());

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
    String cursor = service.list(selection, 2, null).nextCursor();

    assertThatThrownBy(() -> service.list(selection, 2, cursor + "x"))
        .isInstanceOf(MarketException.class)
        .extracting(error -> ((MarketException) error).code())
        .isEqualTo("QUERY_STATE_MISMATCH");

    MarketDomain.Selection changed = selection(MarketDomain.SortField.SQUARE_FOOTAGE);
    assertThatThrownBy(() -> service.list(changed, 2, cursor))
        .isInstanceOf(MarketException.class)
        .extracting(error -> ((MarketException) error).code())
        .isEqualTo("QUERY_STATE_MISMATCH");
  }

  @Test
  void statisticsRejectDuplicateIdsAndInvalidMethodFieldPair() {
    var duplicate =
        new MarketDomain.StatisticsCommand(
            VERSION,
            MarketDomain.Filters.empty(),
            List.of(
                new MarketDomain.Metric(
                    "value", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL),
                new MarketDomain.Metric(
                    "value", MarketDomain.MetricMethod.AVG, MarketDomain.MetricField.PRICE)),
            null,
            null,
            10);
    var invalid =
        new MarketDomain.StatisticsCommand(
            VERSION,
            MarketDomain.Filters.empty(),
            List.of(
                new MarketDomain.Metric(
                    "bad", MarketDomain.MetricMethod.AVG, MarketDomain.MetricField.ALL)),
            null,
            null,
            10);

    assertThatThrownBy(() -> service.query(duplicate)).isInstanceOf(MarketException.class);
    assertThatThrownBy(() -> service.query(invalid)).isInstanceOf(MarketException.class);
  }

  @Test
  void fingerprintIsStableForEquivalentDecimalScales() {
    var left =
        new MarketDomain.Filters(
            MarketDomain.Range.of(new BigDecimal("200000.00"), null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var right =
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
                "a".repeat(64),
                left,
                new MarketDomain.Sort(
                    MarketDomain.SortField.PRICE, MarketDomain.SortDirection.ASC)))
        .isEqualTo(
            QueryFingerprint.selection(
                "a".repeat(64),
                right,
                new MarketDomain.Sort(
                    MarketDomain.SortField.PRICE, MarketDomain.SortDirection.ASC)));
  }

  private static MarketDomain.Selection selection(MarketDomain.SortField field) {
    return new MarketDomain.Selection(
        VERSION,
        MarketDomain.Filters.empty(),
        new MarketDomain.Sort(field, MarketDomain.SortDirection.ASC));
  }

  private static final class FakeReadPort implements MarketReadPort {
    private final List<MarketDomain.PropertyRecord> rows =
        List.of(
            row(1, 100000, 1000), row(2, 200000, 1200),
            row(3, 200000, 1400), row(4, 300000, 1600));

    @Override
    public MarketDomain.DatasetVersion resolveDatasetVersion(UUID requestedVersionId) {
      return new MarketDomain.DatasetVersion(VERSION, "a".repeat(64));
    }

    @Override
    public List<MarketDomain.PropertyRecord> listPage(
        MarketDomain.ResolvedSelection selection,
        MarketDomain.CursorPosition cursor,
        int limitPlusOne) {
      int start = 0;
      if (cursor != null) {
        while (start < rows.size() && rows.get(start).sourceId() <= cursor.sourceId()) start++;
      }
      return new ArrayList<>(rows.subList(start, Math.min(rows.size(), start + limitPlusOne)));
    }

    @Override
    public MarketDomain.StatisticsResult queryStatistics(
        MarketDomain.ResolvedSelection selection, MarketDomain.StatisticsCommand command) {
      return new MarketDomain.StatisticsResult(
          selection.datasetVersion(), selection.queryFingerprint(), List.of());
    }

    @Override
    public long count(MarketDomain.ResolvedSelection selection) {
      return rows.size();
    }

    @Override
    public void stream(
        MarketDomain.ResolvedSelection selection,
        int maximumRows,
        Consumer<MarketDomain.PropertyRecord> consumer) {
      rows.stream().limit(maximumRows).forEach(consumer);
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
}
