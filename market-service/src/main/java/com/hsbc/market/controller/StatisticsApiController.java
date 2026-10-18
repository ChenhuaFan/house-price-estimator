package com.hsbc.market.controller;

import com.hsbc.market.contract.api.StatisticsApi;
import com.hsbc.market.contract.model.StatisticsQuery;
import com.hsbc.market.contract.model.StatisticsResponse;
import com.hsbc.market.service.StatisticsService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the v2 aggregate statistics endpoint. */
@RestController
public class StatisticsApiController implements StatisticsApi {
  private final StatisticsService statisticsService;
  private final ApiModelMapper mapper;

  public StatisticsApiController(StatisticsService statisticsService, ApiModelMapper mapper) {
    this.statisticsService = statisticsService;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<StatisticsResponse> queryStatistics(
      StatisticsQuery request, UUID correlationId) {
    return ResponseEntity.ok(mapper.toContract(statisticsService.query(mapper.toDomain(request))));
  }
}
