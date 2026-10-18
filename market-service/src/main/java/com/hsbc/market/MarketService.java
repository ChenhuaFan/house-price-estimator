package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class MarketService {
    private final MarketDataRepository repository;
    private final PredictionGateway predictionGateway;

    public MarketService(MarketDataRepository repository, PredictionGateway predictionGateway) {
        this.repository = repository;
        this.predictionGateway = predictionGateway;
    }

    public DatasetVersion resolveVersion(UUID requested) {
        return repository.resolveVersion(requested);
    }

    @Cacheable(cacheNames = "marketAnalysis", key = "#version.contentSha256() + ':' + #query.cacheKey()")
    public MarketAnalysis analysis(DatasetVersion version, MarketQuery query) {
        return repository.analyse(version, query);
    }

    public WhatIfResponse whatIf(PropertyInput input, String correlationId) {
        input.validateStep();
        return new WhatIfResponse(input, predictionGateway.predict(input, correlationId));
    }

    public byte[] csv(DatasetVersion version, MarketQuery query) {
        return MarketExports.csv(repository.allMatching(version, query));
    }

    public byte[] pdf(DatasetVersion version, MarketQuery query, WhatIfResponse whatIf) {
        MarketQuery fullQuery = new MarketQuery(
                query.priceMin(), query.priceMax(), query.squareFootageMin(), query.squareFootageMax(),
                query.bedroomsMin(), query.bedroomsMax(), query.bathroomsMin(), query.bathroomsMax(),
                query.yearBuiltMin(), query.yearBuiltMax(), query.lotSizeMin(), query.lotSizeMax(),
                query.distanceMin(), query.distanceMax(), query.schoolRatingMin(), query.schoolRatingMax(),
                query.sort(), query.direction(), 0, 5000, version.id());
        MarketAnalysis full = repository.analyse(version, fullQuery);
        return MarketExports.pdf(full, query, whatIf);
    }
}
