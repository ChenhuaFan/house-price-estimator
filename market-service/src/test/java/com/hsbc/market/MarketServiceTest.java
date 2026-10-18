package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {
    @Mock
    private MarketDataRepository repository;

    @Mock
    private PredictionGateway predictionGateway;

    private MarketService service;

    @BeforeEach
    void setUp() {
        service = new MarketService(repository, predictionGateway);
    }

    @Test
    void analysisAndVersionResolutionDelegateToTheRepository() {
        DatasetVersion version = MarketFixtures.version();
        MarketQuery query = MarketFixtures.query();
        MarketAnalysis analysis = MarketFixtures.analysis();
        when(repository.resolveVersion(MarketFixtures.DATASET_ID)).thenReturn(version);
        when(repository.analyse(version, query)).thenReturn(analysis);

        assertThat(service.resolveVersion(MarketFixtures.DATASET_ID)).isSameAs(version);
        assertThat(service.analysis(version, query)).isSameAs(analysis);
    }

    @Test
    void whatIfValidatesInputAndPropagatesPrediction() {
        PropertyInput input = MarketFixtures.input();
        Prediction prediction = MarketFixtures.prediction();
        when(predictionGateway.predict(input, "correlation")).thenReturn(prediction);

        WhatIfResponse result = service.whatIf(input, "correlation");

        assertThat(result.inputs()).isSameAs(input);
        assertThat(result.prediction()).isSameAs(prediction);
    }

    @Test
    void invalidBathroomStepNeverCallsTheModel() {
        PropertyInput input = new PropertyInput(
                1800L,
                3,
                new BigDecimal("2.2"),
                2005,
                6500L,
                new BigDecimal("4.125"),
                new BigDecimal("8.500"));

        assertThatThrownBy(() -> service.whatIf(input, "correlation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("increments of 0.5");
        verify(predictionGateway, never()).predict(any(), any());
    }

    @Test
    void csvUsesEveryMatchingRecordNotOnlyTheVisiblePage() {
        DatasetVersion version = MarketFixtures.version();
        MarketQuery query = MarketFixtures.query();
        when(repository.allMatching(version, query)).thenReturn(List.of(
                MarketFixtures.record(1, new BigDecimal("1.00")),
                MarketFixtures.record(2, new BigDecimal("2.00"))));

        String csv = new String(service.csv(version, query), StandardCharsets.UTF_8);

        assertThat(csv.lines()).hasSize(3);
        verify(repository).allMatching(version, query);
    }

    @Test
    void pdfRequestsTheFullFilteredResultSet() {
        DatasetVersion version = MarketFixtures.version();
        MarketQuery query = MarketFixtures.query();
        when(repository.analyse(eq(version), any(MarketQuery.class)))
                .thenReturn(MarketFixtures.analysis());
        ArgumentCaptor<MarketQuery> queryCaptor = ArgumentCaptor.forClass(MarketQuery.class);

        byte[] pdf = service.pdf(version, query, null);

        verify(repository).analyse(eq(version), queryCaptor.capture());
        assertThat(queryCaptor.getValue().page()).isZero();
        assertThat(queryCaptor.getValue().size()).isEqualTo(5000);
        assertThat(queryCaptor.getValue().datasetVersionId()).isEqualTo(version.id());
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
