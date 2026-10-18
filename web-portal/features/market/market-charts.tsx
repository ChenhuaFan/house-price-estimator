"use client";

import {
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Filler,
  Legend,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
  type ChartOptions,
} from "chart.js";
import { Bar, Line } from "react-chartjs-2";
import { Panel } from "@/components/ui/primitives";
import type { ChartPoint } from "@/lib/api/types";
import { formatMoney, formatNumber } from "@/lib/format";

ChartJS.register(CategoryScale, LinearScale, BarElement, LineElement, PointElement, Filler, Tooltip, Legend);

function ChartPanel({
  title,
  description,
  points,
  money = false,
  kind,
}: {
  title: string;
  description: string;
  points: ChartPoint[];
  money?: boolean;
  kind: "line" | "bar";
}): React.ReactElement {
  const headingId = `${title.replace(/\s+/g, "-").toLowerCase()}-heading`;
  const accessible = `${title}. ${points.map((point) => `${point.label}: ${money ? formatMoney(point.value) : formatNumber(point.value)}`).join("; ")}`;
  const lineOptions: ChartOptions<"line"> = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (context) => money ? formatMoney(context.parsed.y ?? 0) : formatNumber(context.parsed.y ?? 0) } },
    },
    scales: {
      x: { title: { display: true, text: "Bedrooms", color: "#607087" }, grid: { color: "#edf0f3" }, ticks: { color: "#607087" } },
      y: { beginAtZero: true, title: { display: true, text: "Average price", color: "#607087" }, grid: { color: "#e6ebf0" }, ticks: { color: "#607087", callback: (value) => `$${Number(value) / 1000}K` } },
    },
  };
  const barOptions: ChartOptions<"bar"> = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (context) => money ? formatMoney(context.parsed.x ?? 0) : formatNumber(context.parsed.x ?? 0) } },
    },
    indexAxis: "y",
    scales: {
      x: { beginAtZero: true, title: { display: true, text: "Properties", color: "#607087" }, grid: { color: "#edf0f3" }, ticks: { precision: 0, color: "#607087" } },
      y: { grid: { display: false }, ticks: { color: "#607087" } },
    },
  };
  return (
    <Panel className="min-w-0 p-4 sm:p-5" aria-labelledby={headingId}>
      <h3 id={headingId} className="text-base font-extrabold text-ink sm:text-lg">{title}</h3>
      <p className="mt-1 text-xs text-ink-muted sm:text-sm">{description}</p>
      {points.length === 0 ? <p className="mt-5 rounded-lg bg-slate-50 p-8 text-center text-sm text-ink-muted">No matching data to chart.</p> : (
        <>
          <div className="mt-4 h-64" role="img" aria-label={accessible}>
            {kind === "line" ? (
              <Line aria-hidden="true" data={{ labels: points.map((point) => point.label), datasets: [{ data: points.map((point) => point.value), borderColor: "#2376dc", backgroundColor: "rgba(35,118,220,.14)", fill: true, tension: 0.25, pointBackgroundColor: "#2376dc", pointBorderColor: "#ffffff", pointBorderWidth: 2, pointRadius: 4, pointHoverRadius: 6 }] }} options={lineOptions} />
            ) : (
              <Bar aria-hidden="true" data={{ labels: points.map((point) => point.label), datasets: [{ data: points.map((point) => point.value), backgroundColor: ["#2f765b", "#67a989", "#a8d3bb", "#e5c89e", "#f0aa7f", "#e76b6b"], borderRadius: 2, barThickness: 18 }] }} options={barOptions} />
            )}
          </div>
          <details className="mt-3 text-sm"><summary className="min-h-11 cursor-pointer py-2 font-bold text-blue">View chart data as text</summary><ul className="space-y-1 pl-5 text-ink-muted">{points.map((point) => <li key={point.label}>{point.label}: {money ? formatMoney(point.value) : formatNumber(point.value)}</li>)}</ul></details>
        </>
      )}
    </Panel>
  );
}

export function MarketCharts({ priceByBedrooms, countBySchoolBand }: { priceByBedrooms: ChartPoint[]; countBySchoolBand: ChartPoint[] }): React.ReactElement {
  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <ChartPanel title="Average price by bedrooms" description="Average actual USD price in each bedroom-count group." points={priceByBedrooms} money kind="line" />
      <ChartPanel title="Properties by school rating" description="Number of matching properties in each school-rating band." points={countBySchoolBand} kind="bar" />
    </div>
  );
}
