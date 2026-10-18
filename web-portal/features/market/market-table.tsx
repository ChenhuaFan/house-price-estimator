"use client";

import { useRouter, useSearchParams } from "next/navigation";
import type { MarketAnalysis, PropertyRecord } from "@/lib/api/types";
import { formatMoney, formatNumber } from "@/lib/format";

const columns: Array<{ key: string; label: string; render: (record: PropertyRecord) => string }> = [
  { key: "source_id", label: "ID", render: (item) => String(item.id) },
  { key: "square_footage", label: "Floor area", render: (item) => `${formatNumber(item.squareFootage)} sq. ft.` },
  { key: "bedrooms", label: "Beds", render: (item) => String(item.bedrooms) },
  { key: "bathrooms", label: "Baths", render: (item) => String(item.bathrooms) },
  { key: "year_built", label: "Year built", render: (item) => String(item.yearBuilt) },
  { key: "lot_size", label: "Lot size", render: (item) => `${formatNumber(item.lotSize)} sq. ft.` },
  { key: "distance_to_city_center", label: "Distance", render: (item) => `${formatNumber(item.distanceToCityCenter)} mi` },
  { key: "school_rating", label: "School", render: (item) => formatNumber(item.schoolRating) },
  { key: "price", label: "Actual price", render: (item) => formatMoney(item.price) },
];

export function MarketTable({ analysis, sort, direction }: { analysis: MarketAnalysis; sort: string; direction: string }): React.ReactElement {
  const router = useRouter();
  const searchParams = useSearchParams();
  const pages = Math.max(1, Math.ceil(analysis.matchingCount / analysis.size));

  function navigate(next: Record<string, string>): void {
    const query = new URLSearchParams(searchParams.toString());
    for (const [key, value] of Object.entries(next)) query.set(key, value);
    router.push(`/market?${query}`);
  }

  function sortBy(key: string): void {
    navigate({ sort: key, direction: sort === key && direction === "asc" ? "desc" : "asc", page: "0" });
  }

  return (
    <section className="card p-5 sm:p-6" aria-labelledby="properties-heading">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="eyebrow">Source records</p>
          <h2 id="properties-heading" className="mt-1 text-2xl font-semibold text-slate-950">Matching properties</h2>
          <p className="mt-1 text-sm text-slate-600">Page {analysis.page + 1} of {pages}. Activate a column heading to sort.</p>
        </div>
        <p className="numeric text-sm font-semibold text-slate-700">{analysis.matchingCount} total records</p>
      </div>
      <div className="data-table-wrap mt-5" tabIndex={0} aria-label="Market records; scroll horizontally when needed">
        <table className="data-table">
          <thead><tr>{columns.map((column) => (
            <th key={column.key} scope="col" aria-sort={sort === column.key ? (direction === "desc" ? "descending" : "ascending") : "none"}>
              <button type="button" className="min-h-11 cursor-pointer text-left font-inherit underline decoration-transparent underline-offset-4 hover:decoration-current" onClick={() => sortBy(column.key)}>
                {column.label}{sort === column.key ? <span aria-hidden="true"> {direction === "desc" ? "↓" : "↑"}</span> : null}
              </button>
            </th>
          ))}</tr></thead>
          <tbody>
            {analysis.records.length === 0 ? <tr><td colSpan={columns.length} className="py-10 text-center text-slate-600">No properties match this segment. Adjust or reset the filters.</td></tr> : analysis.records.map((record) => (
              <tr key={record.id}>{columns.map((column) => <td key={column.key} className={column.key === "price" ? "numeric font-semibold text-teal-800" : "numeric"}>{column.render(record)}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-5 flex items-center justify-between gap-3">
        <button type="button" className="button-secondary" disabled={analysis.page <= 0} onClick={() => navigate({ page: String(analysis.page - 1) })}>Previous page</button>
        <span className="text-sm text-slate-600">Page {analysis.page + 1} / {pages}</span>
        <button type="button" className="button-secondary" disabled={analysis.page + 1 >= pages} onClick={() => navigate({ page: String(analysis.page + 1) })}>Next page</button>
      </div>
    </section>
  );
}
