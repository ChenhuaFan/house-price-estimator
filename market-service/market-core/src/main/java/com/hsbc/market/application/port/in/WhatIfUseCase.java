package com.hsbc.market.application.port.in;

import com.hsbc.market.domain.MarketDomain.PropertyFeatures;
import com.hsbc.market.domain.MarketDomain.WhatIfResult;
import java.util.UUID;

public interface WhatIfUseCase {
  WhatIfResult predict(PropertyFeatures features, UUID correlationId);
}
