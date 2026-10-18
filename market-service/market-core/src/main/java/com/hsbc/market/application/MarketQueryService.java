package com.hsbc.market.application;

import com.hsbc.market.application.port.in.ListPropertiesUseCase;
import com.hsbc.market.application.port.in.QueryStatisticsUseCase;
import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.MarketDomain.CursorPosition;
import com.hsbc.market.domain.MarketDomain.DatasetVersion;
import com.hsbc.market.domain.MarketDomain.GroupBy;
import com.hsbc.market.domain.MarketDomain.Metric;
import com.hsbc.market.domain.MarketDomain.MetricField;
import com.hsbc.market.domain.MarketDomain.MetricMethod;
import com.hsbc.market.domain.MarketDomain.PropertyPage;
import com.hsbc.market.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketDomain.Selection;
import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;
import com.hsbc.market.domain.MarketException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MarketQueryService implements ListPropertiesUseCase, QueryStatisticsUseCase {
  private static final Set<com.hsbc.market.domain.MarketDomain.GroupField> EXACT_GROUP_FIELDS =
      Set.of(
          com.hsbc.market.domain.MarketDomain.GroupField.BEDROOMS,
          com.hsbc.market.domain.MarketDomain.GroupField.BATHROOMS);

  private final MarketReadPort readPort;
  private final SignedCursorCodec cursorCodec;

  public MarketQueryService(MarketReadPort readPort, SignedCursorCodec cursorCodec) {
    this.readPort = readPort;
    this.cursorCodec = cursorCodec;
  }

  @Override
  public PropertyPage list(Selection selection, int limit, String cursor) {
    if (limit < 1 || limit > 200) {
      throw MarketException.validation("VALIDATION_ERROR", "limit must be between 1 and 200");
    }
    ResolvedSelection resolved = resolve(selection);
    return listResolved(resolved, limit, cursor);
  }

  public PropertyPage listResolved(ResolvedSelection resolved, int limit, String cursor) {
    CursorPosition position = cursor == null ? null : cursorCodec.decode(cursor, resolved);
    List<PropertyRecord> fetched = readPort.listPage(resolved, position, limit + 1);
    boolean hasMore = fetched.size() > limit;
    List<PropertyRecord> items =
        hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
    String next = null;
    if (hasMore && !items.isEmpty()) {
      PropertyRecord last = items.getLast();
      next =
          cursorCodec.encode(
              resolved,
              new CursorPosition(last.sortValue(resolved.sort().field()), last.sourceId()));
    }
    return new PropertyPage(
        resolved.datasetVersion(), resolved.queryFingerprint(), items, limit, hasMore, next);
  }

  @Override
  public StatisticsResult query(StatisticsCommand command) {
    validateStatistics(command);
    Selection selection =
        new Selection(command.requestedDatasetVersionId(), command.filters(), null);
    ResolvedSelection resolved = resolve(selection);
    return queryResolved(resolved, command);
  }

  public StatisticsResult queryResolved(ResolvedSelection resolved, StatisticsCommand command) {
    validateStatistics(command);
    String fingerprint =
        QueryFingerprint.statistics(
            resolved, command.metrics(), command.groupBy(), command.order(), command.limit());
    ResolvedSelection statisticsSelection =
        new ResolvedSelection(
            resolved.datasetVersion(), resolved.filters(), resolved.sort(), fingerprint);
    return readPort.queryStatistics(statisticsSelection, command);
  }

  public ResolvedSelection resolve(Selection selection) {
    DatasetVersion version = readPort.resolveDatasetVersion(selection.requestedDatasetVersionId());
    String fingerprint =
        QueryFingerprint.selection(version.contentSha256(), selection.filters(), selection.sort());
    return new ResolvedSelection(version, selection.filters(), selection.sort(), fingerprint);
  }

  private void validateStatistics(StatisticsCommand command) {
    if (command == null
        || command.metrics() == null
        || command.metrics().isEmpty()
        || command.metrics().size() > 20) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "statistics require between 1 and 20 metrics");
    }
    if (command.limit() < 1 || command.limit() > 200) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "statistics limit must be between 1 and 200");
    }
    Set<String> ids = new HashSet<>();
    for (Metric metric : command.metrics()) {
      if (metric == null || metric.id() == null || !metric.id().matches("^[a-z][a-z0-9_]{0,63}$")) {
        throw MarketException.validation("VALIDATION_ERROR", "metric id is invalid");
      }
      if (!ids.add(metric.id())) {
        throw MarketException.validation("VALIDATION_ERROR", "metric ids must be unique");
      }
      if (metric.field() == MetricField.ALL && metric.method() != MetricMethod.COUNT) {
        throw MarketException.validation("VALIDATION_ERROR", "only COUNT supports field '*'");
      }
      if (metric.field() == MetricField.SOURCE_ID
          && Set.of(MetricMethod.SUM, MetricMethod.AVG, MetricMethod.STDDEV_POP)
              .contains(metric.method())) {
        throw MarketException.validation(
            "VALIDATION_ERROR", "source_id does not support this metric method");
      }
    }
    GroupBy group = command.groupBy();
    if (group != null) {
      if (!EXACT_GROUP_FIELDS.contains(group.field())
          && (group.fixedWidth() == null || group.fixedWidth().compareTo(BigDecimal.ZERO) <= 0)) {
        throw MarketException.validation(
            "VALIDATION_ERROR", "high-cardinality group fields require a positive fixed width");
      }
      if (group.fixedWidth() != null && group.fixedWidth().compareTo(BigDecimal.ZERO) <= 0) {
        throw MarketException.validation("VALIDATION_ERROR", "bucket width must be positive");
      }
    }
    if (command.order() != null
        && !"group_key".equals(command.order().by())
        && !ids.contains(command.order().by())) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "statistics order must name group_key or a metric id");
    }
  }
}
