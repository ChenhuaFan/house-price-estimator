"use client";

import { ChevronDown, RotateCcw, SlidersHorizontal } from "lucide-react";
import { useEffect, useState } from "react";
import { Button, Panel, Spinner } from "@/components/ui/primitives";
import { marketFilterKeys } from "@/features/market/model";

interface RangeDefinition {
  min: string;
  max: string;
  label: string;
  unit: string;
  step: string;
}

const ranges: RangeDefinition[] = [
  { min: "price_gte", max: "price_lte", label: "Actual price", unit: "USD", step: "0.01" },
  { min: "square_footage_gte", max: "square_footage_lte", label: "Floor area", unit: "sq. ft.", step: "1" },
  { min: "bedrooms_gte", max: "bedrooms_lte", label: "Bedrooms", unit: "whole number", step: "1" },
  { min: "bathrooms_gte", max: "bathrooms_lte", label: "Bathrooms", unit: "0.5 increments", step: "0.5" },
  { min: "year_built_gte", max: "year_built_lte", label: "Year built", unit: "calendar year", step: "1" },
  { min: "lot_size_gte", max: "lot_size_lte", label: "Lot size", unit: "sq. ft.", step: "1" },
  { min: "distance_to_city_center_gte", max: "distance_to_city_center_lte", label: "Distance", unit: "miles", step: "0.001" },
  { min: "school_rating_gte", max: "school_rating_lte", label: "School rating", unit: "rating", step: "0.001" },
];

export type MarketFilterValues = Partial<Record<(typeof marketFilterKeys)[number], string>>;

/** Edits market ranges locally and commits only validated filter sets. */
export function FilterForm({
  values,
  disabled = false,
  pending = false,
  onApply,
  onReset,
}: {
  values: MarketFilterValues;
  disabled?: boolean;
  pending?: boolean;
  onApply: (values: MarketFilterValues) => void;
  onReset: () => void;
}): React.ReactElement {
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setDraft(
      Object.fromEntries(
        Object.entries(values).map(([key, value]) => [
          key,
          value === undefined ? "" : String(value),
        ]),
      ),
    );
  }, [values]);

  function submit(event: React.FormEvent<HTMLFormElement>): void {
    event.preventDefault();

    for (const range of ranges) {
      const minimum = draft[range.min];
      const maximum = draft[range.max];
      if (minimum && maximum && Number(minimum) > Number(maximum)) {
        setError(`${range.label}: minimum cannot exceed maximum.`);
        document.getElementById(`filter-${range.min}`)?.focus();
        return;
      }
    }

    setError(null);
    onApply(draft);
  }

  const activeCount = ranges.flatMap((range) => [draft[range.min], draft[range.max]]).filter(Boolean).length;

  return (
    <Panel className="p-3">
      <form onSubmit={submit}>
        <details className="group">
          <summary className="flex min-h-12 cursor-pointer list-none items-center gap-3 rounded-lg px-2 text-sm font-bold text-ink [&::-webkit-details-marker]:hidden">
            <SlidersHorizontal className="size-5 text-primary" aria-hidden="true" />
            <span className="flex-1">Market filters <span className="font-medium text-ink-muted">· {activeCount ? `${activeCount} applied` : "All properties"}</span></span>
            <ChevronDown className="size-4 text-ink-muted transition-transform group-open:rotate-180" aria-hidden="true" />
          </summary>
          <div className="mt-3 grid gap-3 border-t border-border-ui pt-4 sm:grid-cols-2 lg:grid-cols-4">
            {ranges.map((range) => <fieldset key={range.min} className="min-w-0">
              <legend className="text-sm font-bold text-ink">{range.label}</legend>
              <p className="mt-0.5 text-xs text-ink-muted">{range.unit}</p>
              <div className="mt-2 grid grid-cols-2 gap-2">
                <label className="text-xs font-semibold text-ink-muted">Minimum<input id={`filter-${range.min}`} aria-label={`${range.label} minimum`} className="mt-1 min-h-11 w-full min-w-0 rounded-lg border border-slate-300 px-3 text-ink focus:border-primary focus:ring-2 focus:ring-primary/20" type="number" step={range.step} value={draft[range.min] ?? ""} disabled={disabled || pending} onChange={(event) => setDraft((current) => ({ ...current, [range.min]: event.target.value }))} /></label>
                <label className="text-xs font-semibold text-ink-muted">Maximum<input aria-label={`${range.label} maximum`} className="mt-1 min-h-11 w-full min-w-0 rounded-lg border border-slate-300 px-3 text-ink focus:border-primary focus:ring-2 focus:ring-primary/20" type="number" step={range.step} value={draft[range.max] ?? ""} disabled={disabled || pending} onChange={(event) => setDraft((current) => ({ ...current, [range.max]: event.target.value }))} /></label>
              </div>
            </fieldset>)}
          </div>
          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button type="button" variant="secondary" aria-label="Reset filters" onClick={() => { setError(null); onReset(); }} disabled={disabled || pending}><RotateCcw className="size-4" aria-hidden="true" />Reset</Button>
            <Button type="submit" disabled={disabled || pending}>{pending ? <Spinner label="Applying…" /> : "Apply filters"}</Button>
          </div>
        </details>
        {error ? <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm font-semibold text-danger" role="alert">{error}</div> : null}
      </form>
    </Panel>
  );
}
