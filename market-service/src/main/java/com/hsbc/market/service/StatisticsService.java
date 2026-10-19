package com.hsbc.market.service;

import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.model.domain.MarketDomain.GroupBy;
import com.hsbc.market.model.domain.MarketDomain.GroupField;
import com.hsbc.market.model.domain.MarketDomain.Metric;
import com.hsbc.market.model.domain.MarketDomain.MetricField;
import com.hsbc.market.model.domain.MarketDomain.MetricMethod;
import com.hsbc.market.model.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.model.domain.MarketDomain.Selection;
import com.hsbc.market.model.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.model.domain.MarketDomain.StatisticsResult;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Validates, fingerprints, caches, and executes aggregate market queries. */
@Service
public class StatisticsService {
  private static final Set<GroupField> EXACT_GROUP_FIELDS =
      Set.of(GroupField.BEDROOMS, GroupField.BATHROOMS);

  private final MarketQueryService marketQueryService;
  private final MarketReadDao marketReadDao;
  private final Cache statistics;

  public StatisticsService(
      MarketQueryService marketQueryService,
      MarketReadDao marketReadDao,
      CacheManager cacheManager) {
    this.marketQueryService = marketQueryService;
    this.marketReadDao = marketReadDao;
    this.statistics = required(cacheManager, "statistics");
  }

  /** Returns bounded statistics pinned to the current live-data revision. */
  @Transactional(
      transactionManager = "queryTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      timeout = 3)
  public StatisticsResult query(StatisticsCommand command) {
    validate(command);

    ResolvedSelection resolved = marketQueryService.resolve(new Selection(command.filters(), null));
    String fingerprint =
        QueryFingerprint.statistics(
            resolved, command.metrics(), command.groupBy(), command.order(), command.limit());
    ResolvedSelection statisticsSelection =
        new ResolvedSelection(
            resolved.dataRevision(), resolved.filters(), resolved.sort(), fingerprint);
    String key = "v2|" + resolved.dataRevision() + '|' + fingerprint;

    return statistics.get(key, () -> marketReadDao.queryStatistics(statisticsSelection, command));
  }

  private void validate(StatisticsCommand command) {
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

  private static Cache required(CacheManager manager, String name) {
    Cache cache = manager.getCache(name);
    if (cache == null) {
      throw new IllegalStateException("missing cache: " + name);
    }
    return cache;
  }
}
