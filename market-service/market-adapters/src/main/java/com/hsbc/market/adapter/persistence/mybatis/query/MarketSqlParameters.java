package com.hsbc.market.adapter.persistence.mybatis.query;

import com.hsbc.market.domain.MarketDomain.Filters;
import com.hsbc.market.domain.MarketDomain.ResolvedSelection;
import java.math.BigDecimal;
import java.util.UUID;

public record MarketSqlParameters(
    UUID datasetVersionId,
    BigDecimal priceGte,
    BigDecimal priceLte,
    Long squareFootageGte,
    Long squareFootageLte,
    Integer bedroomsGte,
    Integer bedroomsLte,
    BigDecimal bathroomsGte,
    BigDecimal bathroomsLte,
    Integer yearBuiltGte,
    Integer yearBuiltLte,
    Long lotSizeGte,
    Long lotSizeLte,
    BigDecimal distanceGte,
    BigDecimal distanceLte,
    BigDecimal schoolRatingGte,
    BigDecimal schoolRatingLte) {

  public static MarketSqlParameters from(ResolvedSelection selection) {
    Filters f = selection.filters();
    return new MarketSqlParameters(
        selection.datasetVersion().id(),
        gte(f.price()),
        lte(f.price()),
        gte(f.squareFootage()),
        lte(f.squareFootage()),
        gte(f.bedrooms()),
        lte(f.bedrooms()),
        gte(f.bathrooms()),
        lte(f.bathrooms()),
        gte(f.yearBuilt()),
        lte(f.yearBuilt()),
        gte(f.lotSize()),
        lte(f.lotSize()),
        gte(f.distanceToCityCenter()),
        lte(f.distanceToCityCenter()),
        gte(f.schoolRating()),
        lte(f.schoolRating()));
  }

  private static <T extends Comparable<? super T>> T gte(
      com.hsbc.market.domain.MarketDomain.Range<T> range) {
    return range == null ? null : range.gte();
  }

  private static <T extends Comparable<? super T>> T lte(
      com.hsbc.market.domain.MarketDomain.Range<T> range) {
    return range == null ? null : range.lte();
  }
}
