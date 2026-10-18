package com.hsbc.market.adapter.persistence.mybatis.record;

import java.math.BigDecimal;

public record StatisticsRowRecord(
    BigDecimal groupExact,
    BigDecimal groupGte,
    BigDecimal groupLt,
    String groupLabel,
    String valuesJson) {}
