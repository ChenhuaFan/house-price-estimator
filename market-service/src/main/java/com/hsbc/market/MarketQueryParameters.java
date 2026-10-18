package com.hsbc.market;

import static com.hsbc.market.MarketModels.MarketQuery;

import java.math.BigDecimal;
import java.util.UUID;

public class MarketQueryParameters {
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
    private int page = 0;
    private int size = 20;
    private UUID datasetVersionId;

    public MarketQuery toQuery() {
        if (page < 0) throw new IllegalArgumentException("page must be at least zero");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be asc or desc");
        }
        return new MarketQuery(
                priceMin, priceMax, squareFootageMin, squareFootageMax, bedroomsMin, bedroomsMax,
                bathroomsMin, bathroomsMax, yearBuiltMin, yearBuiltMax, lotSizeMin, lotSizeMax,
                distanceMin, distanceMax, schoolRatingMin, schoolRatingMax,
                sort, direction, page, size, datasetVersionId);
    }

    public BigDecimal getPriceMin() { return priceMin; }
    public void setPriceMin(BigDecimal value) { this.priceMin = value; }
    public BigDecimal getPriceMax() { return priceMax; }
    public void setPriceMax(BigDecimal value) { this.priceMax = value; }
    public Long getSquareFootageMin() { return squareFootageMin; }
    public void setSquareFootageMin(Long value) { this.squareFootageMin = value; }
    public Long getSquareFootageMax() { return squareFootageMax; }
    public void setSquareFootageMax(Long value) { this.squareFootageMax = value; }
    public Integer getBedroomsMin() { return bedroomsMin; }
    public void setBedroomsMin(Integer value) { this.bedroomsMin = value; }
    public Integer getBedroomsMax() { return bedroomsMax; }
    public void setBedroomsMax(Integer value) { this.bedroomsMax = value; }
    public BigDecimal getBathroomsMin() { return bathroomsMin; }
    public void setBathroomsMin(BigDecimal value) { this.bathroomsMin = value; }
    public BigDecimal getBathroomsMax() { return bathroomsMax; }
    public void setBathroomsMax(BigDecimal value) { this.bathroomsMax = value; }
    public Integer getYearBuiltMin() { return yearBuiltMin; }
    public void setYearBuiltMin(Integer value) { this.yearBuiltMin = value; }
    public Integer getYearBuiltMax() { return yearBuiltMax; }
    public void setYearBuiltMax(Integer value) { this.yearBuiltMax = value; }
    public Long getLotSizeMin() { return lotSizeMin; }
    public void setLotSizeMin(Long value) { this.lotSizeMin = value; }
    public Long getLotSizeMax() { return lotSizeMax; }
    public void setLotSizeMax(Long value) { this.lotSizeMax = value; }
    public BigDecimal getDistanceMin() { return distanceMin; }
    public void setDistanceMin(BigDecimal value) { this.distanceMin = value; }
    public BigDecimal getDistanceMax() { return distanceMax; }
    public void setDistanceMax(BigDecimal value) { this.distanceMax = value; }
    public BigDecimal getSchoolRatingMin() { return schoolRatingMin; }
    public void setSchoolRatingMin(BigDecimal value) { this.schoolRatingMin = value; }
    public BigDecimal getSchoolRatingMax() { return schoolRatingMax; }
    public void setSchoolRatingMax(BigDecimal value) { this.schoolRatingMax = value; }
    public String getSort() { return sort; }
    public void setSort(String value) { this.sort = value; }
    public String getDirection() { return direction; }
    public void setDirection(String value) { this.direction = value; }
    public int getPage() { return page; }
    public void setPage(int value) { this.page = value; }
    public int getSize() { return size; }
    public void setSize(int value) { this.size = value; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID value) { this.datasetVersionId = value; }
}
