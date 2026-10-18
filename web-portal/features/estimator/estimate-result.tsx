"use client";

import { BarChart3, CalendarClock, Database, DollarSign } from "lucide-react";
import {
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  Tooltip,
  type ChartOptions,
} from "chart.js";
import { Bar } from "react-chartjs-2";
import { AnimatePresence, m } from "motion/react";
import { Panel } from "@/components/ui/primitives";
import { propertyFieldMetadata } from "@/components/ui/property-fields";
import type { Estimate, PropertyFieldName } from "@/lib/api/types";
import { formatMoney, formatNumber, formatTimestamp } from "@/lib/format";

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip, Legend);

/** Displays the current estimate as an exact summary, chart, and input table. */
export function EstimateResult({ estimate }: { estimate: Estimate | null }): React.ReactElement {
  if (!estimate) {
    return (
      <Panel className="flex min-h-[42rem] flex-col items-center justify-center p-7 text-center">
        <div className="flex flex-col items-center">
          <span className="grid size-14 place-items-center rounded-full bg-primary-soft text-primary"><BarChart3 className="size-7" aria-hidden="true" /></span>
          <h2 className="mt-4 text-xl font-extrabold text-ink">Ready for an estimate</h2>
          <p className="mt-2 max-w-sm text-sm text-ink-muted">Submit the seven property fields to see the exact value, model version, saved timestamp, chart, and input table.</p>
        </div>
      </Panel>
    );
  }

  const predicted = Number(estimate.predicted_price);
  const scaleMaximum = Math.max(100_000, Math.ceil(predicted / 100_000) * 100_000);
  const label = `Bar chart showing the predicted price ${formatMoney(estimate.predicted_price)} on a scale from $0 to ${formatMoney(scaleMaximum)}`;
  const options: ChartOptions<"bar"> = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    indexAxis: "y",
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (context) => formatMoney(context.parsed.x ?? 0) } },
    },
    scales: {
      x: {
        beginAtZero: true,
        suggestedMax: scaleMaximum,
        grid: { color: "#e8edf2" },
        ticks: { callback: (value) => `$${Number(value) / 1000}K`, color: "#607087" },
      },
      y: { grid: { display: false }, ticks: { color: "#0d1b33", font: { weight: 600 } } },
    },
  };

  return (
    <Panel className="min-h-[42rem] overflow-hidden" aria-labelledby="latest-result-heading">
      <AnimatePresence initial={false} mode="wait">
      <m.div key={estimate.estimate_id} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
      <div className="border-t-4 border-primary bg-gradient-to-b from-primary-soft/70 to-white p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs font-extrabold uppercase tracking-wider text-ink-muted">Latest result</p>
            <h2 id="latest-result-heading" className="mt-1 text-xl font-extrabold text-ink">Estimated property value</h2>
            <p className="numeric mt-2 text-4xl font-extrabold tracking-tight text-primary sm:text-5xl">{formatMoney(estimate.predicted_price)}</p>
          </div>
          <span className="rounded-lg border border-border-ui bg-white px-3 py-2 text-xs font-bold text-ink">Model {estimate.model_version}</span>
        </div>
        <p className="mt-2 text-xs text-ink-muted">Model estimate in USD. This is not a formal appraisal.</p>
      </div>
      <div className="p-5 sm:p-6">
        <div className="h-36" role="img" aria-label={label}>
          <Bar aria-hidden="true" data={{ labels: ["Estimated value"], datasets: [{ data: [predicted], backgroundColor: "#e2383f", borderRadius: 6, barThickness: 28 }] }} options={options} />
        </div>
        <dl className="mt-5 grid gap-3 sm:grid-cols-3">
          <div className="rounded-lg bg-slate-50 p-3"><dt className="flex items-center gap-2 text-xs font-semibold text-ink-muted"><DollarSign className="size-4" aria-hidden="true" />Exact value</dt><dd className="numeric mt-1 font-extrabold text-ink">{formatMoney(estimate.predicted_price)}</dd></div>
          <div className="rounded-lg bg-slate-50 p-3"><dt className="flex items-center gap-2 text-xs font-semibold text-ink-muted"><CalendarClock className="size-4" aria-hidden="true" />Created</dt><dd className="mt-1 text-sm font-bold text-ink">{formatTimestamp(estimate.created_at)}</dd></div>
          <div className="rounded-lg bg-slate-50 p-3"><dt className="flex items-center gap-2 text-xs font-semibold text-ink-muted"><Database className="size-4" aria-hidden="true" />Estimate ID</dt><dd className="mt-1 truncate font-mono text-xs text-ink" title={estimate.estimate_id}>{estimate.estimate_id}</dd></div>
        </dl>
        <div className="mt-5 overflow-x-auto rounded-lg border border-border-ui">
          <table className="w-full text-sm">
            <caption className="sr-only">Inputs used for this prediction</caption>
            <tbody className="divide-y divide-border-ui">
              {propertyFieldMetadata.map((field) => <tr key={field.name}><th scope="row" className="px-3 py-2 text-left font-semibold text-ink">{field.label}<span className="ml-1 text-xs font-normal text-ink-muted">({field.unit})</span></th><td className="numeric px-3 py-2 text-right text-ink">{formatNumber(estimate.inputs[field.name as PropertyFieldName])}</td></tr>)}
            </tbody>
          </table>
        </div>
      </div>
      </m.div>
      </AnimatePresence>
    </Panel>
  );
}
