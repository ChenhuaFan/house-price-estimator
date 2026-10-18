package com.hsbc.market.config;

import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.MarketDomain.CursorPosition;
import com.hsbc.market.domain.MarketDomain.DatasetVersion;
import com.hsbc.market.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

final class CachingMarketReadPort implements MarketReadPort {
  private final MarketReadPort delegate;
  private final Cache datasetVersions;

  CachingMarketReadPort(MarketReadPort delegate, CacheManager cacheManager) {
    this.delegate = delegate;
    this.datasetVersions = required(cacheManager, "datasetVersion");
  }

  @Override
  public DatasetVersion resolveDatasetVersion(UUID requestedVersionId) {
    String key = requestedVersionId == null ? "active" : requestedVersionId.toString();
    return datasetVersions.get(key, () -> delegate.resolveDatasetVersion(requestedVersionId));
  }

  @Override
  public List<PropertyRecord> listPage(
      ResolvedSelection selection, CursorPosition cursor, int limitPlusOne) {
    return delegate.listPage(selection, cursor, limitPlusOne);
  }

  @Override
  public StatisticsResult queryStatistics(ResolvedSelection selection, StatisticsCommand command) {
    return delegate.queryStatistics(selection, command);
  }

  @Override
  public long count(ResolvedSelection selection) {
    return delegate.count(selection);
  }

  @Override
  public void stream(
      ResolvedSelection selection, int maximumRows, Consumer<PropertyRecord> consumer) {
    delegate.stream(selection, maximumRows, consumer);
  }

  private static Cache required(CacheManager manager, String name) {
    Cache cache = manager.getCache(name);
    if (cache == null) throw new IllegalStateException("missing cache: " + name);
    return cache;
  }
}
