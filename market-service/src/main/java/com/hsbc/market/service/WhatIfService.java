package com.hsbc.market.service;

import com.hsbc.market.client.ModelServiceClient;
import com.hsbc.market.model.domain.MarketDomain.PropertyFeatures;
import com.hsbc.market.model.domain.MarketDomain.WhatIfResult;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Wraps isolated model predictions with the submitted scenario. */
@Service
public class WhatIfService {
  private final ModelServiceClient modelServiceClient;

  public WhatIfService(ModelServiceClient modelServiceClient) {
    this.modelServiceClient = modelServiceClient;
  }

  /** Predicts one scenario without persisting it as estimator history. */
  public WhatIfResult predict(PropertyFeatures features, UUID correlationId) {
    return new WhatIfResult(features, modelServiceClient.predict(features, correlationId));
  }
}
