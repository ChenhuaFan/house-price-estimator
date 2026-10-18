package com.hsbc.market.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketDomainTest {
  @Test
  void validatesRangesAndPropertyDecimalRules() {
    assertThatThrownBy(() -> MarketDomain.Range.of(2, 1)).isInstanceOf(MarketException.class);
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
  }
}
