package com.hsbc.market.model.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsbc.market.exception.MarketException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketDomainTest {
  @Test
  void validatesRangesAndPropertyDecimalRules() {
    assertThatThrownBy(() -> MarketDomain.Range.of(2, 1)).isInstanceOf(MarketException.class);
    assertThat(MarketDomain.Range.of(null, null)).isNull();
    assertThat(MarketDomain.Range.of(1, 2)).isEqualTo(new MarketDomain.Range<>(1, 2));
    assertInvalidFeatures(0, 2, "1.0", 2000, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, -1, "1.0", 2000, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, null, 2000, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, "0.4", 2000, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 0, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 10000, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 2000, -1, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 2000, 5000, null, "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 2000, 5000, "-0.001", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 2000, 5000, "1.000", null);
    assertThatThrownBy(
            () ->
                new MarketDomain.PropertyFeatures(
                    1000,
                    2,
                    new BigDecimal("1.3"),
                    2000,
                    5000,
                    new BigDecimal("1.000"),
                    new BigDecimal("8.000")))
        .isInstanceOf(MarketException.class);
    assertInvalidFeatures(1000, 2, "1.00", 2000, 5000, "1.000", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 2000, 5000, "1.0000", "8.000");
    assertInvalidFeatures(1000, 2, "1.0", 2000, 5000, "1.000", "8.0000");
    assertThat(
            new MarketDomain.PropertyFeatures(
                1000,
                2,
                new BigDecimal("1.5"),
                2000,
                5000,
                new BigDecimal("1.000"),
                new BigDecimal("8.000")))
        .isNotNull();
  }

  @Test
  void normalizesSortAndRecordSortValues() {
    assertThat(MarketDomain.Sort.parse(null))
        .isEqualTo(
            new MarketDomain.Sort(
                MarketDomain.SortField.SOURCE_ID, MarketDomain.SortDirection.ASC));
    MarketDomain.PropertyRecord row =
        new MarketDomain.PropertyRecord(
            1,
            1000,
            2,
            new BigDecimal("2.0"),
            2000,
            5000,
            new BigDecimal("1.000"),
            new BigDecimal("8.000"),
            new BigDecimal("250000.00"));
    assertThat(row.sortValue(MarketDomain.SortField.PRICE)).isEqualTo("250000");
    for (MarketDomain.SortField field : MarketDomain.SortField.values()) {
      assertThat(row.sortValue(field)).isNotBlank();
      assertThat(MarketDomain.SortField.fromApi(field.apiName())).isEqualTo(field);
    }
    assertThat(MarketDomain.Sort.parse("price:desc"))
        .isEqualTo(
            new MarketDomain.Sort(MarketDomain.SortField.PRICE, MarketDomain.SortDirection.DESC));
    assertThat(new MarketDomain.Sort(null, null))
        .isEqualTo(
            new MarketDomain.Sort(
                MarketDomain.SortField.SOURCE_ID, MarketDomain.SortDirection.ASC));
    assertThat(new MarketDomain.Selection(null, null, null).filters())
        .isEqualTo(MarketDomain.Filters.empty());
    assertThatThrownBy(() -> MarketDomain.Sort.parse("price")).isInstanceOf(MarketException.class);
    assertThatThrownBy(() -> MarketDomain.Sort.parse("price:sideways"))
        .isInstanceOf(MarketException.class);
    assertThatThrownBy(() -> MarketDomain.Sort.parse("unknown:asc"))
        .isInstanceOf(MarketException.class);
  }

  private static void assertInvalidFeatures(
      long squareFootage,
      int bedrooms,
      String bathrooms,
      int yearBuilt,
      long lotSize,
      String distance,
      String schoolRating) {
    assertThatThrownBy(
            () ->
                new MarketDomain.PropertyFeatures(
                    squareFootage,
                    bedrooms,
                    bathrooms == null ? null : new BigDecimal(bathrooms),
                    yearBuilt,
                    lotSize,
                    distance == null ? null : new BigDecimal(distance),
                    schoolRating == null ? null : new BigDecimal(schoolRating)))
        .isInstanceOf(MarketException.class);
  }
}
