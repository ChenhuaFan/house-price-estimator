package com.hsbc.market.adapter.persistence.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.adapter.persistence.mybatis.query.MarketSqlParameters;
import com.hsbc.market.adapter.persistence.mybatis.query.PageSqlParameters;
import com.hsbc.market.adapter.persistence.mybatis.query.StatisticsSqlParameters;
import com.hsbc.market.adapter.persistence.mybatis.record.DatasetVersionRecord;
import com.hsbc.market.adapter.persistence.mybatis.record.PropertyRecordRow;
import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.domain.MarketDomain.CursorPosition;
import com.hsbc.market.domain.MarketDomain.DatasetVersion;
import com.hsbc.market.domain.MarketDomain.PropertyRecord;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.domain.MarketDomain.StatisticsCommand;
import com.hsbc.market.domain.MarketDomain.StatisticsGroup;
import com.hsbc.market.domain.MarketDomain.StatisticsGroupKey;
import com.hsbc.market.domain.MarketDomain.StatisticsResult;
import com.hsbc.market.domain.MarketException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.ibatis.session.ResultContext;

public final class MyBatisMarketReadAdapter implements MarketReadPort {
  private static final TypeReference<LinkedHashMap<String, BigDecimal>> VALUES_TYPE =
      new TypeReference<>() {};
  private final MarketQueryOperations mapper;
  private final ObjectMapper objectMapper;

  public MyBatisMarketReadAdapter(MarketQueryOperations mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public DatasetVersion resolveDatasetVersion(UUID requestedVersionId) {
    DatasetVersionRecord record =
        requestedVersionId == null
            ? mapper.findActiveVersion()
            : mapper.findVersion(requestedVersionId);
    if (record == null) throw MarketException.notFound("dataset version was not found");
    return new DatasetVersion(record.id(), record.contentSha256());
  }

  @Override
  public List<PropertyRecord> listPage(
      ResolvedSelection selection, CursorPosition cursor, int limitPlusOne) {
    return mapper.selectPage(PageSqlParameters.page(selection, cursor, limitPlusOne)).stream()
        .map(MyBatisMarketReadAdapter::toDomain)
        .toList();
  }

  @Override
  public StatisticsResult queryStatistics(ResolvedSelection selection, StatisticsCommand command) {
    var parameters =
        new StatisticsSqlParameters(
            MarketSqlParameters.from(selection),
            command.metrics(),
            command.groupBy(),
            command.order(),
            command.limit());
    List<StatisticsGroup> groups =
        mapper.selectStatistics(parameters).stream()
            .map(
                row ->
                    new StatisticsGroup(
                        command.groupBy() == null
                            ? StatisticsGroupKey.empty()
                            : new StatisticsGroupKey(
                                command.groupBy().field(),
                                row.groupExact(),
                                row.groupGte(),
                                row.groupLt(),
                                row.groupLabel()),
                        values(row.valuesJson())))
            .toList();
    return new StatisticsResult(selection.datasetVersion(), selection.queryFingerprint(), groups);
  }

  @Override
  public long count(ResolvedSelection selection) {
    return mapper.count(MarketSqlParameters.from(selection));
  }

  @Override
  public void stream(
      ResolvedSelection selection, int maximumRows, Consumer<PropertyRecord> consumer) {
    mapper.stream(
        PageSqlParameters.stream(selection, maximumRows),
        context -> {
          @SuppressWarnings("unchecked")
          ResultContext<PropertyRecordRow> typed = (ResultContext<PropertyRecordRow>) context;
          consumer.accept(toDomain(typed.getResultObject()));
        });
  }

  private Map<String, BigDecimal> values(String json) {
    try {
      return objectMapper.readValue(json, VALUES_TYPE);
    } catch (Exception error) {
      throw new IllegalStateException("database returned invalid statistics JSON", error);
    }
  }

  private static PropertyRecord toDomain(PropertyRecordRow row) {
    return new PropertyRecord(
        row.sourceId(),
        row.squareFootage(),
        row.bedrooms(),
        row.bathrooms(),
        row.yearBuilt(),
        row.lotSize(),
        row.distanceToCityCenter(),
        row.schoolRating(),
        row.price());
  }
}
