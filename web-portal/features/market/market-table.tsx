"use client";

import { flexRender, getCoreRowModel, useReactTable, type ColumnDef, type SortingState } from "@tanstack/react-table";
import { ChevronLeft, ChevronRight, ChevronsUpDown } from "lucide-react";
import { useMemo } from "react";
import { Button, Panel } from "@/components/ui/primitives";
import type { MarketPageModel, PropertyRecord } from "@/lib/api/types";
import { formatMoney, formatNumber } from "@/lib/format";

const tableColumns: Array<{ key: string; label: string; value: (record: PropertyRecord) => string }> = [
  { key: "source_id", label: "ID", value: (item) => String(item.id) },
  { key: "price", label: "Actual price", value: (item) => formatMoney(item.price) },
  { key: "bedrooms", label: "Beds", value: (item) => String(item.bedrooms) },
  { key: "bathrooms", label: "Baths", value: (item) => item.bathrooms },
  { key: "square_footage", label: "Floor area", value: (item) => `${formatNumber(item.squareFootage)} sq. ft.` },
  { key: "year_built", label: "Year built", value: (item) => String(item.yearBuilt) },
  { key: "lot_size", label: "Lot size", value: (item) => `${formatNumber(item.lotSize)} sq. ft.` },
  { key: "school_rating", label: "School", value: (item) => formatNumber(item.schoolRating) },
  { key: "distance_to_city_center", label: "Distance", value: (item) => `${formatNumber(item.distanceToCityCenter)} mi` },
];

/** Renders sortable, cursor-paginated market records without owning query state. */
export function MarketTable({
  model,
  disabled = false,
  onSort,
  onNext,
  onBack,
}: {
  model: MarketPageModel;
  disabled?: boolean;
  onSort: (column: string) => void;
  onNext: () => void;
  onBack: () => void;
}): React.ReactElement {
  const [sort, direction] = model.query.sort.split(":");
  const columns = useMemo<Array<ColumnDef<PropertyRecord>>>(() => tableColumns.map((column) => ({
    id: column.key,
    header: column.label,
    accessorFn: column.value,
    cell: ({ row }) => <span className={column.key === "price" ? "numeric font-extrabold text-primary" : "numeric"}>{column.value(row.original)}</span>,
  })), []);
  const sorting: SortingState = [{ id: sort, desc: direction === "desc" }];
  const table = useReactTable({ data: model.records, columns, getCoreRowModel: getCoreRowModel(), manualSorting: true, state: { sorting } });

  return (
    <Panel className="overflow-hidden" aria-labelledby="properties-heading" aria-busy={disabled}>
      <div className="flex flex-col gap-2 border-b border-border-ui px-4 py-3 sm:flex-row sm:items-end sm:justify-between">
        <div><h2 id="properties-heading" className="text-lg font-extrabold text-ink">Matching properties ({formatNumber(model.overview.count)})</h2><p className="mt-0.5 text-xs text-ink-muted">Server-sorted records from one immutable dataset version.</p></div>
        <p className="numeric text-sm font-bold text-ink-muted">{model.records.length} shown</p>
      </div>
      <div className="max-w-full overflow-x-auto" tabIndex={0} aria-label="Market records; scroll horizontally when needed">
        <table className="w-full min-w-[66rem] border-collapse text-xs sm:text-sm">
          <thead className="bg-slate-50 text-left text-[0.7rem] uppercase tracking-wide text-ink-muted">
            {table.getHeaderGroups().map((group) => <tr key={group.id}>{group.headers.map((header) => {
              const active = sort === header.column.id;
              return <th key={header.id} scope="col" aria-sort={active ? direction === "desc" ? "descending" : "ascending" : "none"} className="px-3 py-2"><button type="button" className="inline-flex min-h-11 cursor-pointer items-center gap-1 font-bold hover:text-ink" disabled={disabled} onClick={() => onSort(header.column.id)}>{flexRender(header.column.columnDef.header, header.getContext())}<ChevronsUpDown className="size-3.5" aria-hidden="true" /></button></th>;
            })}</tr>)}
          </thead>
          <tbody className="divide-y divide-border-ui">
            {table.getRowModel().rows.length === 0 ? <tr><td colSpan={columns.length} className="px-4 py-10 text-center text-ink-muted">No properties match this segment. Adjust or reset the filters.</td></tr> : table.getRowModel().rows.map((row) => <tr key={row.id} className="hover:bg-slate-50/80">{row.getVisibleCells().map((cell) => <td key={cell.id} className="whitespace-nowrap px-3 py-2.5">{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>)}</tr>)}
          </tbody>
        </table>
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-border-ui px-4 py-3">
        <p className="text-xs text-ink-muted">Cursor pagination keeps results stable while browsing.</p>
        <div className="flex items-center gap-2">
          <Button type="button" variant="secondary" aria-label="Previous results" disabled={disabled || !model.query.cursor} onClick={onBack}><ChevronLeft className="size-4" aria-hidden="true" />Back</Button>
          <Button type="button" variant="secondary" aria-label="Next results" disabled={disabled || !model.page.hasMore || !model.page.nextCursor} onClick={onNext}>Next<ChevronRight className="size-4" aria-hidden="true" /></Button>
        </div>
      </div>
    </Panel>
  );
}
