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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;

public final class MarketExports {
    private static final String[] HEADERS = {
        "id", "square_footage", "bedrooms", "bathrooms", "year_built", "lot_size",
        "distance_to_city_center", "school_rating", "price"
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
                    .append(record.price().setScale(2).toPlainString()).append('\n');
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
                document.add(new Paragraph("Inputs: " + whatIf.inputs()));
                document.add(new Paragraph("Predicted price: USD "
                        + whatIf.prediction().predictedPrice() + " (model "
                        + whatIf.prediction().modelVersion() + ")"));
                if (!whatIf.prediction().warnings().isEmpty()) {
                    document.add(new Paragraph("Warnings: " + whatIf.prediction().warnings()));
                }
            }

            document.add(new Paragraph("Properties", heading));
            PdfPTable table = new PdfPTable(HEADERS.length);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            addHeader(table, HEADERS);
            for (PropertyRecord record : analysis.records()) {
                table.addCell(Long.toString(record.id()));
                table.addCell(Long.toString(record.squareFootage()));
                table.addCell(Integer.toString(record.bedrooms()));
                table.addCell(record.bathrooms().toPlainString());
                table.addCell(Integer.toString(record.yearBuilt()));
                table.addCell(Long.toString(record.lotSize()));
                table.addCell(record.distanceToCityCenter().toPlainString());
                table.addCell(record.schoolRating().toPlainString());
                table.addCell(money(record.price()));
            }
            document.add(table);
            document.close();
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("PDF rendering failed", error);
        }
    }

    private static String filterSummary(MarketQuery query) {
        return query.cacheKey();
    }

    private static String money(BigDecimal value) {
        return "USD " + value.setScale(2).toPlainString();
    }

    private static void addHeader(PdfPTable table, String... values) {
        for (String value : values) {
            PdfPCell cell = new PdfPCell(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8)));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBackgroundColor(new Color(226, 232, 240));
            table.addCell(cell);
        }
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
            graphics.drawString("$" + point.value().setScale(0), x, Math.max(15, y - 4));
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
