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

@Configuration
@MapperScan(
    basePackages = "com.hsbc.market.adapter.persistence.mybatis.export",
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
    return QueryDataSourceConfiguration.factory(dataSource, properties.database().exportTimeout());
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
