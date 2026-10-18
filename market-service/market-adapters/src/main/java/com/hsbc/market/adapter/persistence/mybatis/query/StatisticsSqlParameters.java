package com.hsbc.market.adapter.persistence.mybatis.query;

import com.hsbc.market.domain.MarketDomain.GroupBy;
import com.hsbc.market.domain.MarketDomain.GroupOrder;
import com.hsbc.market.domain.MarketDomain.Metric;
import java.util.List;

public record StatisticsSqlParameters(
    MarketSqlParameters scope,
    List<Metric> metrics,
    GroupBy groupBy,
    GroupOrder order,
    int limit) {}
