package com.hsbc.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketQueryParametersTest {
    @Test
    void defaultsAreNormalized() {
        MarketModels.MarketQuery query = new MarketQueryParameters().toQuery();

        assertThat(query.normalizedSort()).isEqualTo("source_id");
        assertThat(query.normalizedDirection()).isEqualTo("asc");
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    void equivalentDecimalAndDirectionInputsHaveTheSameCacheKey() {
        MarketQueryParameters first = new MarketQueryParameters();
        first.setPriceMin(new BigDecimal("100000.00"));
        first.setDirection("DESC");
        MarketQueryParameters second = new MarketQueryParameters();
        second.setPriceMin(new BigDecimal("100000"));
        second.setDirection("desc");

        assertThat(first.toQuery().cacheKey()).isEqualTo(second.toQuery().cacheKey());
    }

    @Test
    void allInvalidPagingAndOrderingInputsAreRejected() {
        MarketQueryParameters page = new MarketQueryParameters();
        page.setPage(-1);
        MarketQueryParameters small = new MarketQueryParameters();
        small.setSize(0);
        MarketQueryParameters large = new MarketQueryParameters();
        large.setSize(101);
        MarketQueryParameters direction = new MarketQueryParameters();
        direction.setDirection("sideways");
        MarketQueryParameters sort = new MarketQueryParameters();
        sort.setSort("drop table");

        assertThatThrownBy(page::toQuery).hasMessageContaining("page");
        assertThatThrownBy(small::toQuery).hasMessageContaining("size");
        assertThatThrownBy(large::toQuery).hasMessageContaining("size");
        assertThatThrownBy(direction::toQuery).hasMessageContaining("direction");
        assertThatThrownBy(sort::toQuery).hasMessageContaining("sort field");
    }

    @Test
    void everyInvertedRangeIsRejected() {
        assertInvalidRange(parameters -> {
            parameters.setPriceMin(BigDecimal.TEN);
            parameters.setPriceMax(BigDecimal.ONE);
        }, "price");
        assertInvalidRange(parameters -> {
            parameters.setSquareFootageMin(10L);
            parameters.setSquareFootageMax(1L);
        }, "square_footage");
        assertInvalidRange(parameters -> {
            parameters.setBedroomsMin(3);
            parameters.setBedroomsMax(2);
        }, "bedrooms");
        assertInvalidRange(parameters -> {
            parameters.setBathroomsMin(BigDecimal.TEN);
            parameters.setBathroomsMax(BigDecimal.ONE);
        }, "bathrooms");
        assertInvalidRange(parameters -> {
            parameters.setYearBuiltMin(2020);
            parameters.setYearBuiltMax(2000);
        }, "year_built");
        assertInvalidRange(parameters -> {
            parameters.setLotSizeMin(10L);
            parameters.setLotSizeMax(1L);
        }, "lot_size");
        assertInvalidRange(parameters -> {
            parameters.setDistanceMin(BigDecimal.TEN);
            parameters.setDistanceMax(BigDecimal.ONE);
        }, "distance_to_city_center");
        assertInvalidRange(parameters -> {
            parameters.setSchoolRatingMin(BigDecimal.TEN);
            parameters.setSchoolRatingMax(BigDecimal.ONE);
        }, "school_rating");
    }

    private void assertInvalidRange(
            java.util.function.Consumer<MarketQueryParameters> customizer, String field) {
        MarketQueryParameters parameters = new MarketQueryParameters();
        customizer.accept(parameters);

        assertThatThrownBy(parameters::toQuery).hasMessageContaining(field);
    }
}
