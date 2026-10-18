package com.hsbc.market.application;

import com.hsbc.market.domain.MarketDomain.Filters;
import com.hsbc.market.domain.MarketDomain.GroupBy;
import com.hsbc.market.domain.MarketDomain.GroupOrder;
import com.hsbc.market.domain.MarketDomain.Metric;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketDomain.Sort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class QueryFingerprint {
  private QueryFingerprint() {}

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

  private static String range(com.hsbc.market.domain.MarketDomain.Range<?> range) {
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
