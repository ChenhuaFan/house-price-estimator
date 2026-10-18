package com.hsbc.market.model.query;

import com.hsbc.market.model.domain.MarketDomain.GroupBy;
import com.hsbc.market.model.domain.MarketDomain.GroupOrder;
import com.hsbc.market.model.domain.MarketDomain.Metric;
import java.util.List;

public record StatisticsSqlParameters(
    MarketSqlParameters scope,
    List<Metric> metrics,
    GroupBy groupBy,
    GroupOrder order,
    int limit) {}
