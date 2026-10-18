"use client";

import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import type { MarketAnalysis, WhatIfResponse } from "@/lib/api/types";
import { formatMoney, formatNumber } from "@/lib/format";
import { PortalApiError } from "@/features/estimator/api";
import { downloadPdf } from "@/features/market/api";
import { FilterForm, type MarketFilterValues } from "@/features/market/filter-form";
import { MarketCharts } from "@/features/market/market-charts";
import { MarketTable } from "@/features/market/market-table";
import { WhatIfForm } from "@/features/market/what-if-form";
import { StatusMessage } from "@/components/ui/status-message";

export function MarketDashboard({
  analysis,
  filterValues,
}: {
  analysis: MarketAnalysis;
  filterValues: MarketFilterValues;
}): React.ReactElement {
  const searchParams = useSearchParams();
  const [interactive, setInteractive] = useState(false);
  const [whatIf, setWhatIf] = useState<WhatIfResponse | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<PortalApiError | null>(null);
  const exportQuery = useMemo(() => {
    const query = new URLSearchParams(searchParams.toString());
    query.delete("page");
    query.delete("size");
    query.set("datasetVersionId", analysis.datasetVersionId);
    return query;
  }, [searchParams, analysis.datasetVersionId]);

  useEffect(() => setInteractive(true), []);

  async function exportReport(): Promise<void> {
    setExporting(true);
    setExportError(null);
    try {
      await downloadPdf(exportQuery, whatIf);
    } catch (error) {
      setExportError(error instanceof PortalApiError ? error : new PortalApiError("The PDF could not be generated.", "EXPORT_FAILED"));
    } finally {
      setExporting(false);
    }
  }

  return (
    <div className="space-y-6">
      <FilterForm values={filterValues} disabled={!interactive} />
      <section aria-labelledby="summary-heading">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div><p className="eyebrow">Current segment</p><h2 id="summary-heading" className="mt-1 text-2xl font-semibold text-slate-950">Market summary</h2></div>
          <div className="flex flex-wrap gap-2">
            <a className="button-secondary" href={`/api/market/exports/data.csv?${exportQuery}`} download>Download CSV</a>
            <button type="button" className="button-primary" disabled={!interactive || exporting} onClick={exportReport}>{exporting ? "Generating PDF…" : "Download PDF"}</button>
          </div>
        </div>
        {exportError ? <div className="mt-4"><StatusMessage tone="error" title="Export failed" live>{exportError.message}</StatusMessage></div> : null}
        <div className="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
          {[
            ["Matching properties", formatNumber(analysis.aggregates.count)],
            ["Average price", formatMoney(analysis.aggregates.averagePrice)],
            ["Minimum price", formatMoney(analysis.aggregates.minimumPrice)],
            ["Maximum price", formatMoney(analysis.aggregates.maximumPrice)],
            ["Average floor area", `${formatNumber(analysis.aggregates.averageSquareFootage)} sq. ft.`],
          ].map(([label, value]) => <div key={label} className="card p-5"><p className="text-sm font-semibold text-slate-600">{label}</p><p className="numeric mt-2 text-2xl font-bold text-slate-950">{value}</p></div>)}
        </div>
      </section>
      <MarketCharts priceByBedrooms={analysis.charts.average_price_by_bedrooms} countBySchoolBand={analysis.charts.property_count_by_school_band} />
      <MarketTable analysis={analysis} sort={filterValues.sort || "source_id"} direction={filterValues.direction || "asc"} disabled={!interactive} />
      <WhatIfForm onResult={setWhatIf} disabled={!interactive} />
      <p className="break-all text-xs text-slate-500">Dataset {analysis.datasetVersionId} · fingerprint {analysis.datasetContentSha256}</p>
    </div>
  );
}
