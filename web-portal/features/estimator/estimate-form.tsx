"use client";

import { useRef, useState } from "react";
import { PropertyFields } from "@/components/ui/property-fields";
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
  onSubmit: (input: PropertyInput, requestId: string) => Promise<unknown>;
}

export function EstimateForm({ busy, apiError, onSubmit }: EstimateFormProps): React.ReactElement {
  const [draft, setDraft] = useState<PropertyDraft>(emptyPropertyDraft);
  const [errors, setErrors] = useState<PropertyErrors>({});
  const requestRef = useRef<{ fingerprint: string; id: string } | null>(null);

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
      // The hook supplies the accessible error state. Keep the request ID for a safe retry.
    }
  }

  return (
    <form onSubmit={submit} noValidate className="card p-5 sm:p-6">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="eyebrow">New estimate</p>
          <h2 className="mt-1 text-2xl font-semibold text-slate-950">Describe the property</h2>
          <p className="mt-1 max-w-3xl text-sm text-slate-600">All fields are required. Values outside observed training ranges are accepted when they meet the documented domain rules.</p>
        </div>
        <p className="text-sm font-medium text-slate-600"><span className="text-red-700">*</span> Required</p>
      </div>
      {apiError ? (
        <div className="status-message status-error mt-5" role="alert">
          <p className="font-semibold">Estimate not created</p>
          <p className="mt-1 text-sm">{apiError.message}</p>
          {apiError.correlationId ? <p className="mt-1 text-xs">Reference: {apiError.correlationId}</p> : null}
        </div>
      ) : null}
      <fieldset disabled={busy} className="mt-6">
        <legend className="sr-only">Property characteristics</legend>
        <PropertyFields
          draft={draft}
          errors={errors}
          disabled={busy}
          onChange={update}
          onBlur={validateOne}
          idPrefix="estimate"
        />
      </fieldset>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button type="submit" disabled={busy} className="button-primary min-w-40">
          {busy ? "Estimating…" : "Estimate value"}
        </button>
        {busy ? <p role="status" aria-live="polite" className="text-sm text-slate-600">The model is calculating and saving your estimate.</p> : null}
      </div>
    </form>
  );
}
