package com.hsbc.market.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("marketDatabaseHealthIndicator")
public final class MarketDatabaseHealthIndicator implements HealthIndicator {
  private final JdbcTemplate queries;
  private final JdbcTemplate exports;

  public MarketDatabaseHealthIndicator(
      @Qualifier("marketQueryDataSource") DataSource queryDataSource,
      @Qualifier("marketExportDataSource") DataSource exportDataSource) {
    this.queries = new JdbcTemplate(queryDataSource);
    this.exports = new JdbcTemplate(exportDataSource);
  }

  @Override
  public Health health() {
    try {
      Boolean catalogReadable =
          queries.queryForObject(
              """
                    SELECT EXISTS (
                        SELECT 1 FROM catalog.dataset_versions WHERE is_active
                    )
                    """,
              Boolean.class);
      Boolean operationsWritable =
          exports.queryForObject(
              """
                    SELECT has_table_privilege(current_user, 'market_ops.export_jobs', 'INSERT')
                       AND has_table_privilege(current_user, 'market_ops.export_jobs', 'UPDATE')
                       AND has_table_privilege(current_user, 'market_ops.export_jobs', 'DELETE')
                    """,
              Boolean.class);
      if (!Boolean.TRUE.equals(catalogReadable)) {
        return Health.down().withDetail("catalog", "no active dataset").build();
      }
      if (!Boolean.TRUE.equals(operationsWritable)) {
        return Health.down().withDetail("market_ops", "runtime role is not writable").build();
      }
      return Health.up()
          .withDetail("catalog", "readable")
          .withDetail("market_ops", "writable")
          .build();
    } catch (Exception error) {
      return Health.down().withDetail("reason", "market database checks failed").build();
    }
  }
}
