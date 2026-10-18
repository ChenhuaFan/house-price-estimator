package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

public interface PredictionGateway {
    Prediction predict(PropertyInput input, String correlationId);
}
