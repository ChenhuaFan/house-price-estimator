import type { ChartPoint } from "@/lib/api/types";
import { formatMoney, formatNumber } from "@/lib/format";

function BarChart({
  title,
  description,
  points,
  money = false,
}: {
  title: string;
  description: string;
  points: ChartPoint[];
  money?: boolean;
}): React.ReactElement {
  const maximum = Math.max(...points.map((point) => Number(point.value)), 1);
  return (
    <section className="card p-5" aria-labelledby={`${title.replace(/\s+/g, "-").toLowerCase()}-heading`}>
      <h3 id={`${title.replace(/\s+/g, "-").toLowerCase()}-heading`} className="text-lg font-semibold text-slate-950">{title}</h3>
      <p className="mt-1 text-sm text-slate-600">{description}</p>
      {points.length === 0 ? <p className="mt-6 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-600">No matching data to chart.</p> : (
        <div className="mt-5 space-y-3" role="img" aria-label={`${title}. ${points.map((point) => `${point.label}: ${money ? formatMoney(point.value) : formatNumber(point.value)}`).join("; ")}`}>
          {points.map((point) => (
            <div key={point.label} className="grid grid-cols-[4.5rem_minmax(0,1fr)_7rem] items-center gap-2 text-sm">
              <span className="font-medium text-slate-700">{point.label}</span>
              <span className="h-5 overflow-hidden rounded bg-slate-200">
                <span className="block h-full rounded bg-teal-700" style={{ width: `${Math.max(3, (Number(point.value) / maximum) * 100)}%` }} />
              </span>
              <span className="numeric text-right font-semibold text-slate-800">{money ? formatMoney(point.value) : formatNumber(point.value)}</span>
            </div>
          ))}
        </div>
      )}
      <details className="mt-5 text-sm">
        <summary className="min-h-11 cursor-pointer py-2 font-semibold text-teal-800">View chart data as text</summary>
        <ul className="space-y-1 pl-5 text-slate-700">
          {points.map((point) => <li key={point.label}>{point.label}: {money ? formatMoney(point.value) : formatNumber(point.value)}</li>)}
        </ul>
      </details>
    </section>
  );
}

export function MarketCharts({
  priceByBedrooms,
  countBySchoolBand,
}: {
  priceByBedrooms: ChartPoint[];
  countBySchoolBand: ChartPoint[];
}): React.ReactElement {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <BarChart title="Average price by bedrooms" description="Exact average USD price in each bedroom-count group." points={priceByBedrooms} money />
      <BarChart title="Properties by school-rating band" description="Number of matching source properties in each rating band." points={countBySchoolBand} />
    </div>
  );
}
