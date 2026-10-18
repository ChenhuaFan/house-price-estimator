package com.hsbc.market.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Configures the latency-bounded interactive query data source. */
@Configuration
@MapperScan(
    basePackages = "com.hsbc.market.mapper.query",
    sqlSessionTemplateRef = "querySqlSessionTemplate")
public class QueryDataSourceConfiguration {
  @Bean
  @Primary
  DataSource marketQueryDataSource(MarketProperties properties) {
    return dataSource(properties, properties.database().queryPoolSize(), "market-query");
  }

  @Bean
  @Primary
  SqlSessionFactory querySqlSessionFactory(
      @Qualifier("marketQueryDataSource") DataSource dataSource, MarketProperties properties)
      throws Exception {
    return factory(
        dataSource, properties.database().queryTimeout(), "classpath*:mapper/query/**/*.xml");
  }

  @Bean
  @Primary
  SqlSessionTemplate querySqlSessionTemplate(
      @Qualifier("querySqlSessionFactory") SqlSessionFactory factory) {
    return new SqlSessionTemplate(factory);
  }

  @Bean
  @Primary
  PlatformTransactionManager queryTransactionManager(
      @Qualifier("marketQueryDataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  static DataSource dataSource(MarketProperties properties, int poolSize, String name) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(properties.database().url());
    config.setUsername(properties.database().username());
    config.setPassword(properties.database().password());
    config.setPoolName(name);
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(name.equals("market-query") ? 1 : 0);
    config.setConnectionTimeout(properties.database().connectionTimeout().toMillis());
    // Interactive methods declare read-only transactions, while this pool also owns the short
    // export-job state transactions required to keep status polling isolated from export streams.
    config.setReadOnly(false);
    config.setAutoCommit(true);
    return new HikariDataSource(config);
  }

  static SqlSessionFactory factory(
      DataSource dataSource, java.time.Duration timeout, String mapperLocation) throws Exception {
    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setCacheEnabled(false);
    configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
    configuration.setDefaultStatementTimeout(Math.toIntExact(Math.max(1, timeout.toSeconds())));
    configuration.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
    bean.setDataSource(dataSource);
    bean.setConfiguration(configuration);
    bean.setTypeAliasesPackage("com.hsbc.market.model.persistence");
    bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(mapperLocation));
    return bean.getObject();
  }
}
