package com.hsbc.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.hsbc.market.MarketModels.DatasetVersion;
import com.hsbc.market.MarketModels.MarketAnalysis;
import com.hsbc.market.MarketModels.MarketQuery;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class MarketCacheTest {
    private AnnotationConfigApplicationContext context;
    private MarketDataRepository repository;
    private MarketService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketDataRepository.class);
        PredictionGateway gateway = mock(PredictionGateway.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(MarketDataRepository.class, () -> repository);
        context.registerBean(PredictionGateway.class, () -> gateway);
        context.register(CachingTestConfiguration.class);
        context.refresh();
        service = context.getBean(MarketService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void equivalentNormalizedQueriesShareTheVersionedCacheEntry() {
        DatasetVersion version = MarketFixtures.version();
        MarketQuery first = query(new BigDecimal("100.0"), "", "ASC");
        MarketQuery equivalent = query(new BigDecimal("100.00"), null, "asc");
        MarketAnalysis analysis = MarketFixtures.analysis();
        when(repository.analyse(any(), any())).thenReturn(analysis);

        assertThat(service.analysis(version, first)).isSameAs(analysis);
        assertThat(service.analysis(version, equivalent)).isSameAs(analysis);

        verify(repository, times(1)).analyse(any(), any());
    }

    @Test
    void queryAndDatasetContentChangesCannotReuseAnOldEntry() {
        DatasetVersion firstVersion = MarketFixtures.version();
        DatasetVersion changedVersion = new DatasetVersion(
                UUID.randomUUID(), "housing-v2", "b".repeat(64), 2);
        MarketQuery firstQuery = query(new BigDecimal("100"), "price", "asc");
        MarketQuery changedQuery = query(new BigDecimal("200"), "price", "asc");
        when(repository.analyse(any(), any())).thenReturn(MarketFixtures.analysis());

        service.analysis(firstVersion, firstQuery);
        service.analysis(firstVersion, changedQuery);
        service.analysis(changedVersion, firstQuery);

        verify(repository, times(3)).analyse(any(), any());
    }

    private static MarketQuery query(BigDecimal priceMinimum, String sort, String direction) {
        return new MarketQuery(
                priceMinimum, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, sort, direction, 0, 20, MarketFixtures.DATASET_ID);
    }

    @Configuration
    @EnableCaching
    static class CachingTestConfiguration {
        @Bean
        CacheManager cacheManager() {
            CaffeineCacheManager manager = new CaffeineCacheManager("marketAnalysis");
            manager.setCaffeine(Caffeine.newBuilder().maximumSize(10));
            return manager;
        }

        @Bean
        MarketService marketService(
                MarketDataRepository repository, PredictionGateway predictionGateway) {
            return new MarketService(repository, predictionGateway);
        }
    }
}
