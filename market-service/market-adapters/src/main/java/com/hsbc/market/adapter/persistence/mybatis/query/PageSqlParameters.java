package com.hsbc.market.adapter.persistence.mybatis.query;

import com.hsbc.market.domain.MarketDomain.CursorPosition;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketDomain.SortDirection;
import com.hsbc.market.domain.MarketDomain.SortField;

public record PageSqlParameters(
    MarketSqlParameters scope,
    SortField sortField,
    SortDirection direction,
    String cursorSortValue,
    Long cursorSourceId,
    int limit) {
  public static PageSqlParameters page(
      ResolvedSelection selection, CursorPosition cursor, int limit) {
    return new PageSqlParameters(
        MarketSqlParameters.from(selection),
        selection.sort().field(),
        selection.sort().direction(),
        cursor == null ? null : cursor.sortValue(),
        cursor == null ? null : cursor.sourceId(),
        limit);
  }

  public static PageSqlParameters stream(ResolvedSelection selection, int maximumRows) {
    return page(selection, null, maximumRows);
  }
}
