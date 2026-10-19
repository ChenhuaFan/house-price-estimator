package com.hsbc.market.service;

import com.hsbc.market.config.MarketProperties;
import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.model.domain.MarketDomain.CursorPosition;
import com.hsbc.market.model.domain.MarketDomain.PropertyPage;
import com.hsbc.market.model.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.model.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.model.domain.MarketDomain.Selection;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves live business-data revisions and serves stable keyset-paginated market pages. */
@Service
public class MarketQueryService {
  private final MarketReadDao marketReadDao;
  private final SignedCursorCodec cursorCodec;
  private final Cache firstPage;

  @Autowired
  public MarketQueryService(
      MarketReadDao marketReadDao, MarketProperties properties, CacheManager cacheManager) {
    this(
        marketReadDao,
        new SignedCursorCodec(properties.cursor().hmacSecret()),
        required(cacheManager, "firstPage"));
  }

  MarketQueryService(MarketReadDao marketReadDao, SignedCursorCodec cursorCodec, Cache firstPage) {
    this.marketReadDao = marketReadDao;
    this.cursorCodec = cursorCodec;
    this.firstPage = firstPage;
  }

  /** Returns one bounded page, caching only cursor-free first pages. */
  @Transactional(
      transactionManager = "queryTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      timeout = 3)
  public PropertyPage list(Selection selection, int limit, String cursor) {
    if (limit < 1 || limit > 200) {
      throw MarketException.validation("VALIDATION_ERROR", "limit must be between 1 and 200");
    }

    ResolvedSelection resolved = resolve(selection);
    if (cursor != null) {
      return listResolved(resolved, limit, cursor);
    }

    String key = "v2|" + resolved.dataRevision() + '|' + resolved.queryFingerprint() + '|' + limit;
    return firstPage.get(key, () -> listResolved(resolved, limit, null));
  }

  PropertyPage listResolved(ResolvedSelection resolved, int limit, String cursor) {
    CursorPosition position = cursor == null ? null : cursorCodec.decode(cursor, resolved);
    List<PropertyRecord> fetched = marketReadDao.listPage(resolved, position, limit + 1);
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
        resolved.dataRevision(), resolved.queryFingerprint(), items, limit, hasMore, next);
  }

  /** Pins a query to the current server-owned business-data revision. */
  @Transactional(
      transactionManager = "queryTransactionManager",
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      timeout = 3)
  public ResolvedSelection resolve(Selection selection) {
    long revision = marketReadDao.currentDataRevision();
    String fingerprint =
        QueryFingerprint.selection(revision, selection.filters(), selection.sort());

    return new ResolvedSelection(revision, selection.filters(), selection.sort(), fingerprint);
  }

  private static Cache required(CacheManager manager, String name) {
    Cache cache = manager.getCache(name);
    if (cache == null) {
      throw new IllegalStateException("missing cache: " + name);
    }
    return cache;
  }
}
