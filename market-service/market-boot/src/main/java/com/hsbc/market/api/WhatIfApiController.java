package com.hsbc.market.api;

import com.hsbc.market.application.port.in.WhatIfUseCase;
import com.hsbc.market.contract.api.WhatIfApi;
import com.hsbc.market.contract.model.PropertyInput;
import com.hsbc.market.contract.model.WhatIfResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhatIfApiController implements WhatIfApi {
  private final WhatIfUseCase useCase;
  private final ApiModelMapper mapper;
  private final RequestCorrelation correlation;

  public WhatIfApiController(
      WhatIfUseCase useCase, ApiModelMapper mapper, RequestCorrelation correlation) {
    this.useCase = useCase;
    this.mapper = mapper;
    this.correlation = correlation;
  }

  @Override
  public ResponseEntity<WhatIfResponse> calculateWhatIf(PropertyInput input, UUID correlationId) {
    UUID effective = correlationId == null ? correlation.current() : correlationId;
    return ResponseEntity.ok(mapper.toContract(useCase.predict(mapper.toDomain(input), effective)));
  }
}
