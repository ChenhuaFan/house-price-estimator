package com.hsbc.market.adapter.persistence.mybatis.query;

import com.hsbc.market.domain.MarketDomain.GroupField;
import com.hsbc.market.domain.MarketDomain.Metric;
import com.hsbc.market.domain.MarketDomain.MetricField;
import com.hsbc.market.domain.MarketDomain.SortDirection;
import com.hsbc.market.domain.MarketDomain.SortField;
import java.util.Map;

public final class MarketSqlProvider {
  private static final String COLUMNS =
      """
            source_id, square_footage, bedrooms, bathrooms, year_built, lot_size,
            distance_to_city_center, school_rating, price
            """;

  private MarketSqlProvider() {}

  public static String selectPage(Map<String, Object> bindings) {
    PageSqlParameters p = (PageSqlParameters) bindings.get("parameters");
    String prefix = "parameters.scope.";
    StringBuilder sql =
        new StringBuilder("SELECT ").append(COLUMNS).append(" FROM catalog.housing_records ");
    filters(sql, p.scope(), prefix);
    cursor(sql, p, "parameters.");
    order(sql, p.sortField(), p.direction());
    sql.append(" LIMIT #{parameters.limit}");
    return sql.toString();
  }

  public static String stream(Map<String, Object> bindings) {
    PageSqlParameters p = (PageSqlParameters) bindings.get("parameters");
    StringBuilder sql =
        new StringBuilder("SELECT ").append(COLUMNS).append(" FROM catalog.housing_records ");
    filters(sql, p.scope(), "parameters.scope.");
    order(sql, p.sortField(), p.direction());
    sql.append(" LIMIT #{parameters.limit}");
    return sql.toString();
  }

  public static String count(Map<String, Object> bindings) {
    MarketSqlParameters p = (MarketSqlParameters) bindings.get("parameters");
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM catalog.housing_records ");
    filters(sql, p, "parameters.");
    return sql.toString();
  }

  public static String statistics(Map<String, Object> bindings) {
    StatisticsSqlParameters p = (StatisticsSqlParameters) bindings.get("parameters");
    StringBuilder sql = new StringBuilder("SELECT ");
    String groupExpression = groupProjection(sql, p);
    sql.append(", jsonb_build_object(");
    for (int index = 0; index < p.metrics().size(); index++) {
      if (index > 0) sql.append(',');
      sql.append("#{parameters.metrics[")
          .append(index)
          .append("].id}, ")
          .append(metricExpression(p.metrics().get(index)));
    }
    sql.append(")::text AS values_json FROM catalog.housing_records ");
    filters(sql, p.scope(), "parameters.scope.");
    if (groupExpression != null) {
      sql.append(" GROUP BY ").append(groupExpression);
      sql.append(" ORDER BY ");
      if (p.order() == null || "group_key".equals(p.order().by())) {
        sql.append(groupExpression);
      } else {
        Metric orderMetric =
            p.metrics().stream()
                .filter(metric -> metric.id().equals(p.order().by()))
                .findFirst()
                .orElseThrow();
        sql.append(metricExpression(orderMetric));
      }
      sql.append(
          p.order() != null && p.order().direction() == SortDirection.DESC ? " DESC" : " ASC");
      sql.append(" LIMIT #{parameters.limit}");
    }
    return sql.toString();
  }

  private static String groupProjection(StringBuilder sql, StatisticsSqlParameters p) {
    if (p.groupBy() == null) {
      sql.append("NULL::numeric AS group_exact, NULL::numeric AS group_gte, ")
          .append("NULL::numeric AS group_lt, NULL::text AS group_label");
      return null;
    }
    String column = groupColumn(p.groupBy().field());
    if (p.groupBy().fixedWidth() == null) {
      sql.append(column)
          .append("::numeric AS group_exact, NULL::numeric AS group_gte, ")
          .append("NULL::numeric AS group_lt, ")
          .append(column)
          .append("::text AS group_label");
      return column;
    }
    String expression =
        "floor("
            + column
            + " / #{parameters.groupBy.fixedWidth}) * #{parameters.groupBy.fixedWidth}";
    sql.append("NULL::numeric AS group_exact, ")
        .append(expression)
        .append(" AS group_gte, (")
        .append(expression)
        .append(" + #{parameters.groupBy.fixedWidth}) AS group_lt, ")
        .append(expression)
        .append("::text || '-' || (")
        .append(expression)
        .append(" + #{parameters.groupBy.fixedWidth})::text AS group_label");
    return expression;
  }

  private static String metricExpression(Metric metric) {
    String column = metric.field() == MetricField.ALL ? "*" : metricColumn(metric.field());
    return switch (metric.method()) {
      case COUNT -> "count(" + column + ")";
      case SUM -> "sum(" + column + ")";
      case AVG -> "avg(" + column + ")";
      case MIN -> "min(" + column + ")";
      case MAX -> "max(" + column + ")";
      case STDDEV_POP -> "stddev_pop(" + column + ")";
    };
  }

  private static void filters(StringBuilder sql, MarketSqlParameters p, String prefix) {
    sql.append(" WHERE dataset_version_id = #{").append(prefix).append("datasetVersionId}");
    range(sql, p.priceGte(), p.priceLte(), "price", prefix + "priceGte", prefix + "priceLte");
    range(
        sql,
        p.squareFootageGte(),
        p.squareFootageLte(),
        "square_footage",
        prefix + "squareFootageGte",
        prefix + "squareFootageLte");
    range(
        sql,
        p.bedroomsGte(),
        p.bedroomsLte(),
        "bedrooms",
        prefix + "bedroomsGte",
        prefix + "bedroomsLte");
    range(
        sql,
        p.bathroomsGte(),
        p.bathroomsLte(),
        "bathrooms",
        prefix + "bathroomsGte",
        prefix + "bathroomsLte");
    range(
        sql,
        p.yearBuiltGte(),
        p.yearBuiltLte(),
        "year_built",
        prefix + "yearBuiltGte",
        prefix + "yearBuiltLte");
    range(
        sql,
        p.lotSizeGte(),
        p.lotSizeLte(),
        "lot_size",
        prefix + "lotSizeGte",
        prefix + "lotSizeLte");
    range(
        sql,
        p.distanceGte(),
        p.distanceLte(),
        "distance_to_city_center",
        prefix + "distanceGte",
        prefix + "distanceLte");
    range(
        sql,
        p.schoolRatingGte(),
        p.schoolRatingLte(),
        "school_rating",
        prefix + "schoolRatingGte",
        prefix + "schoolRatingLte");
  }

  private static void range(
      StringBuilder sql, Object gte, Object lte, String column, String gtePath, String ltePath) {
    if (gte != null)
      sql.append(" AND ").append(column).append(" >= #{").append(gtePath).append('}');
    if (lte != null)
      sql.append(" AND ").append(column).append(" <= #{").append(ltePath).append('}');
  }

  private static void cursor(StringBuilder sql, PageSqlParameters p, String prefix) {
    if (p.cursorSourceId() == null) return;
    String operator = p.direction() == SortDirection.ASC ? ">" : "<";
    if (p.sortField() == SortField.SOURCE_ID) {
      sql.append(" AND source_id ")
          .append(operator)
          .append(" #{")
          .append(prefix)
          .append("cursorSourceId}");
      return;
    }
    String column = sortColumn(p.sortField());
    sql.append(" AND (")
        .append(column)
        .append(", source_id) ")
        .append(operator)
        .append(" (CAST(#{")
        .append(prefix)
        .append("cursorSortValue} AS ")
        .append(sqlType(p.sortField()))
        .append("), #{")
        .append(prefix)
        .append("cursorSourceId})");
  }

  private static void order(StringBuilder sql, SortField field, SortDirection direction) {
    String keyword = direction == SortDirection.ASC ? " ASC" : " DESC";
    String column = sortColumn(field);
    sql.append(" ORDER BY ").append(column).append(keyword);
    if (field != SortField.SOURCE_ID) sql.append(", source_id").append(keyword);
  }

  private static String sortColumn(SortField field) {
    return switch (field) {
      case SOURCE_ID -> "source_id";
      case PRICE -> "price";
      case SQUARE_FOOTAGE -> "square_footage";
      case BEDROOMS -> "bedrooms";
      case BATHROOMS -> "bathrooms";
      case YEAR_BUILT -> "year_built";
      case LOT_SIZE -> "lot_size";
      case DISTANCE_TO_CITY_CENTER -> "distance_to_city_center";
      case SCHOOL_RATING -> "school_rating";
    };
  }

  private static String sqlType(SortField field) {
    return switch (field) {
      case SOURCE_ID, SQUARE_FOOTAGE, LOT_SIZE -> "bigint";
      case BEDROOMS, YEAR_BUILT -> "integer";
      case PRICE, BATHROOMS, DISTANCE_TO_CITY_CENTER, SCHOOL_RATING -> "numeric";
    };
  }

  private static String metricColumn(MetricField field) {
    return switch (field) {
      case ALL -> throw new IllegalArgumentException("ALL has no column");
      case SOURCE_ID -> "source_id";
      case PRICE -> "price";
      case SQUARE_FOOTAGE -> "square_footage";
      case BEDROOMS -> "bedrooms";
      case BATHROOMS -> "bathrooms";
      case YEAR_BUILT -> "year_built";
      case LOT_SIZE -> "lot_size";
      case DISTANCE_TO_CITY_CENTER -> "distance_to_city_center";
      case SCHOOL_RATING -> "school_rating";
    };
  }

  private static String groupColumn(GroupField field) {
    return switch (field) {
      case PRICE -> "price";
      case SQUARE_FOOTAGE -> "square_footage";
      case BEDROOMS -> "bedrooms";
      case BATHROOMS -> "bathrooms";
      case YEAR_BUILT -> "year_built";
      case LOT_SIZE -> "lot_size";
      case DISTANCE_TO_CITY_CENTER -> "distance_to_city_center";
      case SCHOOL_RATING -> "school_rating";
    };
  }
}
