"use client";

import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from "@tanstack/react-table";
import { CopyPlus, Eye, Trash2 } from "lucide-react";
import { Button, Panel } from "@/components/ui/primitives";
import type { Estimate } from "@/lib/api/types";
import { formatMoney, formatNumber, formatTimestamp } from "@/lib/format";

interface HistoryTableProps {
  estimates: Estimate[];
  selectedIds: Set<string>;
  nextCursor: string | null;
  busy: boolean;
  warning?: string | null;
  onToggle: (id: string) => void;
  onView: (id: string) => void;
  onReuse: (id: string) => void;
  onDelete: (id: string) => Promise<void>;
  onClear: () => Promise<void>;
  onLoadMore: () => Promise<void>;
}

export function HistoryTable(props: HistoryTableProps): React.ReactElement {
  async function confirmDelete(id: string): Promise<void> {
    if (window.confirm("Delete this estimate from your history?")) await props.onDelete(id);
  }
  async function confirmClear(): Promise<void> {
    if (window.confirm("Clear your complete estimate history? This cannot be undone.")) await props.onClear();
  }

  const columns: Array<ColumnDef<Estimate>> = [
    {
      id: "compare",
      header: "Compare",
      cell: ({ row }) => {
        const checked = props.selectedIds.has(row.original.estimate_id);
        const disabled = !checked && props.selectedIds.size >= 4;
        return <label className="inline-flex min-h-11 cursor-pointer items-center"><input type="checkbox" checked={checked} disabled={disabled} onChange={() => props.onToggle(row.original.estimate_id)} /><span className="sr-only">Compare estimate {row.original.estimate_id}</span></label>;
      },
    },
    { accessorKey: "created_at", header: "Created", cell: ({ row }) => <span className="whitespace-nowrap">{formatTimestamp(row.original.created_at)}</span> },
    { id: "property", header: "Property", cell: ({ row }) => <span>{formatNumber(row.original.inputs.square_footage)} sq. ft. · {row.original.inputs.bedrooms} bed · {row.original.inputs.bathrooms} bath</span> },
    { accessorKey: "predicted_price", header: "Predicted value", cell: ({ row }) => <span className="numeric font-extrabold text-primary">{formatMoney(row.original.predicted_price)}</span> },
    { accessorKey: "model_version", header: "Model" },
    {
      id: "action",
      header: "Action",
      cell: ({ row }) => <div className="flex gap-2"><Button type="button" variant="secondary" disabled={props.busy} onClick={() => props.onView(row.original.estimate_id)}><Eye className="size-4" aria-hidden="true" />View</Button><Button type="button" variant="secondary" disabled={props.busy} onClick={() => props.onReuse(row.original.estimate_id)}><CopyPlus className="size-4" aria-hidden="true" />Use as scenario</Button><Button type="button" variant="danger" disabled={props.busy} onClick={() => confirmDelete(row.original.estimate_id)}><Trash2 className="size-4" aria-hidden="true" />Delete</Button></div>,
    },
  ];

  const table = useReactTable({ data: props.estimates, columns, getCoreRowModel: getCoreRowModel() });

  return (
    <Panel className="p-5 sm:p-6" aria-labelledby="history-heading">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div><h2 id="history-heading" className="text-xl font-extrabold text-ink">Previous estimates</h2><p className="mt-1 text-sm text-ink-muted">Select up to four records for comparison.</p></div>
        <Button type="button" variant="danger" disabled={props.busy || props.estimates.length === 0} onClick={confirmClear}>Clear history</Button>
      </div>
      {props.warning ? <p className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm font-semibold text-amber-900" role="status">{props.warning}</p> : null}
      {props.estimates.length === 0 ? (
        <div className="mt-5 rounded-lg border border-dashed border-slate-300 bg-slate-50 p-8 text-center"><p className="font-bold text-ink">No estimates yet</p><p className="mt-1 text-sm text-ink-muted">Successful estimates are stored here and survive reloads.</p></div>
      ) : (
        <>
          <div className="mt-5 max-w-full overflow-x-auto rounded-lg border border-border-ui" tabIndex={0} aria-label="Previous estimates; scroll horizontally when needed">
            <table className="w-full min-w-[58rem] border-collapse text-sm">
              <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-ink-muted">{table.getHeaderGroups().map((group) => <tr key={group.id}>{group.headers.map((header) => <th key={header.id} scope="col" className="p-3">{flexRender(header.column.columnDef.header, header.getContext())}</th>)}</tr>)}</thead>
              <tbody className="divide-y divide-border-ui">{table.getRowModel().rows.map((row) => <tr key={row.id} className="hover:bg-slate-50/80">{row.getVisibleCells().map((cell) => <td key={cell.id} className="p-3 align-middle">{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>)}</tr>)}</tbody>
            </table>
          </div>
          {props.nextCursor ? <Button type="button" variant="secondary" className="mt-4" disabled={props.busy} onClick={props.onLoadMore}>{props.busy ? "Loading…" : "Load more history"}</Button> : null}
        </>
      )}
    </Panel>
  );
}
