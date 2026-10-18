package com.hsbc.market.service;

import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.model.domain.MarketDomain;
import com.hsbc.market.model.v1.V1Models;
import com.hsbc.market.model.v1.V1QueryParameters;
import com.lowagie.text.Document;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Preserves the legacy v1 dashboard and export behavior over v2 domain services. */
@Service
public class V1CompatibilityService {
  private final MarketQueryService marketQueryService;
  private final StatisticsService statisticsService;
  private final WhatIfService whatIfService;
  private final MarketReadDao marketReadDao;

  public V1CompatibilityService(
      MarketQueryService marketQueryService,
      StatisticsService statisticsService,
      WhatIfService whatIfService,
      MarketReadDao marketReadDao) {
    this.marketQueryService = marketQueryService;
    this.statisticsService = statisticsService;
    this.whatIfService = whatIfService;
    this.marketReadDao = marketReadDao;
  }

  /** Builds the legacy market analysis response from stable v2 queries. */
  public V1Models.MarketAnalysis analysis(V1QueryParameters parameters) {
    MarketDomain.Selection selection = parameters.selection();
    MarketDomain.PropertyPage current = null;
    String cursor = null;
    for (int index = 0; index <= parameters.getPage(); index++) {
      current = marketQueryService.list(selection, parameters.getSize(), cursor);
      if (index < parameters.getPage()) {
        cursor = current.nextCursor();
        if (cursor == null) {
          break;
        }
      }
    }
    if (current == null) {
      throw new IllegalStateException("page was not resolved");
    }
    List<MarketDomain.Metric> metrics =
        List.of(
            new MarketDomain.Metric(
                "count", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL),
            new MarketDomain.Metric(
                "minimum_price", MarketDomain.MetricMethod.MIN, MarketDomain.MetricField.PRICE),
            new MarketDomain.Metric(
                "maximum_price", MarketDomain.MetricMethod.MAX, MarketDomain.MetricField.PRICE),
            new MarketDomain.Metric(
                "average_price", MarketDomain.MetricMethod.AVG, MarketDomain.MetricField.PRICE),
            new MarketDomain.Metric(
                "average_square_footage",
                MarketDomain.MetricMethod.AVG,
                MarketDomain.MetricField.SQUARE_FOOTAGE));
    MarketDomain.StatisticsResult summary =
        statisticsService.query(
            new MarketDomain.StatisticsCommand(
                selection.requestedDatasetVersionId(),
                selection.filters(),
                metrics,
                null,
                null,
                1));
    Map<String, BigDecimal> values = summary.groups().getFirst().values();
    MarketDomain.StatisticsResult bedrooms =
        statisticsService.query(
            new MarketDomain.StatisticsCommand(
                selection.requestedDatasetVersionId(),
                selection.filters(),
                List.of(
                    new MarketDomain.Metric(
                        "average_price",
                        MarketDomain.MetricMethod.AVG,
                        MarketDomain.MetricField.PRICE)),
                new MarketDomain.GroupBy(MarketDomain.GroupField.BEDROOMS, null),
                new MarketDomain.GroupOrder("group_key", MarketDomain.SortDirection.ASC),
                100));
    Map<String, List<V1Models.ChartPoint>> charts = new LinkedHashMap<>();
    charts.put(
        "average_price_by_bedrooms",
        bedrooms.groups().stream()
            .map(
                group ->
                    new V1Models.ChartPoint(
                        group.key().label(), group.values().get("average_price")))
            .toList());
    charts.put("property_count_by_school_band", schoolBands(selection));
    return new V1Models.MarketAnalysis(
        current.datasetVersion().id(),
        current.datasetVersion().contentSha256(),
        values.get("count").longValue(),
        parameters.getPage(),
        parameters.getSize(),
        current.items().stream().map(V1CompatibilityService::v1Record).toList(),
        new V1Models.Aggregates(
            values.get("count").longValue(),
            money(values.get("minimum_price")),
            money(values.get("maximum_price")),
            money(values.get("average_price")),
            scale(values.get("average_square_footage"), 2)),
        charts);
  }

  /** Maps a legacy what-if request through the current prediction workflow. */
  public V1Models.WhatIfResponse whatIf(V1Models.PropertyInput input, UUID correlationId) {
    MarketDomain.PropertyFeatures features =
        new MarketDomain.PropertyFeatures(
            input.squareFootage(),
            input.bedrooms(),
            input.bathrooms(),
            input.yearBuilt(),
            input.lotSize(),
            input.distanceToCityCenter(),
            input.schoolRating());
    return v1WhatIf(whatIfService.predict(features, correlationId));
  }

  /** Streams a legacy CSV response for the requested filters. */
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 120)
  public void writeCsv(V1QueryParameters parameters, OutputStream output) {
    try {
      MarketDomain.ResolvedSelection resolved = marketQueryService.resolve(parameters.selection());
      BufferedWriter writer =
          new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
      writer.write(
          "id,square_footage,bedrooms,bathrooms,year_built,lot_size,distance_to_city_center,school_rating,price\r\n");
      marketReadDao.stream(
          resolved,
          1_000_000,
          row -> {
            try {
              writer.write(
                  row.sourceId()
                      + ","
                      + row.squareFootage()
                      + ","
                      + row.bedrooms()
                      + ","
                      + row.bathrooms().setScale(1).toPlainString()
                      + ","
                      + row.yearBuilt()
                      + ","
                      + row.lotSize()
                      + ","
                      + row.distanceToCityCenter().setScale(3).toPlainString()
                      + ","
                      + row.schoolRating().setScale(3).toPlainString()
                      + ","
                      + row.price().setScale(2).toPlainString()
                      + "\r\n");
            } catch (java.io.IOException error) {
              throw new java.io.UncheckedIOException(error);
            }
          });
      writer.flush();
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  /** Streams a legacy PDF report with an optional prediction. */
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 120)
  public void writePdf(
      V1QueryParameters parameters, V1Models.WhatIfResponse prediction, OutputStream output) {
    try {
      MarketDomain.ResolvedSelection resolved = marketQueryService.resolve(parameters.selection());
      long count = marketReadDao.count(resolved);
      Document document = new Document(PageSize.A4.rotate(), 28, 28, 30, 30);
      PdfWriter.getInstance(document, output);
      document.open();
      document.add(
          new Paragraph(
              "Property Market Analysis", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18)));
      document.add(new Paragraph("Dataset: " + resolved.datasetVersion().id()));
      document.add(new Paragraph("Matching records: " + count));
      if (prediction != null && prediction.prediction() != null) {
        document.add(
            new Paragraph("What-if prediction: USD " + prediction.prediction().predictedPrice()));
      }
      PdfPTable[] table = {v1Table()};
      long[] rows = {0};
      marketReadDao.stream(
          resolved,
          5000,
          row -> {
            addV1Row(table[0], row);
            rows[0]++;
            if (rows[0] % 100 == 0) {
              try {
                document.add(table[0]);
                table[0] = v1Table();
              } catch (Exception error) {
                throw new IllegalStateException(error);
              }
            }
          });
      if (rows[0] % 100 != 0 || rows[0] == 0) {
        document.add(table[0]);
      }
      if (count > 5000) {
        document.add(new Paragraph("Showing first 5000 of " + count + " records."));
      }
      document.close();
    } catch (Exception error) {
      throw new IllegalStateException("cannot generate v1 PDF", error);
    }
  }

  private List<V1Models.ChartPoint> schoolBands(MarketDomain.Selection selection) {
    return List.of(
        new V1Models.ChartPoint(
            "<6", BigDecimal.valueOf(countWithSchool(selection, null, new BigDecimal("5.999")))),
        new V1Models.ChartPoint(
            "6-7.999",
            BigDecimal.valueOf(
                countWithSchool(selection, new BigDecimal("6.000"), new BigDecimal("7.999")))),
        new V1Models.ChartPoint(
            "8+", BigDecimal.valueOf(countWithSchool(selection, new BigDecimal("8.000"), null))));
  }

  private long countWithSchool(MarketDomain.Selection selection, BigDecimal gte, BigDecimal lte) {
    MarketDomain.Filters filters = selection.filters();
    MarketDomain.Range<BigDecimal> existing = filters.schoolRating();
    BigDecimal lower = max(existing == null ? null : existing.gte(), gte);
    BigDecimal upper = min(existing == null ? null : existing.lte(), lte);
    if (lower != null && upper != null && lower.compareTo(upper) > 0) {
      return 0;
    }
    MarketDomain.Filters narrowed =
        new MarketDomain.Filters(
            filters.price(),
            filters.squareFootage(),
            filters.bedrooms(),
            filters.bathrooms(),
            filters.yearBuilt(),
            filters.lotSize(),
            filters.distanceToCityCenter(),
            MarketDomain.Range.of(lower, upper));
    MarketDomain.StatisticsResult result =
        statisticsService.query(
            new MarketDomain.StatisticsCommand(
                selection.requestedDatasetVersionId(),
                narrowed,
                List.of(
                    new MarketDomain.Metric(
                        "count", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL)),
                null,
                null,
                1));
    return result.groups().getFirst().values().get("count").longValue();
  }

  private static V1Models.PropertyRecord v1Record(MarketDomain.PropertyRecord row) {
    return new V1Models.PropertyRecord(
        row.sourceId(),
        row.squareFootage(),
        row.bedrooms(),
        row.bathrooms(),
        row.yearBuilt(),
        row.lotSize(),
        row.distanceToCityCenter(),
        row.schoolRating(),
        row.price());
  }

  private static V1Models.WhatIfResponse v1WhatIf(MarketDomain.WhatIfResult result) {
    MarketDomain.PropertyFeatures input = result.inputs();
    return new V1Models.WhatIfResponse(
        new V1Models.PropertyInput(
            input.squareFootage(),
            input.bedrooms(),
            input.bathrooms(),
            input.yearBuilt(),
            input.lotSize(),
            input.distanceToCityCenter(),
            input.schoolRating()),
        new V1Models.Prediction(
            result.prediction().predictedPrice().setScale(2).toPlainString(),
            result.prediction().currency(),
            result.prediction().modelVersion(),
            result.prediction().warnings().stream()
                .map(
                    warning ->
                        new V1Models.Warning(
                            warning.field(),
                            warning.submittedValue(),
                            warning.advisoryMinimum(),
                            warning.advisoryMaximum(),
                            warning.message()))
                .toList()));
  }

  private static PdfPTable v1Table() {
    PdfPTable table = new PdfPTable(9);
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    for (String header :
        List.of(
            "ID",
            "Square footage",
            "Bedrooms",
            "Bathrooms",
            "Year built",
            "Lot size",
            "Distance",
            "School rating",
            "Price")) {
      table.addCell(
          new PdfPCell(new Phrase(header, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7))));
    }
    return table;
  }

  private static void addV1Row(PdfPTable table, MarketDomain.PropertyRecord row) {
    for (String value :
        List.of(
            Long.toString(row.sourceId()),
            Long.toString(row.squareFootage()),
            Integer.toString(row.bedrooms()),
            row.bathrooms().toPlainString(),
            Integer.toString(row.yearBuilt()),
            Long.toString(row.lotSize()),
            row.distanceToCityCenter().toPlainString(),
            row.schoolRating().toPlainString(),
            row.price().setScale(2).toPlainString())) {
      table.addCell(new PdfPCell(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA, 7))));
    }
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(2)
        : value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal scale(BigDecimal value, int scale) {
    return value == null
        ? BigDecimal.ZERO.setScale(scale)
        : value.setScale(scale, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal max(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.max(right);
  }

  private static BigDecimal min(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.min(right);
  }
}
