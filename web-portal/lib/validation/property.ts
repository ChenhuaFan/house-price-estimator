import type { PropertyFieldName, PropertyInput } from "@/lib/api/types";

export type PropertyDraft = Record<PropertyFieldName, string>;
export type PropertyErrors = Partial<Record<PropertyFieldName, string>>;

export const emptyPropertyDraft: PropertyDraft = {
  square_footage: "",
  bedrooms: "",
  bathrooms: "",
  year_built: "",
  lot_size: "",
  distance_to_city_center: "",
  school_rating: "",
};

const wholeNumberFields = new Set<PropertyFieldName>([
  "square_footage",
  "bedrooms",
  "year_built",
  "lot_size",
]);

const maximumFractionDigits: Partial<Record<PropertyFieldName, number>> = {
  bathrooms: 1,
  distance_to_city_center: 3,
  school_rating: 3,
};

export function validatePropertyDraft(draft: PropertyDraft): PropertyErrors {
  const errors: PropertyErrors = {};
  for (const [name, rawValue] of Object.entries(draft) as Array<[PropertyFieldName, string]>) {
    const trimmed = rawValue.trim();
    if (!trimmed) {
      errors[name] = "Enter a value.";
      continue;
    }
    const value = Number(trimmed);
    if (!Number.isFinite(value)) {
      errors[name] = "Enter a finite number.";
      continue;
    }
    if (wholeNumberFields.has(name) && !Number.isInteger(value)) {
      errors[name] = "Enter a whole number.";
      continue;
    }
    const scale = maximumFractionDigits[name];
    const fraction = trimmed.includes(".") ? trimmed.replace(/0+$/, "").split(".")[1]?.length ?? 0 : 0;
    if (scale !== undefined && fraction > scale) {
      errors[name] = `Use no more than ${scale} decimal place${scale === 1 ? "" : "s"}.`;
      continue;
    }
    if (name === "square_footage" && value < 1) errors[name] = "Must be at least 1 sq. ft.";
    if (name === "bedrooms" && value < 0) errors[name] = "Cannot be negative.";
    if (name === "bathrooms" && (value < 0.5 || value % 0.5 !== 0)) {
      errors[name] = "Use 0.5 increments, starting at 0.5.";
    }
    if (name === "year_built" && value < 1) errors[name] = "Enter a valid positive year.";
    if (name === "lot_size" && value < 0) errors[name] = "Cannot be negative.";
    if (name === "distance_to_city_center" && value < 0) errors[name] = "Cannot be negative.";
  }
  return errors;
}

export function propertyFromDraft(draft: PropertyDraft): PropertyInput {
  return {
    square_footage: Number(draft.square_footage),
    bedrooms: Number(draft.bedrooms),
    bathrooms: Number(draft.bathrooms),
    year_built: Number(draft.year_built),
    lot_size: Number(draft.lot_size),
    distance_to_city_center: Number(draft.distance_to_city_center),
    school_rating: Number(draft.school_rating),
  };
}

export function draftFromProperty(input: PropertyInput): PropertyDraft {
  return Object.fromEntries(
    Object.entries(input).map(([key, value]) => [key, String(value)]),
  ) as PropertyDraft;
}
