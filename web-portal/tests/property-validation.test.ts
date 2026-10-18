import { describe, expect, it } from "vitest";
import { propertyInput } from "@/tests/fixtures";
import {
  draftFromProperty,
  emptyPropertyDraft,
  propertyFromDraft,
  validatePropertyDraft,
  type PropertyDraft,
} from "@/lib/validation/property";

function validDraft(overrides: Partial<PropertyDraft> = {}): PropertyDraft {
  return {
    square_footage: "1800",
    bedrooms: "3",
    bathrooms: "2.5",
    year_built: "2005",
    lot_size: "6500",
    distance_to_city_center: "4.125",
    school_rating: "8.500",
    ...overrides,
  };
}

describe("property validation", () => {
  it("requires every field and focuses validation on actionable messages", () => {
    const errors = validatePropertyDraft(emptyPropertyDraft);
    expect(Object.keys(errors)).toHaveLength(7);
    expect(errors.square_footage).toBe("Enter a value.");
  });

  it.each([
    ["square_footage", "1.5", "Enter a whole number."],
    ["bedrooms", "-1", "Cannot be negative."],
    ["bathrooms", "2.2", "Use 0.5 increments, starting at 0.5."],
    ["year_built", "0", "Enter a valid positive year."],
    ["lot_size", "-1", "Cannot be negative."],
    ["distance_to_city_center", "-0.001", "Cannot be negative."],
    ["school_rating", "1.2345", "Use no more than 3 decimal places."],
    ["distance_to_city_center", "1e-4", "Use ordinary decimal notation."],
    ["school_rating", "NaN", "Use ordinary decimal notation."],
  ] as const)("rejects invalid %s values", (field, value, message) => {
    expect(validatePropertyDraft(validDraft({ [field]: value }))[field]).toBe(message);
  });

  it("accepts canonical boundaries, trailing zeroes, and advisory-only school ratings", () => {
    const draft = validDraft({
      square_footage: "1",
      bedrooms: "0",
      bathrooms: "0.50",
      year_built: "1",
      lot_size: "0",
      distance_to_city_center: "0.0000",
      school_rating: "11.0000",
    });
    expect(validatePropertyDraft(draft)).toEqual({});
  });

  it("converts between UI drafts and canonical API values", () => {
    expect(propertyFromDraft(validDraft())).toEqual(propertyInput);
    expect(draftFromProperty(propertyInput)).toEqual({
      square_footage: "1800",
      bedrooms: "3",
      bathrooms: "2.5",
      year_built: "2005",
      lot_size: "6500",
      distance_to_city_center: "4.125",
      school_rating: "8.5",
    });
  });
});
