package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import java.util.List;
import java.util.UUID;

public interface MarketDataRepository {
    DatasetVersion resolveVersion(UUID requested);

    MarketAnalysis analyse(DatasetVersion version, MarketQuery query);

    List<PropertyRecord> allMatching(DatasetVersion version, MarketQuery query);
}
