package com.hsbc.market.api;

import com.hsbc.market.application.port.in.ListPropertiesUseCase;
import com.hsbc.market.contract.api.PropertiesApi;
import com.hsbc.market.contract.model.PropertyPage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PropertiesApiController implements PropertiesApi {
  private final ListPropertiesUseCase useCase;
  private final ApiModelMapper mapper;

  public PropertiesApiController(ListPropertiesUseCase useCase, ApiModelMapper mapper) {
    this.useCase = useCase;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<PropertyPage> listProperties(
      UUID correlationId,
      UUID datasetVersionId,
      String priceGte,
      String priceLte,
      Long squareFootageGte,
      Long squareFootageLte,
      Integer bedroomsGte,
      Integer bedroomsLte,
      String bathroomsGte,
      String bathroomsLte,
      Integer yearBuiltGte,
      Integer yearBuiltLte,
      Long lotSizeGte,
      Long lotSizeLte,
      String distanceGte,
      String distanceLte,
      String schoolRatingGte,
      String schoolRatingLte,
      String sort,
      Integer limit,
      String cursor) {
    var selection =
        mapper.propertySelection(
            datasetVersionId,
            priceGte,
            priceLte,
            squareFootageGte,
            squareFootageLte,
            bedroomsGte,
            bedroomsLte,
            bathroomsGte,
            bathroomsLte,
            yearBuiltGte,
            yearBuiltLte,
            lotSizeGte,
            lotSizeLte,
            distanceGte,
            distanceLte,
            schoolRatingGte,
            schoolRatingLte,
            sort);
    return ResponseEntity.ok(mapper.toContract(useCase.list(selection, limit, cursor)));
  }
}
