"use client";

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
import { m } from "motion/react";
import { Panel } from "@/components/ui/primitives";
import type { Estimate, PropertyFieldName } from "@/lib/api/types";
import { propertyFieldMetadata } from "@/components/ui/property-fields";
import { formatMoney, formatNumber } from "@/lib/format";

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip, Legend);

export function Comparison({ estimates }: { estimates: Estimate[] }): React.ReactElement | null {
  if (estimates.length < 2) return null;
  const chartLabel = `Comparison chart. ${estimates.map((item, index) => `Property ${index + 1}: ${formatMoney(item.predicted_price)}`).join("; ")}`;
  const options: ChartOptions<"bar"> = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: { legend: { display: false }, tooltip: { callbacks: { label: (context) => formatMoney(context.parsed.y ?? 0) } } },
    scales: {
      x: { grid: { display: false }, ticks: { color: "#0d1b33", font: { weight: 600 } } },
      y: { beginAtZero: true, grid: { color: "#e8edf2" }, ticks: { color: "#607087", callback: (value) => `$${Number(value) / 1000}K` } },
    },
  };
  return (
    <m.div layout initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }}>
    <Panel className="p-5 sm:p-6" aria-labelledby="comparison-heading">
      <h2 id="comparison-heading" className="text-xl font-extrabold text-ink">Properties side by side</h2>
      <p className="mt-1 text-sm text-ink-muted">Selected estimates share the same field order and exact units.</p>
      <div className="mt-4 h-48" role="img" aria-label={chartLabel}>
        <Bar aria-hidden="true" data={{ labels: estimates.map((_, index) => `Property ${index + 1}`), datasets: [{ data: estimates.map((item) => Number(item.predicted_price)), backgroundColor: ["#2376dc", "#217a57", "#d97706", "#e2383f"], borderRadius: 5 }] }} options={options} />
      </div>
      <div className="mt-5 max-w-full overflow-x-auto rounded-lg border border-border-ui" tabIndex={0} aria-label="Selected estimate comparison; scroll horizontally when needed">
        <table className="w-full min-w-[48rem] border-collapse text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-ink-muted"><tr><th className="p-3">Attribute</th>{estimates.map((item, index) => <th className="p-3" key={item.estimate_id}>Property {index + 1}</th>)}</tr></thead>
          <tbody className="divide-y divide-border-ui">
            <tr><th scope="row" className="p-3 text-left">Predicted value</th>{estimates.map((item) => <td className="numeric p-3 font-extrabold text-primary" key={item.estimate_id}>{formatMoney(item.predicted_price)}</td>)}</tr>
            {propertyFieldMetadata.map((field) => <tr key={field.name}><th scope="row" className="p-3 text-left">{field.label}<span className="block text-xs font-normal text-ink-muted">{field.unit}</span></th>{estimates.map((item) => <td className="numeric p-3" key={item.estimate_id}>{formatNumber(item.inputs[field.name as PropertyFieldName])}</td>)}</tr>)}
            <tr><th scope="row" className="p-3 text-left">Model version</th>{estimates.map((item) => <td className="p-3" key={item.estimate_id}>{item.model_version}</td>)}</tr>
          </tbody>
        </table>
      </div>
    </Panel>
    </m.div>
  );
}
