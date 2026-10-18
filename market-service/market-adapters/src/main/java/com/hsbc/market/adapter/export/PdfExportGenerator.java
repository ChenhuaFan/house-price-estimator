package com.hsbc.market.adapter.export;

import com.hsbc.market.application.port.out.ExportJobPort;
import com.hsbc.market.application.port.out.ExportStorage;
import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.ExportDomain.Artifact;
import com.hsbc.market.domain.ExportDomain.Job;
import com.hsbc.market.domain.ExportDomain.PdfOptions;
import com.hsbc.market.domain.ExportDomain.RequestSnapshot;
import com.hsbc.market.domain.MarketDomain.Filters;
import com.hsbc.market.domain.MarketDomain.GroupBy;
import com.hsbc.market.domain.MarketDomain.GroupField;
import com.hsbc.market.domain.MarketDomain.GroupOrder;
import com.hsbc.market.domain.MarketDomain.Metric;
import com.hsbc.market.domain.MarketDomain.MetricField;
import com.hsbc.market.domain.MarketDomain.MetricMethod;
import com.hsbc.market.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.domain.MarketDomain.Range;
import com.hsbc.market.domain.MarketDomain.SortDirection;
import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.librepdf.openpdf.fonts.Liberation;

public final class PdfExportGenerator {
  private static final String[] HEADERS = {
    "ID",
    "Square footage",
    "Bedrooms",
    "Bathrooms",
    "Year built",
    "Lot size",
    "Distance",
    "School rating",
    "Price"
  };
  private final MarketReadPort readPort;
  private final ExportJobPort jobs;
  private final ExportStorage storage;
  private final long maxBytes;
  private final Duration maxRuntime;
  private final BaseFont regularFont;
  private final BaseFont boldFont;

  public PdfExportGenerator(
      MarketReadPort readPort,
      ExportJobPort jobs,
      ExportStorage storage,
      long maxBytes,
      Duration maxRuntime) {
    this.readPort = readPort;
    this.jobs = jobs;
    this.storage = storage;
    this.maxBytes = maxBytes;
    this.maxRuntime = maxRuntime;
    this.regularFont = loadFont(Liberation.SANS);
    this.boldFont = loadFont(Liberation.SANS_BOLD);
  }

  public Artifact generate(Job job, RequestSnapshot snapshot) throws Exception {
    PdfOptions options = snapshot.pdfOptions();
    if (options == null) throw new IllegalStateException("PDF options are missing");
    ExportStorage.TempFile temp = storage.createTemp(job.exportId());
    Path tempPath = temp.path();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    LimitedOutputStream limited = new LimitedOutputStream(temp.output(), maxBytes);
    ExportGenerationSupport support = new ExportGenerationSupport(jobs, job.exportId(), maxRuntime);
    long total = readPort.count(snapshot.selection());
    long[] written = {0};
    try (DigestOutputStream output = new DigestOutputStream(limited, digest)) {
      Document document = new Document(PageSize.A4.rotate(), 32, 32, 38, 36);
      PdfWriter writer = PdfWriter.getInstance(document, output);
      writer.setPageEvent(new PageNumbers(regularFont));
      document.open();
      addOverview(document, writer, snapshot, total);
      if (options.includeDetails() && options.detailLimit() > 0) {
        addDetails(document, snapshot, options.detailLimit(), total, written, support);
      }
      document.close();
    } catch (Exception error) {
      storage.deleteTemp(tempPath);
      throw error;
    }
    support.check();
    String contentKey = storage.commit(job.exportId(), tempPath, "pdf");
    boolean truncated = options.includeDetails() && total > options.detailLimit();
    return new Artifact(
        contentKey,
        "application/pdf",
        "property-market-analysis.pdf",
        limited.count(),
        HexFormat.of().formatHex(digest.digest()),
        total,
        written[0],
        truncated);
  }

  private void addOverview(
      Document document, PdfWriter writer, RequestSnapshot snapshot, long total) throws Exception {
    Font title = font(boldFont, 19, new Color(15, 45, 75));
    Font heading = font(boldFont, 12, new Color(15, 45, 75));
    Font body = font(regularFont, 10, Color.DARK_GRAY);
    document.add(new Paragraph("Property Market Analysis", title));
    document.add(
        new Paragraph(
            "Generated: "
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
            body));
    document.add(
        new Paragraph("Dataset version: " + snapshot.selection().datasetVersion().id(), body));
    document.add(
        new Paragraph(
            "Dataset SHA-256: " + snapshot.selection().datasetVersion().contentSha256(), body));
    document.add(
        new Paragraph("Query fingerprint: " + snapshot.selection().queryFingerprint(), body));
    document.add(new Paragraph("Active filters", heading));
    document.add(new Paragraph(describeFilters(snapshot.selection().filters()), body));
    document.add(
        new Paragraph(
            "Effective sort: "
                + snapshot.selection().sort().field().apiName()
                + ':'
                + snapshot.selection().sort().direction().name().toLowerCase(),
            body));
    document.add(new Paragraph("Matching records: " + total, body));
    document.add(new Paragraph("Summary statistics", heading));
    List<Metric> metrics = snapshot.pdfOptions().metrics();
    if (metrics != null && !metrics.isEmpty()) {
      StatisticsCommand command =
          new StatisticsCommand(
              snapshot.selection().datasetVersion().id(),
              snapshot.selection().filters(),
              metrics,
              null,
              null,
              1);
      StatisticsResult result = readPort.queryStatistics(snapshot.selection(), command);
      PdfPTable table = new PdfPTable(2);
      table.setWidthPercentage(65);
      table.setHorizontalAlignment(Element.ALIGN_LEFT);
      addHeader(table, "Metric", "Value");
      result
          .groups()
          .getFirst()
          .values()
          .forEach(
              (name, value) -> {
                table.addCell(cell(name, false));
                table.addCell(
                    cell(
                        value == null ? "n/a" : value.stripTrailingZeros().toPlainString(), false));
              });
      document.add(table);
    } else {
      document.add(new Paragraph("property_count: " + total, body));
    }
    addBedroomChart(document, writer, snapshot);
    if (snapshot.pdfOptions().whatIf() != null) {
      document.add(new Paragraph("What-if prediction", heading));
      document.add(
          new Paragraph(
              "Predicted price: USD "
                  + snapshot
                      .pdfOptions()
                      .whatIf()
                      .prediction()
                      .predictedPrice()
                      .setScale(2)
                      .toPlainString(),
              body));
      document.add(
          new Paragraph(
              "Model version: " + snapshot.pdfOptions().whatIf().prediction().modelVersion(),
              body));
    }
  }

  private void addBedroomChart(Document document, PdfWriter writer, RequestSnapshot snapshot)
      throws Exception {
    Metric average = new Metric("average_price", MetricMethod.AVG, MetricField.PRICE);
    StatisticsCommand chartCommand =
        new StatisticsCommand(
            snapshot.selection().datasetVersion().id(),
            snapshot.selection().filters(),
            List.of(average),
            new GroupBy(GroupField.BEDROOMS, null),
            new GroupOrder("group_key", SortDirection.ASC),
            20);
    StatisticsResult chart = readPort.queryStatistics(snapshot.selection(), chartCommand);
    if (chart.groups().isEmpty()) return;
    BigDecimal maximum =
        chart.groups().stream()
            .map(group -> group.values().get("average_price"))
            .filter(java.util.Objects::nonNull)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ONE);
    PdfTemplate template = writer.getDirectContent().createTemplate(620, 155);
    template.setColorFill(new Color(45, 112, 160));
    int index = 0;
    for (var group : chart.groups()) {
      BigDecimal value = group.values().get("average_price");
      if (value == null) continue;
      float width = value.divide(maximum, 6, java.math.RoundingMode.HALF_UP).floatValue() * 500f;
      float y = 135 - index * 22f;
      template.rectangle(85, y, width, 13);
      template.fill();
      template.beginText();
      template.setFontAndSize(regularFont, 8);
      template.setColorFill(Color.DARK_GRAY);
      template.setTextMatrix(5, y + 3);
      template.showText(group.key().label() + " bedrooms");
      template.endText();
      template.setColorFill(new Color(45, 112, 160));
      index++;
      if (index >= 6) break;
    }
    document.add(
        new Paragraph("Average price by bedrooms", font(boldFont, 12, new Color(15, 45, 75))));
    Image image = Image.getInstance(template);
    image.scaleToFit(620, 155);
    document.add(image);
  }

  private void addDetails(
      Document document,
      RequestSnapshot snapshot,
      int detailLimit,
      long total,
      long[] written,
      ExportGenerationSupport support)
      throws Exception {
    document.newPage();
    document.add(new Paragraph("Property details", font(boldFont, 12, new Color(15, 45, 75))));
    long expected = Math.min(total, detailLimit);
    document.add(
        new Paragraph(
            total > detailLimit
                ? "Showing first " + expected + " of " + total + " records."
                : "Showing all " + total + " records.",
            font(regularFont, 10, Color.DARK_GRAY)));
    PdfPTable[] table = {detailTable()};
    readPort.stream(
        snapshot.selection(),
        (int) expected,
        row -> {
          support.check();
          addRow(table[0], row);
          written[0]++;
          if (written[0] % 100 == 0) {
            try {
              document.add(table[0]);
              table[0] = detailTable();
            } catch (DocumentException error) {
              throw new IllegalStateException("cannot render PDF detail table", error);
            }
          }
        });
    if (written[0] % 100 != 0 || written[0] == 0) document.add(table[0]);
  }

  private PdfPTable detailTable() {
    PdfPTable table = new PdfPTable(HEADERS.length);
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    addHeader(table, HEADERS);
    return table;
  }

  private void addRow(PdfPTable table, PropertyRecord row) {
    String[] values = {
      Long.toString(row.sourceId()),
      Long.toString(row.squareFootage()),
      Integer.toString(row.bedrooms()),
      row.bathrooms().setScale(1).toPlainString(),
      Integer.toString(row.yearBuilt()),
      Long.toString(row.lotSize()),
      row.distanceToCityCenter().setScale(3).toPlainString(),
      row.schoolRating().setScale(3).toPlainString(),
      row.price().setScale(2).toPlainString()
    };
    for (String value : values) table.addCell(cell(value, false));
  }

  private void addHeader(PdfPTable table, String... values) {
    for (String value : values) table.addCell(cell(value, true));
  }

  private PdfPCell cell(String value, boolean header) {
    Font valueFont = font(header ? boldFont : regularFont, header ? 8 : 7, Color.DARK_GRAY);
    PdfPCell cell = new PdfPCell(new Phrase(value, valueFont));
    cell.setPadding(3);
    if (header) cell.setBackgroundColor(new Color(226, 232, 240));
    return cell;
  }

  private static Font font(BaseFont baseFont, float size, Color color) {
    return new Font(baseFont, size, Font.NORMAL, color);
  }

  private static BaseFont loadFont(Liberation liberation) {
    try {
      return liberation.create().getBaseFont();
    } catch (java.io.IOException error) {
      throw new IllegalStateException("packaged PDF font is unavailable", error);
    }
  }

  private static String describeFilters(Filters filters) {
    List<String> active = new ArrayList<>();
    addRange(active, "price", filters.price());
    addRange(active, "square_footage", filters.squareFootage());
    addRange(active, "bedrooms", filters.bedrooms());
    addRange(active, "bathrooms", filters.bathrooms());
    addRange(active, "year_built", filters.yearBuilt());
    addRange(active, "lot_size", filters.lotSize());
    addRange(active, "distance_to_city_center", filters.distanceToCityCenter());
    addRange(active, "school_rating", filters.schoolRating());
    return active.isEmpty() ? "None" : String.join("; ", active);
  }

  private static void addRange(List<String> active, String name, Range<?> range) {
    if (range == null) return;
    List<String> bounds = new ArrayList<>(2);
    if (range.gte() != null) bounds.add(">= " + range.gte());
    if (range.lte() != null) bounds.add("<= " + range.lte());
    active.add(name + ": " + String.join(", ", bounds));
  }

  private static final class PageNumbers extends PdfPageEventHelper {
    private final BaseFont font;

    private PageNumbers(BaseFont font) {
      this.font = font;
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte canvas = writer.getDirectContent();
      canvas.beginText();
      canvas.setFontAndSize(font, 8);
      canvas.showTextAligned(
          Element.ALIGN_CENTER,
          "Page " + writer.getPageNumber(),
          (document.left() + document.right()) / 2,
          document.bottom() - 18,
          0);
      canvas.endText();
    }
  }
}
