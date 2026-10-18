package com.hsbc.market.model.persistence;

import java.math.BigDecimal;

/** Database projection for one property observation. */
public record PropertyRecordRow(
    long sourceId,
    long squareFootage,
    int bedrooms,
    BigDecimal bathrooms,
    int yearBuilt,
    long lotSize,
    BigDecimal distanceToCityCenter,
    BigDecimal schoolRating,
    BigDecimal price) {}
