package com.hsbc.market.config;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Configures the long-running export data source and transaction manager. */
@Configuration
@MapperScan(
    basePackages = "com.hsbc.market.mapper.export",
    sqlSessionTemplateRef = "exportSqlSessionTemplate")
public class ExportDataSourceConfiguration {
  @Bean
  DataSource marketExportDataSource(MarketProperties properties) {
    return QueryDataSourceConfiguration.dataSource(
        properties, properties.database().exportPoolSize(), "market-export");
  }

  @Bean
  SqlSessionFactory exportSqlSessionFactory(
      @Qualifier("marketExportDataSource") DataSource dataSource, MarketProperties properties)
      throws Exception {
    return QueryDataSourceConfiguration.factory(
        dataSource, properties.database().exportTimeout(), "classpath*:mapper/export/**/*.xml");
  }

  @Bean
  SqlSessionTemplate exportSqlSessionTemplate(
      @Qualifier("exportSqlSessionFactory") SqlSessionFactory factory) {
    return new SqlSessionTemplate(factory);
  }

  @Bean
  PlatformTransactionManager exportTransactionManager(
      @Qualifier("marketExportDataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }
}
