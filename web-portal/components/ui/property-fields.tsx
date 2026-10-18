import type { PropertyFieldName } from "@/lib/api/types";
import type { PropertyDraft, PropertyErrors } from "@/lib/validation/property";

export const propertyFieldMetadata: Array<{
  name: PropertyFieldName;
  label: string;
  unit: string;
  step: string;
  min?: string;
}> = [
  { name: "square_footage", label: "Interior floor area", unit: "sq. ft.", step: "1", min: "1" },
  { name: "bedrooms", label: "Bedrooms", unit: "whole number", step: "1", min: "0" },
  { name: "bathrooms", label: "Bathrooms", unit: "0.5 increments", step: "0.5", min: "0.5" },
  { name: "year_built", label: "Year built", unit: "calendar year", step: "1", min: "1" },
  { name: "lot_size", label: "Lot size", unit: "sq. ft.", step: "1", min: "0" },
  { name: "distance_to_city_center", label: "Distance to city centre", unit: "miles", step: "0.001", min: "0" },
  { name: "school_rating", label: "School rating", unit: "advisory range 1–10", step: "0.001" },
];

interface PropertyFieldsProps {
  draft: PropertyDraft;
  errors: PropertyErrors;
  disabled?: boolean;
  onChange: (name: PropertyFieldName, value: string) => void;
  onBlur?: (name: PropertyFieldName) => void;
  idPrefix: string;
}

export function PropertyFields({
  draft,
  errors,
  disabled = false,
  onChange,
  onBlur,
  idPrefix,
}: PropertyFieldsProps): React.ReactElement {
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      {propertyFieldMetadata.map((field) => {
        const id = `${idPrefix}-${field.name}`;
        const errorId = `${id}-error`;
        const helpId = `${id}-help`;
        return (
          <div key={field.name} className="field-group">
            <label htmlFor={id} className="field-label">
              {field.label} <span aria-hidden="true" className="text-red-700">*</span>
            </label>
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
              className="field-input"
            />
            <p id={helpId} className="field-help">{field.unit}</p>
            {errors[field.name] ? (
              <p id={errorId} className="field-error" role="alert">{errors[field.name]}</p>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
