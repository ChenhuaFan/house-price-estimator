package com.hsbc.market.api;

import com.hsbc.market.application.port.in.QueryStatisticsUseCase;
import com.hsbc.market.contract.api.StatisticsApi;
import com.hsbc.market.contract.model.StatisticsQuery;
import com.hsbc.market.contract.model.StatisticsResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatisticsApiController implements StatisticsApi {
  private final QueryStatisticsUseCase useCase;
  private final ApiModelMapper mapper;

  public StatisticsApiController(QueryStatisticsUseCase useCase, ApiModelMapper mapper) {
    this.useCase = useCase;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<StatisticsResponse> queryStatistics(
      StatisticsQuery request, UUID correlationId) {
    return ResponseEntity.ok(mapper.toContract(useCase.query(mapper.toDomain(request))));
  }
}
