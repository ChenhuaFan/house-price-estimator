package com.hsbc.market.service;

import com.hsbc.market.model.domain.MarketDomain.Filters;
import com.hsbc.market.model.domain.MarketDomain.GroupBy;
import com.hsbc.market.model.domain.MarketDomain.GroupOrder;
import com.hsbc.market.model.domain.MarketDomain.Metric;
import com.hsbc.market.model.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.model.domain.MarketDomain.Sort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Produces canonical fingerprints that bind caches, cursors, and exports to query state. */
public final class QueryFingerprint {
  private QueryFingerprint() {}

  /** Fingerprints one dataset, filter, and sort selection. */
  public static String selection(String contentSha256, Filters filters, Sort sort) {
    return digest(
        "v2|"
            + contentSha256
            + '|'
            + filters(filters)
            + '|'
            + sort.field().apiName()
            + ':'
            + sort.direction().name());
  }

  /** Extends a resolved selection fingerprint with aggregation settings. */
  public static String statistics(
      ResolvedSelection selection,
      List<Metric> metrics,
      GroupBy groupBy,
      GroupOrder order,
      int limit) {
    String metricText =
        metrics.stream()
            .map(metric -> metric.id() + ':' + metric.method() + ':' + metric.field().apiName())
            .reduce((left, right) -> left + ',' + right)
            .orElse("");
    String group = groupBy == null ? "" : groupBy.field() + ":" + decimal(groupBy.fixedWidth());
    String ordering = order == null ? "" : order.by() + ':' + order.direction();
    return digest(
        selection.queryFingerprint()
            + '|'
            + metricText
            + '|'
            + group
            + '|'
            + ordering
            + '|'
            + limit);
  }

  /** Serializes filters into a deterministic canonical string. */
  public static String filters(Filters filters) {
    return String.join(
        "|",
        range(filters.price()),
        range(filters.squareFootage()),
        range(filters.bedrooms()),
        range(filters.bathrooms()),
        range(filters.yearBuilt()),
        range(filters.lotSize()),
        range(filters.distanceToCityCenter()),
        range(filters.schoolRating()));
  }

  private static String range(com.hsbc.market.model.domain.MarketDomain.Range<?> range) {
    return range == null ? ":" : value(range.gte()) + ':' + value(range.lte());
  }

  private static String value(Object value) {
    return value instanceof BigDecimal decimal
        ? decimal(decimal)
        : value == null ? "" : value.toString();
  }

  private static String decimal(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  /** Returns a lowercase SHA-256 identifier for canonical text. */
  public static String digest(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
