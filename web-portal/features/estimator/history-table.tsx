"use client";

import type { Estimate } from "@/lib/api/types";
import { formatMoney, formatNumber, formatTimestamp } from "@/lib/format";

interface HistoryTableProps {
  estimates: Estimate[];
  selectedIds: Set<string>;
  nextCursor: string | null;
  busy: boolean;
  onToggle: (id: string) => void;
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
  return (
    <section className="card p-5 sm:p-6" aria-labelledby="history-heading">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="eyebrow">Durable history</p>
          <h2 id="history-heading" className="mt-1 text-2xl font-semibold text-slate-950">Previous estimates</h2>
          <p className="mt-1 text-sm text-slate-600">Select up to four records for a side-by-side comparison.</p>
        </div>
        <button type="button" className="button-danger" disabled={props.busy || props.estimates.length === 0} onClick={confirmClear}>Clear history</button>
      </div>
      {props.estimates.length === 0 ? (
        <div className="mt-6 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-8 text-center">
          <p className="font-semibold text-slate-800">No estimates yet</p>
          <p className="mt-1 text-sm text-slate-600">Your successful estimates will appear here and remain available after a reload.</p>
        </div>
      ) : (
        <>
          <div className="data-table-wrap mt-6" tabIndex={0} aria-label="Previous estimates; scroll horizontally when needed">
            <table className="data-table">
              <thead><tr><th scope="col">Compare</th><th scope="col">Created</th><th scope="col">Property</th><th scope="col">Predicted value</th><th scope="col">Model</th><th scope="col">Action</th></tr></thead>
              <tbody>
                {props.estimates.map((estimate) => {
                  const checked = props.selectedIds.has(estimate.estimate_id);
                  const selectionDisabled = !checked && props.selectedIds.size >= 4;
                  return (
                    <tr key={estimate.estimate_id}>
                      <td>
                        <label className="inline-flex min-h-11 cursor-pointer items-center gap-2">
                          <input type="checkbox" checked={checked} disabled={selectionDisabled} onChange={() => props.onToggle(estimate.estimate_id)} />
                          <span className="sr-only">Compare estimate {estimate.estimate_id}</span>
                        </label>
                      </td>
                      <td className="whitespace-nowrap">{formatTimestamp(estimate.created_at)}</td>
                      <td>{formatNumber(estimate.inputs.square_footage)} sq. ft. · {estimate.inputs.bedrooms} bed · {estimate.inputs.bathrooms} bath</td>
                      <td className="numeric font-semibold text-teal-800">{formatMoney(estimate.predicted_price)}</td>
                      <td>{estimate.model_version}</td>
                      <td><button type="button" className="button-danger" disabled={props.busy} onClick={() => confirmDelete(estimate.estimate_id)}>Delete</button></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {props.nextCursor ? (
            <button type="button" className="button-secondary mt-5" disabled={props.busy} onClick={props.onLoadMore}>
              {props.busy ? "Loading…" : "Load more history"}
            </button>
          ) : null}
        </>
      )}
    </section>
  );
}
