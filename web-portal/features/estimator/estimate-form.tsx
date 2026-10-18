"use client";

import { Calculator } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { PropertyFields } from "@/components/ui/property-fields";
import { Button, Panel, Spinner } from "@/components/ui/primitives";
import { StatusMessage } from "@/components/ui/status-message";
import type { PropertyFieldName, PropertyInput } from "@/lib/api/types";
import {
  emptyPropertyDraft,
  propertyFromDraft,
  validatePropertyDraft,
  type PropertyDraft,
  type PropertyErrors,
} from "@/lib/validation/property";
import type { PortalApiError } from "@/features/estimator/api";

interface EstimateFormProps {
  busy: boolean;
  apiError: PortalApiError | null;
  scenario?: PropertyInput | null;
  onSubmit: (input: PropertyInput, requestId: string) => Promise<unknown>;
}

const presets: Array<{ label: string; value: PropertyInput }> = [
  { label: "City apartment", value: { square_footage: 850, bedrooms: 2, bathrooms: 1.5, year_built: 2018, lot_size: 0, distance_to_city_center: 1.5, school_rating: 7.5 } },
  { label: "Family house", value: { square_footage: 2200, bedrooms: 4, bathrooms: 2.5, year_built: 2008, lot_size: 7200, distance_to_city_center: 6.25, school_rating: 8.5 } },
];

function toDraft(input: PropertyInput): PropertyDraft {
  return Object.fromEntries(Object.entries(input).map(([key, value]) => [key, String(value)])) as PropertyDraft;
}

export function EstimateForm({ busy, apiError, scenario, onSubmit }: EstimateFormProps): React.ReactElement {
  const [draft, setDraft] = useState<PropertyDraft>(emptyPropertyDraft);
  const [errors, setErrors] = useState<PropertyErrors>({});
  const requestRef = useRef<{ fingerprint: string; id: string } | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (!scenario) return;
    setDraft(toDraft(scenario));
    setErrors({});
    headingRef.current?.focus();
  }, [scenario]);

  function update(name: PropertyFieldName, value: string): void {
    setDraft((current) => ({ ...current, [name]: value }));
    setErrors((current) => ({ ...current, [name]: undefined }));
  }

  function validateOne(name: PropertyFieldName): void {
    const all = validatePropertyDraft(draft);
    setErrors((current) => ({ ...current, [name]: all[name] }));
  }

  async function submit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const nextErrors = validatePropertyDraft(draft);
    setErrors(nextErrors);
    const first = Object.keys(nextErrors)[0] as PropertyFieldName | undefined;
    if (first) {
      document.getElementById(`estimate-${first}`)?.focus();
      return;
    }
    const input = propertyFromDraft(draft);
    const fingerprint = JSON.stringify(input);
    if (!requestRef.current || requestRef.current.fingerprint !== fingerprint) {
      requestRef.current = { fingerprint, id: crypto.randomUUID() };
    }
    try {
      await onSubmit(input, requestRef.current.id);
      requestRef.current = null;
    } catch {
      // The state boundary supplies accessible error feedback and retains the id for safe retry.
    }
  }

  return (
    <Panel className="p-5 sm:p-6">
      <form onSubmit={submit} noValidate>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 ref={headingRef} tabIndex={-1} className="text-xl font-extrabold text-ink outline-none">Property details</h2>
            <p className="mt-1 text-sm text-ink-muted">All fields are required and follow the model contract.</p>
          </div>
          <p className="text-xs font-semibold text-ink-muted"><span className="text-primary">*</span> Required</p>
        </div>
        {apiError ? <div className="mt-4"><StatusMessage tone="error" title="Estimate not created">{apiError.message}{apiError.correlationId ? <span className="mt-1 block text-xs">Reference: {apiError.correlationId}</span> : null}</StatusMessage></div> : null}
        <div className="mt-4 flex flex-wrap items-center gap-2" aria-label="Example property presets">
          <span className="text-xs font-bold text-ink-muted">Try an example:</span>
          {presets.map((preset) => <Button key={preset.label} type="button" variant="quiet" disabled={busy} onClick={() => { setDraft(toDraft(preset.value)); setErrors({}); }}>{preset.label}</Button>)}
        </div>
        <fieldset disabled={busy} className="mt-5">
          <legend className="sr-only">Property characteristics</legend>
          <PropertyFields draft={draft} errors={errors} disabled={busy} onChange={update} onBlur={validateOne} idPrefix="estimate" />
        </fieldset>
        <Button type="submit" disabled={busy} className="mt-5 w-full sm:w-auto sm:min-w-48">
          {busy ? <Spinner label="Estimating…" /> : <><Calculator className="size-4" aria-hidden="true" />Estimate value</>}
        </Button>
        {busy ? <p role="status" aria-live="polite" className="mt-3 text-sm text-ink-muted">The model is calculating and saving your estimate.</p> : null}
      </form>
    </Panel>
  );
}
