package com.hsbc.market.export;

import com.hsbc.market.config.MarketProperties;
import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.export.storage.LocalExportStorage;
import com.hsbc.market.model.domain.ExportDomain.Artifact;
import com.hsbc.market.model.domain.ExportDomain.Job;
import com.hsbc.market.model.domain.ExportDomain.RequestSnapshot;
import com.hsbc.market.model.domain.MarketDomain.PropertyRecord;
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
import org.springframework.stereotype.Component;

/** Streams a bounded property selection into a CSV artifact. */
@Component
public class CsvExportGenerator {
  private static final String HEADER =
      "source_id,square_footage,bedrooms,bathrooms,year_built,lot_size,"
          + "distance_to_city_center,school_rating,price\r\n";
  private final MarketReadDao marketReadDao;
  private final ExportJobDao jobs;
  private final LocalExportStorage storage;
  private final long maxBytes;
  private final int maxRows;
  private final Duration maxRuntime;

  public CsvExportGenerator(
      MarketReadDao marketReadDao,
      ExportJobDao jobs,
      LocalExportStorage storage,
      MarketProperties properties) {
    this.marketReadDao = marketReadDao;
    this.jobs = jobs;
    this.storage = storage;
    this.maxBytes = properties.export().maxBytes().toBytes();
    this.maxRows = properties.export().csvMaxRows();
    this.maxRuntime = properties.export().maxRuntime();
  }

  /** Generates and atomically commits the CSV artifact for one claimed job. */
  public Artifact generate(Job job, RequestSnapshot snapshot) throws Exception {
    LocalExportStorage.TempFile temp = storage.createTemp(job.exportId());
    Path tempPath = temp.path();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    LimitedOutputStream limited = new LimitedOutputStream(temp.output(), maxBytes);
    long[] rows = {0};
    ExportGenerationSupport support = new ExportGenerationSupport(jobs, job.exportId(), maxRuntime);
    try (DigestOutputStream digestOutput = new DigestOutputStream(limited, digest);
        BufferedWriter writer =
            new BufferedWriter(new OutputStreamWriter(digestOutput, StandardCharsets.UTF_8))) {
      writer.write(HEADER);
      marketReadDao.streamExport(
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
