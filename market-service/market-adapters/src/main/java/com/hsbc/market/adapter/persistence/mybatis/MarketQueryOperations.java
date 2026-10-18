package com.hsbc.market.adapter.persistence.mybatis;

import com.hsbc.market.adapter.persistence.mybatis.query.MarketSqlParameters;
import com.hsbc.market.adapter.persistence.mybatis.query.MarketSqlProvider;
import com.hsbc.market.adapter.persistence.mybatis.query.PageSqlParameters;
import com.hsbc.market.adapter.persistence.mybatis.query.StatisticsSqlParameters;
import com.hsbc.market.adapter.persistence.mybatis.record.DatasetVersionRecord;
import com.hsbc.market.adapter.persistence.mybatis.record.PropertyRecordRow;
import com.hsbc.market.adapter.persistence.mybatis.record.StatisticsRowRecord;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.session.ResultHandler;

public interface MarketQueryOperations {
  @Select(
      """
            SELECT dataset_version_id AS id, trim(content_sha256) AS content_sha256
            FROM catalog.dataset_versions
            WHERE is_active
            """)
  DatasetVersionRecord findActiveVersion();

  @Select(
      """
            SELECT dataset_version_id AS id, trim(content_sha256) AS content_sha256
            FROM catalog.dataset_versions
            WHERE dataset_version_id = #{id}
            """)
  DatasetVersionRecord findVersion(UUID id);

  @SelectProvider(type = MarketSqlProvider.class, method = "selectPage")
  List<PropertyRecordRow> selectPage(@Param("parameters") PageSqlParameters parameters);

  @SelectProvider(type = MarketSqlProvider.class, method = "statistics")
  List<StatisticsRowRecord> selectStatistics(
      @Param("parameters") StatisticsSqlParameters parameters);

  @SelectProvider(type = MarketSqlProvider.class, method = "count")
  long count(@Param("parameters") MarketSqlParameters parameters);

  @Options(fetchSize = 1000)
  @ResultType(PropertyRecordRow.class)
  @SelectProvider(type = MarketSqlProvider.class, method = "stream")
  void stream(
      @Param("parameters") PageSqlParameters parameters,
      ResultHandler<PropertyRecordRow> resultHandler);
}
