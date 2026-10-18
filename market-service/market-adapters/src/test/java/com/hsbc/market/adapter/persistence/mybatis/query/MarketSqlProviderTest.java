package com.hsbc.market.adapter.persistence.mybatis.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsbc.market.domain.MarketDomain;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketSqlProviderTest {
  @Test
  void enumeratesEverySortWithoutAcceptingRawIdentifiers() {
    for (MarketDomain.SortField field : MarketDomain.SortField.values()) {
      for (MarketDomain.SortDirection direction : MarketDomain.SortDirection.values()) {
        PageSqlParameters parameters =
            new PageSqlParameters(scope(), field, direction, "10", 7L, 51);
        String sql = MarketSqlProvider.selectPage(Map.of("parameters", parameters));

        assertThat(sql)
            .contains("dataset_version_id = #{parameters.scope.datasetVersionId}")
            .contains("LIMIT #{parameters.limit}")
            .doesNotContain(";", "--");
        assertThat(sql).contains(direction == MarketDomain.SortDirection.ASC ? " ASC" : " DESC");
      }
    }
  }

  @Test
  void statisticsCompileAllMetricsIntoOneStatement() {
    StatisticsSqlParameters parameters =
        new StatisticsSqlParameters(
            scope(),
            List.of(
                new MarketDomain.Metric(
                    "count", MarketDomain.MetricMethod.COUNT, MarketDomain.MetricField.ALL),
                new MarketDomain.Metric(
                    "average_price", MarketDomain.MetricMethod.AVG, MarketDomain.MetricField.PRICE),
                new MarketDomain.Metric(
                    "deviation",
                    MarketDomain.MetricMethod.STDDEV_POP,
                    MarketDomain.MetricField.PRICE)),
            new MarketDomain.GroupBy(MarketDomain.GroupField.SQUARE_FOOTAGE, new BigDecimal("500")),
            new MarketDomain.GroupOrder("average_price", MarketDomain.SortDirection.DESC),
            100);

    String sql = MarketSqlProvider.statistics(Map.of("parameters", parameters));

    assertThat(sql)
        .contains("jsonb_build_object", "count(*)", "avg(price)", "stddev_pop(price)")
        .contains("GROUP BY floor(square_footage")
        .contains("ORDER BY avg(price) DESC")
        .containsOnlyOnce(" FROM catalog.housing_records ");
  }

  private static MarketSqlParameters scope() {
    return new MarketSqlParameters(
        UUID.randomUUID(),
        new BigDecimal("100.00"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
