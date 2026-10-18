import type { Estimate } from "@/lib/api/types";
import { formatMoney, formatTimestamp } from "@/lib/format";

export function EstimateResult({ estimate }: { estimate: Estimate | null }): React.ReactElement {
  if (!estimate) {
    return (
      <section className="card flex min-h-64 flex-col justify-center p-6">
        <p className="eyebrow">Latest result</p>
        <h2 className="mt-1 text-2xl font-semibold text-slate-950">Ready when you are</h2>
        <p className="mt-2 text-slate-600">Submit a property to see its exact model estimate, visual scale and model version.</p>
      </section>
    );
  }
  const chartWidth = Math.max(8, Math.min(100, Number(estimate.predicted_price) / 5_000));
  return (
    <section className="card p-6" aria-labelledby="latest-result-heading">
      <p className="eyebrow">Latest result</p>
      <div className="mt-1 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="latest-result-heading" className="text-2xl font-semibold text-slate-950">Estimated property value</h2>
          <p className="numeric mt-2 text-4xl font-bold tracking-tight text-teal-800">{formatMoney(estimate.predicted_price)}</p>
        </div>
        <span className="rounded-full bg-teal-50 px-3 py-1 text-xs font-semibold text-teal-800">Model {estimate.model_version}</span>
      </div>
      <div className="mt-6" role="img" aria-label={`Bar chart showing the predicted price ${formatMoney(estimate.predicted_price)}`}>
        <div className="h-5 overflow-hidden rounded-full bg-slate-200">
          <div className="h-full rounded-full bg-teal-700" style={{ width: `${chartWidth}%` }} />
        </div>
        <div className="mt-2 flex justify-between text-xs text-slate-600"><span>$0</span><span>$500,000 scale</span></div>
      </div>
      <div className="data-table-wrap mt-6" aria-label="Latest estimate details">
        <table className="data-table min-w-full">
          <thead><tr><th>Estimate ID</th><th>Created</th><th>Exact value</th></tr></thead>
          <tbody><tr><td className="font-mono text-xs">{estimate.estimate_id}</td><td>{formatTimestamp(estimate.created_at)}</td><td className="numeric font-semibold">{formatMoney(estimate.predicted_price)}</td></tr></tbody>
        </table>
      </div>
    </section>
  );
}
