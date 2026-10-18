package com.hsbc.market.adapter.export;

import com.hsbc.market.application.port.out.ExportJobPort;
import com.hsbc.market.application.port.out.ExportStorage;
import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.ExportDomain.Artifact;
import com.hsbc.market.domain.ExportDomain.Job;
import com.hsbc.market.domain.ExportDomain.RequestSnapshot;
import com.hsbc.market.domain.MarketDomain.PropertyRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

public final class CsvExportGenerator {
  private static final String HEADER =
      "source_id,square_footage,bedrooms,bathrooms,year_built,lot_size,"
          + "distance_to_city_center,school_rating,price\r\n";
  private final MarketReadPort readPort;
  private final ExportJobPort jobs;
  private final ExportStorage storage;
  private final long maxBytes;
  private final int maxRows;
  private final Duration maxRuntime;

  public CsvExportGenerator(
      MarketReadPort readPort,
      ExportJobPort jobs,
      ExportStorage storage,
      long maxBytes,
      int maxRows,
      Duration maxRuntime) {
    this.readPort = readPort;
    this.jobs = jobs;
    this.storage = storage;
    this.maxBytes = maxBytes;
    this.maxRows = maxRows;
    this.maxRuntime = maxRuntime;
  }

  public Artifact generate(Job job, RequestSnapshot snapshot) throws Exception {
    ExportStorage.TempFile temp = storage.createTemp(job.exportId());
    Path tempPath = temp.path();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    LimitedOutputStream limited = new LimitedOutputStream(temp.output(), maxBytes);
    long[] rows = {0};
    ExportGenerationSupport support = new ExportGenerationSupport(jobs, job.exportId(), maxRuntime);
    try (DigestOutputStream digestOutput = new DigestOutputStream(limited, digest);
        BufferedWriter writer =
            new BufferedWriter(new OutputStreamWriter(digestOutput, StandardCharsets.UTF_8))) {
      writer.write(HEADER);
      readPort.stream(
          snapshot.selection(),
          maxRows + 1,
          row -> {
            support.check();
            if (rows[0] >= maxRows) {
              throw new IllegalStateException("CSV export exceeded the configured row limit");
            }
            try {
              writer.write(csv(row));
              rows[0]++;
            } catch (IOException error) {
              throw new UncheckedIOException(error);
            }
          });
    } catch (Exception error) {
      storage.deleteTemp(tempPath);
      throw error;
    }
    support.check();
    String contentKey = storage.commit(job.exportId(), tempPath, "csv");
    return new Artifact(
        contentKey,
        "text/csv;charset=UTF-8",
        "property-market-analysis.csv",
        limited.count(),
        HexFormat.of().formatHex(digest.digest()),
        rows[0],
        rows[0],
        false);
  }

  private static String csv(PropertyRecord row) {
    return row.sourceId()
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
        + "\r\n";
  }
}
