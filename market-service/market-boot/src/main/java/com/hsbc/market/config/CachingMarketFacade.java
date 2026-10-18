package com.hsbc.market.config;

import com.hsbc.market.application.MarketQueryService;
import com.hsbc.market.application.QueryFingerprint;
import com.hsbc.market.application.port.in.ListPropertiesUseCase;
import com.hsbc.market.application.port.in.QueryStatisticsUseCase;
import com.hsbc.market.domain.MarketDomain.PropertyPage;
import com.hsbc.market.domain.MarketDomain.Selection;
import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

public class CachingMarketFacade implements ListPropertiesUseCase, QueryStatisticsUseCase {
  private final MarketQueryService service;
  private final Cache firstPage;
  private final Cache statistics;

  CachingMarketFacade(MarketQueryService service, CacheManager manager) {
    this.service = service;
    this.firstPage = required(manager, "firstPage");
    this.statistics = required(manager, "statistics");
  }

  @Override
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public PropertyPage list(Selection selection, int limit, String cursor) {
    if (cursor != null) return service.list(selection, limit, cursor);
    var resolved = service.resolve(selection);
    String key =
        "v2|"
            + resolved.datasetVersion().contentSha256()
            + '|'
            + resolved.queryFingerprint()
            + '|'
            + limit;
    return firstPage.get(key, () -> service.listResolved(resolved, limit, null));
  }

  @Override
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public StatisticsResult query(StatisticsCommand command) {
    var resolved =
        service.resolve(
            new Selection(command.requestedDatasetVersionId(), command.filters(), null));
    String key =
        QueryFingerprint.statistics(
            resolved, command.metrics(), command.groupBy(), command.order(), command.limit());
    return statistics.get(key, () -> service.queryResolved(resolved, command));
  }

  private static Cache required(CacheManager manager, String name) {
    Cache cache = manager.getCache(name);
    if (cache == null) throw new IllegalStateException("missing cache: " + name);
    return cache;
  }
}
