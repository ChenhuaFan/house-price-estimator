package com.hsbc.market.application.port.in;

import com.hsbc.market.domain.MarketDomain.PropertyPage;
import com.hsbc.market.domain.MarketDomain.Selection;

public interface ListPropertiesUseCase {
  PropertyPage list(Selection selection, int limit, String cursor);
}
