package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MarketService {
    private final MarketRepository repository;
    private final RestClient modelClient;

    public MarketService(
            MarketRepository repository,
            RestClient.Builder builder,
            @Value("${model-service.url}") String modelUrl,
            @Value("${model-service.connect-timeout}") Duration connectTimeout,
            @Value("${model-service.response-timeout}") Duration responseTimeout) {
        this.repository = repository;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(responseTimeout);
        this.modelClient = builder.baseUrl(modelUrl).requestFactory(requestFactory).build();
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
        PredictResponse response = modelClient.post()
                .uri("/api/v1/predict")
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PredictRequest(input))
                .retrieve()
                .body(PredictResponse.class);
        if (response == null || response.predictions() == null || response.predictions().size() != 1) {
            throw new ModelServiceException("Prediction service returned an invalid response");
        }
        return new WhatIfResponse(input, response.predictions().getFirst());
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

    public static class ModelServiceException extends RuntimeException {
        public ModelServiceException(String message) {
            super(message);
        }
    }
}
