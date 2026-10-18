package com.hsbc.market.application.port.in;

import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;

public interface QueryStatisticsUseCase {
  StatisticsResult query(StatisticsCommand command);
}
