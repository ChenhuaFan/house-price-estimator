package com.hsbc.market.controller;

import com.hsbc.market.contract.model.AdvisoryWarning;
import com.hsbc.market.contract.model.DatasetVersion;
import com.hsbc.market.contract.model.ExportCreateRequest;
import com.hsbc.market.contract.model.ExportFormat;
import com.hsbc.market.contract.model.ExportJob;
import com.hsbc.market.contract.model.ExportStatus;
import com.hsbc.market.contract.model.MarketFilters;
import com.hsbc.market.contract.model.MarketScope;
import com.hsbc.market.contract.model.MetricRequest;
import com.hsbc.market.contract.model.PageMetadata;
import com.hsbc.market.contract.model.PropertyInput;
import com.hsbc.market.contract.model.PropertyPage;
import com.hsbc.market.contract.model.StatisticValue;
import com.hsbc.market.contract.model.StatisticsGroup;
import com.hsbc.market.contract.model.StatisticsGroupKey;
import com.hsbc.market.contract.model.StatisticsQuery;
import com.hsbc.market.contract.model.StatisticsResponse;
import com.hsbc.market.contract.model.WhatIfResponse;
import com.hsbc.market.model.domain.ExportDomain;
import com.hsbc.market.model.domain.MarketDomain;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Converts generated OpenAPI models to and from the service domain model. */
@Component
public final class ApiModelMapper {
  public MarketDomain.Selection propertySelection(
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
      String sort) {
    return new MarketDomain.Selection(
        datasetVersionId,
        new MarketDomain.Filters(
            MarketDomain.Range.of(decimal(priceGte), decimal(priceLte)),
            MarketDomain.Range.of(squareFootageGte, squareFootageLte),
            MarketDomain.Range.of(bedroomsGte, bedroomsLte),
            MarketDomain.Range.of(decimal(bathroomsGte), decimal(bathroomsLte)),
            MarketDomain.Range.of(yearBuiltGte, yearBuiltLte),
            MarketDomain.Range.of(lotSizeGte, lotSizeLte),
            MarketDomain.Range.of(decimal(distanceGte), decimal(distanceLte)),
            MarketDomain.Range.of(decimal(schoolRatingGte), decimal(schoolRatingLte))),
        MarketDomain.Sort.parse(sort));
  }

  public MarketDomain.StatisticsCommand toDomain(StatisticsQuery request) {
    MarketScope scope = request.getScope();
    return new MarketDomain.StatisticsCommand(
        scope.getDatasetVersionId(),
        filters(scope.getFilters()),
        request.getMetrics().stream().map(this::metric).toList(),
        request.getGroupBy() == null
            ? null
            : new MarketDomain.GroupBy(
                MarketDomain.GroupField.valueOf(request.getGroupBy().getField().name()),
                request.getGroupBy().getBucket() == null
                    ? null
                    : new BigDecimal(request.getGroupBy().getBucket().getWidth())),
        request.getOrder() == null
            ? null
            : new MarketDomain.GroupOrder(
                request.getOrder().getBy(),
                MarketDomain.SortDirection.valueOf(request.getOrder().getDirection().name())),
        request.getLimit() == null ? 100 : request.getLimit());
  }

  public ExportDomain.CreateCommand toDomain(UUID idempotencyKey, ExportCreateRequest request) {
    ExportDomain.Format format = ExportDomain.Format.valueOf(request.getFormat().name());
    com.hsbc.market.contract.model.MarketSelection selection = request.getSelection();
    MarketDomain.Sort sort =
        selection.getSort() == null || selection.getSort().isEmpty()
            ? new MarketDomain.Sort(
                MarketDomain.SortField.SOURCE_ID, MarketDomain.SortDirection.ASC)
            : new MarketDomain.Sort(
                MarketDomain.SortField.fromApi(
                    selection.getSort().getFirst().getField().getValue()),
                MarketDomain.SortDirection.valueOf(
                    selection.getSort().getFirst().getDirection().name()));
    MarketDomain.Selection domainSelection =
        new MarketDomain.Selection(
            selection.getScope().getDatasetVersionId(),
            filters(selection.getScope().getFilters()),
            sort);
    ExportDomain.PdfOptions pdf = null;
    if (request.getPdfOptions() != null) {
      var options = request.getPdfOptions();
      pdf =
          new ExportDomain.PdfOptions(
              options.getTemplate() == null
                  ? "market-analysis-v1"
                  : options.getTemplate().getValue(),
              options.getIncludeDetails() == null || options.getIncludeDetails(),
              options.getDetailLimit() == null ? 500 : options.getDetailLimit(),
              options.getMetrics() == null
                  ? List.of()
                  : options.getMetrics().stream().map(this::metric).toList(),
              options.getWhatIf() == null ? null : toDomain(options.getWhatIf()));
    }
    return new ExportDomain.CreateCommand(format, domainSelection, pdf, idempotencyKey);
  }

  public MarketDomain.PropertyFeatures toDomain(PropertyInput input) {
    return new MarketDomain.PropertyFeatures(
        input.getSquareFootage(),
        input.getBedrooms(),
        new BigDecimal(input.getBathrooms()),
        input.getYearBuilt(),
        input.getLotSize(),
        new BigDecimal(input.getDistanceToCityCenter()),
        new BigDecimal(input.getSchoolRating()));
  }

  public PropertyPage toContract(MarketDomain.PropertyPage page) {
    List<com.hsbc.market.contract.model.PropertyRecord> items =
        page.items().stream().map(this::toContract).toList();
    PageMetadata metadata = new PageMetadata(page.limit(), page.hasMore());
    if (page.nextCursor() != null) metadata.setNextCursor(page.nextCursor());
    return new PropertyPage(
        dataset(page.datasetVersion()), page.queryFingerprint(), items, metadata);
  }

  public StatisticsResponse toContract(MarketDomain.StatisticsResult result) {
    List<StatisticsGroup> groups =
        result.groups().stream()
            .map(
                group -> {
                  StatisticsGroupKey key = new StatisticsGroupKey();
                  if (group.key().field() != null) {
                    key.setField(
                        com.hsbc.market.contract.model.GroupField.valueOf(
                            group.key().field().name()));
                  }
                  if (group.key().exactValue() != null)
                    key.setExactValue(decimal(group.key().exactValue()));
                  if (group.key().gte() != null) key.setGte(decimal(group.key().gte()));
                  if (group.key().lt() != null) key.setLt(decimal(group.key().lt()));
                  if (group.key().label() != null) key.setLabel(group.key().label());
                  List<StatisticValue> values = new ArrayList<>();
                  group
                      .values()
                      .forEach(
                          (id, value) -> {
                            StatisticValue mapped = new StatisticValue(id);
                            if (value != null) mapped.setValue(decimal(value));
                            values.add(mapped);
                          });
                  return new StatisticsGroup(key, values);
                })
            .toList();
    return new StatisticsResponse(
        dataset(result.datasetVersion()), result.queryFingerprint(), groups);
  }

  public WhatIfResponse toContract(MarketDomain.WhatIfResult result) {
    PropertyInput input =
        new PropertyInput(
            result.inputs().squareFootage(),
            result.inputs().bedrooms(),
            result.inputs().bathrooms().setScale(1).toPlainString(),
            result.inputs().yearBuilt(),
            result.inputs().lotSize(),
            result.inputs().distanceToCityCenter().setScale(3).toPlainString(),
            result.inputs().schoolRating().setScale(3).toPlainString());
    com.hsbc.market.contract.model.Prediction prediction =
        new com.hsbc.market.contract.model.Prediction(
            result.prediction().predictedPrice().setScale(2).toPlainString(),
            com.hsbc.market.contract.model.Prediction.CurrencyEnum.USD,
            result.prediction().modelVersion(),
            result.prediction().warnings().stream()
                .map(
                    warning ->
                        new AdvisoryWarning(
                            warning.field(),
                            warning.submittedValue(),
                            warning.advisoryMinimum(),
                            warning.advisoryMaximum(),
                            warning.message()))
                .toList());
    return new WhatIfResponse(input, prediction);
  }

  public ExportJob toContract(ExportDomain.Job job) {
    ExportJob response =
        new ExportJob(
            job.exportId(), ExportStatus.valueOf(job.status().name()),
            ExportFormat.valueOf(job.format().name()), job.queryFingerprint(),
            job.createdAt().atOffset(ZoneOffset.UTC), job.expiresAt().atOffset(ZoneOffset.UTC));
    if (job.startedAt() != null) response.setStartedAt(job.startedAt().atOffset(ZoneOffset.UTC));
    if (job.completedAt() != null)
      response.setCompletedAt(job.completedAt().atOffset(ZoneOffset.UTC));
    response.setRowCount(job.rowCount());
    response.setDetailRowCount(job.detailRowCount());
    response.setDetailsTruncated(job.detailsTruncated());
    response.setContentLength(job.contentLength());
    response.setContentSha256(job.contentSha256());
    response.setFailureCode(job.failureCode());
    if (job.status() == ExportDomain.Status.SUCCEEDED) {
      response.setDownloadUrl("/api/v2/market/exports/" + job.exportId() + "/content");
    }
    return response;
  }

  private MarketDomain.WhatIfResult toDomain(WhatIfResponse response) {
    PropertyInput input = response.getInputs();
    var prediction = response.getPrediction();
    return new MarketDomain.WhatIfResult(
        toDomain(input),
        new MarketDomain.Prediction(
            new BigDecimal(prediction.getPredictedPrice()),
            prediction.getCurrency().getValue(),
            prediction.getModelVersion(),
            prediction.getWarnings().stream()
                .map(
                    warning ->
                        new MarketDomain.AdvisoryWarning(
                            warning.getField(),
                            warning.getSubmittedValue(),
                            warning.getAdvisoryMinimum(),
                            warning.getAdvisoryMaximum(),
                            warning.getMessage()))
                .toList()));
  }

  private MarketDomain.Metric metric(MetricRequest request) {
    MarketDomain.MetricField field =
        request.getField() == com.hsbc.market.contract.model.MetricField.STAR
            ? MarketDomain.MetricField.ALL
            : MarketDomain.MetricField.valueOf(request.getField().name());
    return new MarketDomain.Metric(
        request.getId(), MarketDomain.MetricMethod.valueOf(request.getMethod().name()), field);
  }

  private MarketDomain.Filters filters(MarketFilters filters) {
    if (filters == null) return MarketDomain.Filters.empty();
    return new MarketDomain.Filters(
        decimalRange(filters.getPrice()),
        longRange(filters.getSquareFootage()),
        integerRange(filters.getBedrooms()),
        decimalRange(filters.getBathrooms()),
        integerRange(filters.getYearBuilt()),
        longRange(filters.getLotSize()),
        decimalRange(filters.getDistanceToCityCenter()),
        decimalRange(filters.getSchoolRating()));
  }

  private MarketDomain.Range<BigDecimal> decimalRange(
      com.hsbc.market.contract.model.DecimalRange range) {
    return range == null
        ? null
        : MarketDomain.Range.of(decimal(range.getGte()), decimal(range.getLte()));
  }

  private MarketDomain.Range<Long> longRange(com.hsbc.market.contract.model.IntegerRange range) {
    return range == null ? null : MarketDomain.Range.of(range.getGte(), range.getLte());
  }

  private MarketDomain.Range<Integer> integerRange(
      com.hsbc.market.contract.model.IntegerRange range) {
    return range == null
        ? null
        : MarketDomain.Range.of(
            range.getGte() == null ? null : Math.toIntExact(range.getGte()),
            range.getLte() == null ? null : Math.toIntExact(range.getLte()));
  }

  private com.hsbc.market.contract.model.PropertyRecord toContract(
      MarketDomain.PropertyRecord record) {
    return new com.hsbc.market.contract.model.PropertyRecord(
        record.sourceId(),
        record.squareFootage(),
        record.bedrooms(),
        record.bathrooms().setScale(1).toPlainString(),
        record.yearBuilt(),
        record.lotSize(),
        record.distanceToCityCenter().setScale(3).toPlainString(),
        record.schoolRating().setScale(3).toPlainString(),
        record.price().setScale(2).toPlainString());
  }

  private DatasetVersion dataset(MarketDomain.DatasetVersion version) {
    return new DatasetVersion(version.id(), version.contentSha256());
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  private static String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
