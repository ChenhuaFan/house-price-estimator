package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketExportsTest {
    @Test
    void csvUsesTheRequiredHeaderOrderAndCanonicalMoney() {
        byte[] bytes = MarketExports.csv(List.of(
                MarketFixtures.record(1, new BigDecimal("123.455")),
                MarketFixtures.record(2, new BigDecimal("0"))));

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(
                "id,square_footage,bedrooms,bathrooms,year_built,lot_size,"
                        + "distance_to_city_center,school_rating,price\n"
                        + "1,1801,3,2.5,2005,6500,4.125,8.500,123.46\n"
                        + "2,1802,3,2.5,2005,6500,4.125,8.500,0.00\n");
    }

    @Test
    void pdfIsLandscapeSearchableMultipageAndIncludesWhatIf() throws Exception {
        List<PropertyRecord> records = new ArrayList<>();
        for (int index = 1; index <= 120; index++) {
            records.add(MarketFixtures.record(index, new BigDecimal("250000.555")));
        }
        Map<String, List<ChartPoint>> charts = new LinkedHashMap<>();
        charts.put(
                "average_price_by_bedrooms",
                List.of(new ChartPoint("3", new BigDecimal("250000.555"))));
        MarketAnalysis analysis = new MarketAnalysis(
                MarketFixtures.DATASET_ID,
                MarketFixtures.DATASET_HASH,
                records.size(),
                0,
                5000,
                records,
                new Aggregates(
                        records.size(),
                        new BigDecimal("250000.555"),
                        new BigDecimal("250000.555"),
                        new BigDecimal("250000.555"),
                        new BigDecimal("1800.55")),
                charts);
        Warning warning = new Warning(
                "school_rating", "11", "1", "10", "outside advisory range");
        WhatIfResponse whatIf = new WhatIfResponse(
                MarketFixtures.input(),
                new Prediction(
                        "250000.56", "USD", "housing-linear-test", List.of(warning)));

        byte[] bytes = MarketExports.pdf(analysis, MarketFixtures.query(), whatIf);
        PdfReader reader = new PdfReader(bytes);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            String pageText = extractor.getTextFromPage(page);
            text.append(pageText);
            if (page > 1) {
                assertThat(pageText)
                        .contains("Square footage")
                        .contains("Distance to city center")
                        .contains("Actual price");
            }
        }
        Rectangle pageSize = reader.getPageSizeWithRotation(1);

        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(reader.getNumberOfPages()).isGreaterThan(1);
        assertThat(pageSize.getWidth()).isGreaterThan(pageSize.getHeight());
        assertThat(text.toString())
                .contains("Property Market Analysis")
                .contains("Matching records: 120")
                .contains("Properties")
                .contains("What-if analysis")
                .contains("Square footage")
                .contains("Distance")
                .contains("250000.56")
                .contains("housing-linear-test")
                .contains("outside advisory range")
                .contains("Advisory warning - school_rating = 11")
                .contains("Page 1")
                .contains("Page 2")
                .doesNotContain("PropertyInput[")
                .doesNotContain("Warning[")
                .doesNotContain("||||");
        reader.close();
    }

    @Test
    void pdfSupportsEmptyChartsAndNoWhatIf() throws Exception {
        MarketAnalysis source = MarketFixtures.analysis();
        MarketAnalysis analysis = new MarketAnalysis(
                source.datasetVersionId(),
                source.datasetContentSha256(),
                0,
                0,
                20,
                List.of(),
                new Aggregates(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                Map.of());

        PdfReader reader = new PdfReader(MarketExports.pdf(analysis, MarketFixtures.query(), null));
        PdfTextExtractor extractor = new PdfTextExtractor(reader);

        assertThat(extractor.getTextFromPage(1))
                .contains("Matching records: 0")
                .doesNotContain("What-if analysis");
        reader.close();
    }
}
