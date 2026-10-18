package com.hsbc.market.model.persistence;

import java.math.BigDecimal;

public record StatisticsRowRecord(
    BigDecimal groupExact,
    BigDecimal groupGte,
    BigDecimal groupLt,
    String groupLabel,
    String valuesJson) {}
