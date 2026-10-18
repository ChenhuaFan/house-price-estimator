import { ArrowDown, ArrowUp, CircleDollarSign, Database, House, Ruler } from "lucide-react";
import { MetricCard } from "@/components/ui/metric-card";
import { StatusMessage } from "@/components/ui/status-message";
import { FilterForm, type MarketFilterValues } from "@/features/market/filter-form";
import { MarketCharts } from "@/features/market/market-charts";
import { MarketTable } from "@/features/market/market-table";
import { WhatIfForm } from "@/features/market/what-if-form";
import { ExportCentre } from "@/features/market/export-centre";
import type { PortalApiError } from "@/features/estimator/api";
import type { ExportFormat, ExportJob, MarketPageModel, WhatIfResponse } from "@/lib/api/types";
import { formatMoney, formatNumber } from "@/lib/format";
import { marketFilterKeys } from "@/features/market/model";

interface Props {
  model: MarketPageModel;
  values: MarketFilterValues;
  navigating: boolean;
  whatIf: WhatIfResponse | null;
  exports: ExportJob[];
  creatingFormat: ExportFormat | null;
  exportError: PortalApiError | null;
  announcement: string;
  onApplyFilters: (values: MarketFilterValues) => void;
  onResetFilters: () => void;
  onSort: (column: string) => void;
  onNext: () => void;
  onBack: () => void;
  onExport: (format: ExportFormat) => Promise<void>;
  onCancelExport: (id: string) => Promise<void>;
  onDismissExport: (id: string) => void;
  onWhatIfResult: (value: WhatIfResponse) => void;
}

const filterLabels: Record<(typeof marketFilterKeys)[number], string> = {
  price_gte: "Price ≥", price_lte: "Price ≤", square_footage_gte: "Area ≥", square_footage_lte: "Area ≤",
  bedrooms_gte: "Beds ≥", bedrooms_lte: "Beds ≤", bathrooms_gte: "Baths ≥", bathrooms_lte: "Baths ≤",
  year_built_gte: "Year ≥", year_built_lte: "Year ≤", lot_size_gte: "Lot ≥", lot_size_lte: "Lot ≤",
  distance_to_city_center_gte: "Distance ≥", distance_to_city_center_lte: "Distance ≤",
  school_rating_gte: "School ≥", school_rating_lte: "School ≤",
};

function nullableMoney(value: string | null): string {
  return value === null ? "—" : formatMoney(value);
}

function nullableNumber(value: string | null, suffix = ""): string {
  return value === null ? "—" : `${formatNumber(value)}${suffix}`;
}

/** Composes the presentational dashboard from server-provided data and callback props. */
export function MarketDashboardView(props: Props): React.ReactElement {
  const activeFilters = marketFilterKeys.flatMap((key) => props.values[key] ? [{ key, value: props.values[key]! }] : []);

  return (
    <div className="grid min-w-0 gap-4 xl:grid-cols-[minmax(0,1fr)_18rem]">
      <div className="min-w-0 space-y-4">
        <p className="sr-only" role="status" aria-live="polite">{props.announcement}</p>
        <div className="flex min-h-14 flex-col gap-3 sm:flex-row sm:items-center">
          <div className="inline-flex min-h-14 flex-1 items-center gap-3 rounded-xl border border-border-ui bg-white px-4 py-2 shadow-panel">
            <Database className="size-5 text-ink" aria-hidden="true" />
            <div className="min-w-0"><p className="font-bold text-ink">Housing dataset · {formatNumber(props.model.overview.count)} matching</p><p className="truncate text-xs text-ink-muted">Version {props.model.datasetVersion.id.slice(0, 8)} · server-rendered and pinned · price σ {nullableMoney(props.model.overview.priceStandardDeviation)}</p></div>
          </div>
          <div className={`inline-flex min-h-11 items-center gap-2 rounded-full px-3 text-sm font-bold transition-opacity ${props.navigating ? "bg-blue-soft text-blue opacity-100" : "pointer-events-none opacity-0"}`} role={props.navigating ? "status" : undefined} aria-live={props.navigating ? "polite" : undefined} aria-hidden={!props.navigating}><span className="size-2 animate-pulse rounded-full bg-blue" />Updating dashboard…</div>
        </div>

        <FilterForm values={props.values} disabled={false} pending={props.navigating} onApply={props.onApplyFilters} onReset={props.onResetFilters} />
        {activeFilters.length > 0 ? <div className="flex flex-wrap gap-2" aria-label="Active market filters">{activeFilters.map(({ key, value }) => <span key={key} className="rounded-full border border-border-ui bg-white px-3 py-1.5 text-xs font-bold text-ink">{filterLabels[key]} {value}</span>)}</div> : null}
        {props.exportError ? <StatusMessage tone="error" title="Export action failed" live>{props.exportError.message}</StatusMessage> : null}

        <section aria-labelledby="summary-heading">
          <h2 id="summary-heading" className="sr-only">Market summary</h2>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            <MetricCard label="Properties" value={formatNumber(props.model.overview.count)} icon={House} tone="blue" />
            <MetricCard label="Average price" value={nullableMoney(props.model.overview.averagePrice)} icon={CircleDollarSign} tone="orange" />
            <MetricCard label="Minimum" value={nullableMoney(props.model.overview.minimumPrice)} icon={ArrowDown} tone="green" />
            <MetricCard label="Maximum" value={nullableMoney(props.model.overview.maximumPrice)} icon={ArrowUp} tone="red" />
            <MetricCard label="Average area" value={nullableNumber(props.model.overview.averageSquareFootage, " sq. ft.")} icon={Ruler} tone="orange" />
          </div>
        </section>
        <MarketCharts priceByBedrooms={props.model.charts.averagePriceByBedrooms} countBySchoolBand={props.model.charts.propertyCountBySchoolBand} />
        <MarketTable model={props.model} disabled={props.navigating} onSort={props.onSort} onNext={props.onNext} onBack={props.onBack} />
        <ExportCentre jobs={props.exports} creatingFormat={props.creatingFormat} onCreate={props.onExport} onCancel={props.onCancelExport} onDismiss={props.onDismissExport} />
        <details className="break-all rounded-lg px-1 text-xs text-ink-muted"><summary className="min-h-11 cursor-pointer py-3 font-bold">Technical dataset details</summary><p>Version {props.model.datasetVersion.id}</p><p>Content SHA-256 {props.model.datasetVersion.contentSha256}</p><p>Query {props.model.queryFingerprint}</p></details>
      </div>
      <aside className="min-w-0 xl:sticky xl:top-5 xl:self-start">
        <WhatIfForm marketOverview={props.model.overview} onResult={props.onWhatIfResult} />
      </aside>
    </div>
  );
}
