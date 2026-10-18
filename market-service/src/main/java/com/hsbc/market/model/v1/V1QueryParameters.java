package com.hsbc.market.model.v1;

import com.hsbc.market.exception.MarketException;
import com.hsbc.market.model.domain.MarketDomain;
import java.math.BigDecimal;
import java.util.UUID;

/** Binds legacy flat query parameters into the current market selection model. */
public final class V1QueryParameters {
  private BigDecimal priceMin;
  private BigDecimal priceMax;
  private Long squareFootageMin;
  private Long squareFootageMax;
  private Integer bedroomsMin;
  private Integer bedroomsMax;
  private BigDecimal bathroomsMin;
  private BigDecimal bathroomsMax;
  private Integer yearBuiltMin;
  private Integer yearBuiltMax;
  private Long lotSizeMin;
  private Long lotSizeMax;
  private BigDecimal distanceMin;
  private BigDecimal distanceMax;
  private BigDecimal schoolRatingMin;
  private BigDecimal schoolRatingMax;
  private String sort = "source_id";
  private String direction = "asc";
  private int page;
  private int size = 20;
  private UUID datasetVersionId;

  public MarketDomain.Selection selection() {
    if (page < 0 || size < 1 || size > 100) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "page or size is outside the supported range");
    }
    return new MarketDomain.Selection(
        datasetVersionId,
        new MarketDomain.Filters(
            MarketDomain.Range.of(priceMin, priceMax),
            MarketDomain.Range.of(squareFootageMin, squareFootageMax),
            MarketDomain.Range.of(bedroomsMin, bedroomsMax),
            MarketDomain.Range.of(bathroomsMin, bathroomsMax),
            MarketDomain.Range.of(yearBuiltMin, yearBuiltMax),
            MarketDomain.Range.of(lotSizeMin, lotSizeMax),
            MarketDomain.Range.of(distanceMin, distanceMax),
            MarketDomain.Range.of(schoolRatingMin, schoolRatingMax)),
        MarketDomain.Sort.parse(
            (sort == null ? "source_id" : sort)
                + ':'
                + (direction == null ? "asc" : direction.toLowerCase())));
  }

  public BigDecimal getPriceMin() {
    return priceMin;
  }

  public void setPriceMin(BigDecimal value) {
    priceMin = value;
  }

  public BigDecimal getPriceMax() {
    return priceMax;
  }

  public void setPriceMax(BigDecimal value) {
    priceMax = value;
  }

  public Long getSquareFootageMin() {
    return squareFootageMin;
  }

  public void setSquareFootageMin(Long value) {
    squareFootageMin = value;
  }

  public Long getSquareFootageMax() {
    return squareFootageMax;
  }

  public void setSquareFootageMax(Long value) {
    squareFootageMax = value;
  }

  public Integer getBedroomsMin() {
    return bedroomsMin;
  }

  public void setBedroomsMin(Integer value) {
    bedroomsMin = value;
  }

  public Integer getBedroomsMax() {
    return bedroomsMax;
  }

  public void setBedroomsMax(Integer value) {
    bedroomsMax = value;
  }

  public BigDecimal getBathroomsMin() {
    return bathroomsMin;
  }

  public void setBathroomsMin(BigDecimal value) {
    bathroomsMin = value;
  }

  public BigDecimal getBathroomsMax() {
    return bathroomsMax;
  }

  public void setBathroomsMax(BigDecimal value) {
    bathroomsMax = value;
  }

  public Integer getYearBuiltMin() {
    return yearBuiltMin;
  }

  public void setYearBuiltMin(Integer value) {
    yearBuiltMin = value;
  }

  public Integer getYearBuiltMax() {
    return yearBuiltMax;
  }

  public void setYearBuiltMax(Integer value) {
    yearBuiltMax = value;
  }

  public Long getLotSizeMin() {
    return lotSizeMin;
  }

  public void setLotSizeMin(Long value) {
    lotSizeMin = value;
  }

  public Long getLotSizeMax() {
    return lotSizeMax;
  }

  public void setLotSizeMax(Long value) {
    lotSizeMax = value;
  }

  public BigDecimal getDistanceMin() {
    return distanceMin;
  }

  public void setDistanceMin(BigDecimal value) {
    distanceMin = value;
  }

  public BigDecimal getDistanceMax() {
    return distanceMax;
  }

  public void setDistanceMax(BigDecimal value) {
    distanceMax = value;
  }

  public BigDecimal getSchoolRatingMin() {
    return schoolRatingMin;
  }

  public void setSchoolRatingMin(BigDecimal value) {
    schoolRatingMin = value;
  }

  public BigDecimal getSchoolRatingMax() {
    return schoolRatingMax;
  }

  public void setSchoolRatingMax(BigDecimal value) {
    schoolRatingMax = value;
  }

  public String getSort() {
    return sort;
  }

  public void setSort(String value) {
    sort = value;
  }

  public String getDirection() {
    return direction;
  }

  public void setDirection(String value) {
    direction = value;
  }

  public int getPage() {
    return page;
  }

  public void setPage(int value) {
    page = value;
  }

  public int getSize() {
    return size;
  }

  public void setSize(int value) {
    size = value;
  }

  public UUID getDatasetVersionId() {
    return datasetVersionId;
  }

  public void setDatasetVersionId(UUID value) {
    datasetVersionId = value;
  }
}
