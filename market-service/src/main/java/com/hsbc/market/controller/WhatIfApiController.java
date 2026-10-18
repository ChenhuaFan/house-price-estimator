package com.hsbc.market.controller;

import com.hsbc.market.contract.api.WhatIfApi;
import com.hsbc.market.contract.model.PropertyInput;
import com.hsbc.market.contract.model.WhatIfResponse;
import com.hsbc.market.service.WhatIfService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements isolated v2 what-if predictions. */
@RestController
public class WhatIfApiController implements WhatIfApi {
  private final WhatIfService whatIfService;
  private final ApiModelMapper mapper;
  private final RequestCorrelation correlation;

  public WhatIfApiController(
      WhatIfService whatIfService, ApiModelMapper mapper, RequestCorrelation correlation) {
    this.whatIfService = whatIfService;
    this.mapper = mapper;
    this.correlation = correlation;
  }

  /** Calls the isolated model workflow without creating estimator history. */
  @Override
  public ResponseEntity<WhatIfResponse> calculateWhatIf(PropertyInput input, UUID correlationId) {
    UUID effective = correlationId == null ? correlation.current() : correlationId;

    return ResponseEntity.ok(
        mapper.toContract(whatIfService.predict(mapper.toDomain(input), effective)));
  }
}
