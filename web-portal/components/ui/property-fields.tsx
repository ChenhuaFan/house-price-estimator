import type { PropertyFieldName } from "@/lib/api/types";
import type { PropertyDraft, PropertyErrors } from "@/lib/validation/property";

export const propertyFieldMetadata: Array<{
  name: PropertyFieldName;
  label: string;
  unit: string;
  suffix?: string;
  step: string;
  min?: string;
}> = [
  { name: "square_footage", label: "Interior floor area", unit: "Square feet", suffix: "sq. ft.", step: "1", min: "1" },
  { name: "bedrooms", label: "Bedrooms", unit: "Whole number", step: "1", min: "0" },
  { name: "bathrooms", label: "Bathrooms", unit: "0.5 increments", step: "0.5", min: "0.5" },
  { name: "year_built", label: "Year built", unit: "Calendar year", step: "1", min: "1" },
  { name: "lot_size", label: "Lot size", unit: "Square feet", suffix: "sq. ft.", step: "1", min: "0" },
  { name: "distance_to_city_center", label: "Distance to city centre", unit: "Miles", suffix: "mi", step: "0.001", min: "0" },
  { name: "school_rating", label: "School rating", unit: "Advisory range 1–10", suffix: "/ 10", step: "0.001" },
];

interface PropertyFieldsProps {
  draft: PropertyDraft;
  errors: PropertyErrors;
  disabled?: boolean;
  onChange: (name: PropertyFieldName, value: string) => void;
  onBlur?: (name: PropertyFieldName) => void;
  idPrefix: string;
  compact?: boolean;
}

export function PropertyFields({
  draft,
  errors,
  disabled = false,
  onChange,
  onBlur,
  idPrefix,
  compact = false,
}: PropertyFieldsProps): React.ReactElement {
  return (
    <div className={compact ? "grid gap-4" : "grid gap-4 sm:grid-cols-2 xl:grid-cols-3"}>
      {propertyFieldMetadata.map((field) => {
        const id = `${idPrefix}-${field.name}`;
        const errorId = `${id}-error`;
        const helpId = `${id}-help`;
        const span = !compact && field.name === "school_rating" ? "xl:col-span-3" : "";
        return (
          <div key={field.name} className={span}>
            <label htmlFor={id} className="block text-sm font-bold text-ink">
              {field.label} <span aria-hidden="true" className="text-primary">*</span>
            </label>
            <div className="relative mt-1.5">
              <input
                id={id}
                name={field.name}
                type="number"
                inputMode="decimal"
                required
                value={draft[field.name]}
                min={field.min}
                step={field.step}
                disabled={disabled}
                aria-invalid={Boolean(errors[field.name])}
                aria-describedby={errors[field.name] ? `${helpId} ${errorId}` : helpId}
                onChange={(event) => onChange(field.name, event.target.value)}
                onBlur={() => onBlur?.(field.name)}
                className={`min-h-11 w-full rounded-lg border bg-white px-3 py-2.5 text-ink outline-none transition duration-200 disabled:cursor-not-allowed disabled:bg-slate-100 ${field.suffix ? "pr-16" : ""} ${
                  errors[field.name]
                    ? "border-danger ring-1 ring-danger"
                    : "border-slate-300 hover:border-slate-400 focus:border-primary focus:ring-2 focus:ring-primary/20"
                }`}
              />
              {field.suffix ? <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-xs font-medium text-ink-muted">{field.suffix}</span> : null}
            </div>
            <p id={helpId} className="mt-1 text-xs text-ink-muted">{field.unit}</p>
            {errors[field.name] ? <p id={errorId} className="mt-1 text-xs font-semibold text-danger" role="alert">{errors[field.name]}</p> : null}
          </div>
        );
      })}
    </div>
  );
}
