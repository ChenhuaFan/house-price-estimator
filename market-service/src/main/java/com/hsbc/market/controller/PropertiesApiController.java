package com.hsbc.market.controller;

import com.hsbc.market.contract.api.PropertiesApi;
import com.hsbc.market.contract.model.PropertyPage;
import com.hsbc.market.service.MarketQueryService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the v2 property listing endpoint. */
@RestController
public class PropertiesApiController implements PropertiesApi {
  private final MarketQueryService marketQueryService;
  private final ApiModelMapper mapper;

  public PropertiesApiController(MarketQueryService marketQueryService, ApiModelMapper mapper) {
    this.marketQueryService = marketQueryService;
    this.mapper = mapper;
  }

  /** Resolves filters and returns one stable keyset-paginated property page. */
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

    return ResponseEntity.ok(mapper.toContract(marketQueryService.list(selection, limit, cursor)));
  }
}
