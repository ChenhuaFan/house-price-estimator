import type { Estimate, PropertyFieldName } from "@/lib/api/types";
import { propertyFieldMetadata } from "@/components/ui/property-fields";
import { formatMoney, formatNumber } from "@/lib/format";

export function Comparison({ estimates }: { estimates: Estimate[] }): React.ReactElement | null {
  if (estimates.length < 2) return null;
  return (
    <section className="card p-5 sm:p-6" aria-labelledby="comparison-heading">
      <p className="eyebrow">Comparison</p>
      <h2 id="comparison-heading" className="mt-1 text-2xl font-semibold text-slate-950">Properties side by side</h2>
      <p className="mt-1 text-sm text-slate-600">Values are aligned by field so differences remain understandable without relying on colour.</p>
      <div className="data-table-wrap mt-6" tabIndex={0} aria-label="Selected estimate comparison; scroll horizontally when needed">
        <table className="data-table">
          <thead><tr><th scope="col">Attribute</th>{estimates.map((item, index) => <th scope="col" key={item.estimate_id}>Property {index + 1}</th>)}</tr></thead>
          <tbody>
            <tr><th scope="row">Predicted value</th>{estimates.map((item) => <td className="numeric font-bold text-teal-800" key={item.estimate_id}>{formatMoney(item.predicted_price)}</td>)}</tr>
            {propertyFieldMetadata.map((field) => (
              <tr key={field.name}>
                <th scope="row">{field.label} <span className="block text-xs font-normal normal-case tracking-normal text-slate-500">{field.unit}</span></th>
                {estimates.map((item) => <td className="numeric" key={item.estimate_id}>{formatNumber(item.inputs[field.name as PropertyFieldName])}</td>)}
              </tr>
            ))}
            <tr><th scope="row">Model version</th>{estimates.map((item) => <td key={item.estimate_id}>{item.model_version}</td>)}</tr>
          </tbody>
        </table>
      </div>
    </section>
  );
}
