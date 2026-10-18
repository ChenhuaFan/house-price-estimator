package com.hsbc.market.application;

import com.hsbc.market.application.port.in.WhatIfUseCase;
import com.hsbc.market.application.port.out.PredictionPort;
import com.hsbc.market.domain.MarketDomain.PropertyFeatures;
import com.hsbc.market.domain.MarketDomain.WhatIfResult;
import java.util.UUID;

public final class WhatIfService implements WhatIfUseCase {
  private final PredictionPort predictionPort;

  public WhatIfService(PredictionPort predictionPort) {
    this.predictionPort = predictionPort;
  }

  @Override
  public WhatIfResult predict(PropertyFeatures features, UUID correlationId) {
    return new WhatIfResult(features, predictionPort.predict(features, correlationId));
  }
}
