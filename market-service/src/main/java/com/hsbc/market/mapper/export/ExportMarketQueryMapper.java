package com.hsbc.market.mapper.export;

import com.hsbc.market.model.persistence.PropertyRecordRow;
import com.hsbc.market.model.persistence.StatisticsRowRecord;
import com.hsbc.market.model.query.MarketSqlParameters;
import com.hsbc.market.model.query.PageSqlParameters;
import com.hsbc.market.model.query.StatisticsSqlParameters;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** MyBatis queries used by long-running export generation. */
@Mapper
public interface ExportMarketQueryMapper {
  long currentDataRevision();

  List<StatisticsRowRecord> selectStatistics(
      @Param("parameters") StatisticsSqlParameters parameters);

  long count(@Param("parameters") MarketSqlParameters parameters);

  void stream(
      @Param("parameters") PageSqlParameters parameters,
      ResultHandler<PropertyRecordRow> resultHandler);
}
