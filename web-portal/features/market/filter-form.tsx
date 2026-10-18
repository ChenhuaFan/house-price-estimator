"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

const ranges = [
  { min: "priceMin", max: "priceMax", label: "Actual price", unit: "USD", step: "0.01" },
  { min: "squareFootageMin", max: "squareFootageMax", label: "Interior floor area", unit: "sq. ft.", step: "1" },
  { min: "bedroomsMin", max: "bedroomsMax", label: "Bedrooms", unit: "whole number", step: "1" },
  { min: "bathroomsMin", max: "bathroomsMax", label: "Bathrooms", unit: "0.5 increments", step: "0.5" },
  { min: "yearBuiltMin", max: "yearBuiltMax", label: "Year built", unit: "calendar year", step: "1" },
  { min: "lotSizeMin", max: "lotSizeMax", label: "Lot size", unit: "sq. ft.", step: "1" },
  { min: "distanceMin", max: "distanceMax", label: "Distance to city centre", unit: "miles", step: "0.001" },
  { min: "schoolRatingMin", max: "schoolRatingMax", label: "School rating", unit: "rating", step: "0.001" },
] as const;

export type MarketFilterValues = Record<string, string>;

export function FilterForm({ values }: { values: MarketFilterValues }): React.ReactElement {
  const router = useRouter();
  const [draft, setDraft] = useState(values);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  useEffect(() => {
    setDraft(values);
    setPending(false);
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
    setPending(true);
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(draft)) if (value) query.set(key, value);
    query.set("page", "0");
    query.set("size", values.size || "20");
    if (values.sort) query.set("sort", values.sort);
    if (values.direction) query.set("direction", values.direction);
    router.push(`/market?${query}`);
  }

  function reset(): void {
    setError(null);
    setPending(true);
    router.push("/market");
  }

  return (
    <form onSubmit={submit} className="card p-5 sm:p-6" aria-labelledby="filter-heading">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="eyebrow">Segment controls</p>
          <h2 id="filter-heading" className="mt-1 text-2xl font-semibold text-slate-950">Filter the market</h2>
          <p className="mt-1 text-sm text-slate-600">Minimum and maximum values are inclusive. Leave fields blank to include the full range.</p>
        </div>
        <button type="button" className="button-secondary" onClick={reset} disabled={pending}>Reset filters</button>
      </div>
      {error ? <div className="status-message status-error mt-4" role="alert">{error}</div> : null}
      <div className="mt-6 grid gap-5 md:grid-cols-2 xl:grid-cols-4">
        {ranges.map((range) => (
          <fieldset key={range.min} className="rounded-xl border border-slate-200 p-3">
            <legend className="px-1 text-sm font-bold text-slate-800">{range.label}</legend>
            <p className="mb-2 text-xs text-slate-500">{range.unit}</p>
            <div className="grid grid-cols-2 gap-2">
              <label className="field-group text-xs font-semibold text-slate-600">
                Minimum
                <input id={`filter-${range.min}`} className="field-input" type="number" step={range.step} value={draft[range.min] ?? ""} onChange={(event) => setDraft((current) => ({ ...current, [range.min]: event.target.value }))} />
              </label>
              <label className="field-group text-xs font-semibold text-slate-600">
                Maximum
                <input className="field-input" type="number" step={range.step} value={draft[range.max] ?? ""} onChange={(event) => setDraft((current) => ({ ...current, [range.max]: event.target.value }))} />
              </label>
            </div>
          </fieldset>
        ))}
      </div>
      <div className="mt-5 flex items-center gap-3">
        <button type="submit" className="button-primary" disabled={pending}>{pending ? "Applying…" : "Apply filters"}</button>
        {pending ? <span role="status" className="text-sm text-slate-600">Loading the selected segment…</span> : null}
      </div>
    </form>
  );
}
