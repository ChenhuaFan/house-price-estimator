package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public final class MarketExports {
    private static final String[] HEADERS = {
        "id", "square_footage", "bedrooms", "bathrooms", "year_built", "lot_size",
        "distance_to_city_center", "school_rating", "price"
    };
    private static final String[] PDF_HEADERS = {
        "ID", "Square footage", "Bedrooms", "Bathrooms", "Year built", "Lot size",
        "Distance to city center", "School rating", "Actual price"
    };
    private static final float[] PDF_COLUMN_WIDTHS = {
        0.50f, 1.10f, 0.75f, 0.82f, 0.82f, 0.92f, 1.30f, 0.92f, 1.18f
    };

    private MarketExports() {}

    public static byte[] csv(List<PropertyRecord> records) {
        StringBuilder output = new StringBuilder(String.join(",", HEADERS)).append('\n');
        for (PropertyRecord record : records) {
            output.append(record.id()).append(',')
                    .append(record.squareFootage()).append(',')
                    .append(record.bedrooms()).append(',')
                    .append(record.bathrooms().toPlainString()).append(',')
                    .append(record.yearBuilt()).append(',')
                    .append(record.lotSize()).append(',')
                    .append(record.distanceToCityCenter().toPlainString()).append(',')
                    .append(record.schoolRating().toPlainString()).append(',')
                    .append(record.price().setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pdf(MarketAnalysis analysis, MarketQuery query, WhatIfResponse whatIf) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4.rotate(), 28, 28, 34, 34);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new PageNumberEvent());
            document.addTitle("Property Market Analysis");
            document.addSubject("Filtered housing market analysis");
            document.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font heading = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
            document.add(new Paragraph("Property Market Analysis", title));
            document.add(new Paragraph("Generated at: "
                    + DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))));
            document.add(new Paragraph("Dataset version: " + analysis.datasetVersionId()));
            document.add(new Paragraph("Matching records: " + analysis.matchingCount()));
            document.add(new Paragraph("Active filters: " + filterSummary(query)));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Aggregate statistics", heading));
            PdfPTable aggregates = new PdfPTable(5);
            aggregates.setWidthPercentage(100);
            addHeader(aggregates, "Count", "Minimum price", "Maximum price", "Average price", "Average sq. ft.");
            aggregates.addCell(Long.toString(analysis.aggregates().count()));
            aggregates.addCell(money(analysis.aggregates().minimumPrice()));
            aggregates.addCell(money(analysis.aggregates().maximumPrice()));
            aggregates.addCell(money(analysis.aggregates().averagePrice()));
            aggregates.addCell(analysis.aggregates().averageSquareFootage().toPlainString());
            document.add(aggregates);
            document.add(new Paragraph(" "));

            List<ChartPoint> chart = analysis.charts().getOrDefault("average_price_by_bedrooms", List.of());
            if (!chart.isEmpty()) {
                document.add(new Paragraph("Average price by bedroom count", heading));
                document.add(Image.getInstance(chartImage(chart)));
            }

            if (whatIf != null) {
                document.add(new Paragraph("What-if analysis", heading));
                addWhatIfInputs(document, whatIf.inputs());
                document.add(new Paragraph("Predicted price: USD "
                        + whatIf.prediction().predictedPrice() + " (model "
                        + whatIf.prediction().modelVersion() + ")"));
                for (Warning warning : whatIf.prediction().warnings()) {
                    document.add(new Paragraph(warningSummary(warning)));
                }
            }

            addPropertyPages(document, analysis.records(), heading);
            document.close();
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("PDF rendering failed", error);
        }
    }

    private static String filterSummary(MarketQuery query) {
        List<String> filters = new ArrayList<>();
        addRange(filters, "Price", query.priceMin(), query.priceMax(), true);
        addRange(filters, "Square footage", query.squareFootageMin(), query.squareFootageMax(), false);
        addRange(filters, "Bedrooms", query.bedroomsMin(), query.bedroomsMax(), false);
        addRange(filters, "Bathrooms", query.bathroomsMin(), query.bathroomsMax(), false);
        addRange(filters, "Year built", query.yearBuiltMin(), query.yearBuiltMax(), false);
        addRange(filters, "Lot size", query.lotSizeMin(), query.lotSizeMax(), false);
        addRange(filters, "Distance", query.distanceMin(), query.distanceMax(), false);
        addRange(filters, "School rating", query.schoolRatingMin(), query.schoolRatingMax(), false);
        if (filters.isEmpty()) {
            filters.add("None (all records)");
        }
        filters.add("Sort: " + displaySort(query.normalizedSort()) + " ("
                + ("desc".equals(query.normalizedDirection()) ? "descending" : "ascending") + ")");
        return String.join("; ", filters);
    }

    private static void addRange(
            List<String> filters, String label, Object minimum, Object maximum, boolean monetary) {
        if (minimum == null && maximum == null) {
            return;
        }
        String lower = displayValue(minimum, monetary);
        String upper = displayValue(maximum, monetary);
        if (minimum != null && maximum != null) {
            filters.add(label + ": " + lower + " to " + upper);
        } else if (minimum != null) {
            filters.add(label + ": at least " + lower);
        } else {
            filters.add(label + ": at most " + upper);
        }
    }

    private static String displayValue(Object value, boolean monetary) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return monetary ? money(decimal) : decimal.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String displaySort(String sort) {
        return switch (sort) {
            case "source_id" -> "ID";
            case "square_footage" -> "square footage";
            case "year_built" -> "year built";
            case "lot_size" -> "lot size";
            case "distance_to_city_center" -> "distance to city center";
            case "school_rating" -> "school rating";
            default -> sort;
        };
    }

    private static void addWhatIfInputs(Document document, PropertyInput input) throws Exception {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {1.15f, 0.85f, 1.15f, 0.85f});
        addLabelValue(table, "Square footage", input.squareFootage() + " sq. ft.");
        addLabelValue(table, "Bedrooms", input.bedrooms().toString());
        addLabelValue(table, "Bathrooms", input.bathrooms().toPlainString());
        addLabelValue(table, "Year built", input.yearBuilt().toString());
        addLabelValue(table, "Lot size", input.lotSize() + " sq. ft.");
        addLabelValue(table, "Distance", input.distanceToCityCenter().toPlainString() + " miles");
        addLabelValue(table, "School rating", input.schoolRating().toPlainString());
        addLabelValue(table, "Currency", "USD");
        document.add(table);
    }

    private static void addLabelValue(PdfPTable table, String label, String value) {
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
        table.addCell(pdfCell(label, labelFont, false));
        table.addCell(pdfCell(value, valueFont, false));
    }

    private static String warningSummary(Warning warning) {
        String range = warning.advisoryMin() == null && warning.advisoryMax() == null
                ? "documented advisory range"
                : String.valueOf(warning.advisoryMin()) + " to " + warning.advisoryMax();
        return "Advisory warning - " + warning.field() + " = " + warning.submittedValue()
                + " (advisory range " + range + "): " + warning.reason();
    }

    private static void addPropertyPages(Document document, List<PropertyRecord> records, Font heading)
            throws Exception {
        document.newPage();
        document.add(new Paragraph("Properties", heading));
        if (records.isEmpty()) {
            document.add(new Paragraph("No properties match the active filters."));
            return;
        }
        document.add(propertyTable(records));
    }

    private static PdfPTable propertyTable(List<PropertyRecord> records) throws Exception {
        PdfPTable table = new PdfPTable(PDF_HEADERS.length);
        table.setWidthPercentage(100);
        table.setWidths(PDF_COLUMN_WIDTHS);
        table.setHeaderRows(1);
        table.setSplitLate(false);
        table.setSplitRows(false);
        addHeader(table, PDF_HEADERS);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);
        for (PropertyRecord record : records) {
            addBodyRow(table, body,
                    Long.toString(record.id()),
                    Long.toString(record.squareFootage()),
                    Integer.toString(record.bedrooms()),
                    record.bathrooms().toPlainString(),
                    Integer.toString(record.yearBuilt()),
                    Long.toString(record.lotSize()),
                    record.distanceToCityCenter().toPlainString(),
                    record.schoolRating().toPlainString(),
                    money(record.price()));
        }
        return table;
    }

    private static void addBodyRow(PdfPTable table, Font font, String... values) {
        for (String value : values) {
            table.addCell(pdfCell(value, font, true));
        }
    }

    private static String money(BigDecimal value) {
        return "USD " + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void addHeader(PdfPTable table, String... values) {
        for (String value : values) {
            PdfPCell cell = pdfCell(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f), false);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBackgroundColor(new Color(226, 232, 240));
            table.addCell(cell);
        }
    }

    private static PdfPCell pdfCell(String value, Font font, boolean noWrap) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setPadding(3);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setNoWrap(noWrap);
        return cell;
    }

    private static byte[] chartImage(List<ChartPoint> points) throws Exception {
        int width = 720;
        int height = 180;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        BigDecimal maximum = points.stream().map(ChartPoint::value).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        int barWidth = Math.max(30, (width - 80) / points.size() - 20);
        for (int index = 0; index < points.size(); index++) {
            ChartPoint point = points.get(index);
            int barHeight = point.value().multiply(BigDecimal.valueOf(120))
                    .divide(maximum, 0, java.math.RoundingMode.HALF_UP).intValue();
            int x = 50 + index * (barWidth + 20);
            int y = 145 - barHeight;
            graphics.setColor(new Color(15, 118, 110));
            graphics.fillRoundRect(x, y, barWidth, barHeight, 8, 8);
            graphics.setColor(new Color(15, 23, 42));
            graphics.drawString(point.label(), x + barWidth / 2 - 4, 165);
            graphics.drawString(
                    "$" + point.value().setScale(0, RoundingMode.HALF_UP).toPlainString(),
                    x,
                    Math.max(15, y - 4));
        }
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static class PageNumberEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Phrase page = new Phrase("Page " + writer.getPageNumber(),
                    FontFactory.getFont(FontFactory.HELVETICA, 8));
            com.lowagie.text.pdf.ColumnText.showTextAligned(
                    writer.getDirectContent(), Element.ALIGN_CENTER, page,
                    (document.right() + document.left()) / 2, document.bottom() - 14, 0);
        }
    }
}
