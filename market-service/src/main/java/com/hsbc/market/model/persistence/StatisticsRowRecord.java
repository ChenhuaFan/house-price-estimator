package com.hsbc.market.model.persistence;

import java.math.BigDecimal;

/** Database projection for one aggregate group and its metric values. */
public record StatisticsRowRecord(
    BigDecimal groupExact,
    BigDecimal groupGte,
    BigDecimal groupLt,
    String groupLabel,
    String valuesJson) {}
