"use client";

import { useState } from "react";
import { PropertyFields } from "@/components/ui/property-fields";
import { StatusMessage } from "@/components/ui/status-message";
import { PortalApiError } from "@/features/estimator/api";
import { runWhatIf } from "@/features/market/api";
import type { PropertyFieldName, WhatIfResponse } from "@/lib/api/types";
import { formatMoney } from "@/lib/format";
import {
  emptyPropertyDraft,
  propertyFromDraft,
  validatePropertyDraft,
  type PropertyDraft,
  type PropertyErrors,
} from "@/lib/validation/property";

export function WhatIfForm({ onResult }: { onResult: (value: WhatIfResponse) => void }): React.ReactElement {
  const [draft, setDraft] = useState<PropertyDraft>(emptyPropertyDraft);
  const [errors, setErrors] = useState<PropertyErrors>({});
  const [result, setResult] = useState<WhatIfResponse | null>(null);
  const [error, setError] = useState<PortalApiError | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const nextErrors = validatePropertyDraft(draft);
    setErrors(nextErrors);
    const first = Object.keys(nextErrors)[0];
    if (first) {
      document.getElementById(`what-if-${first}`)?.focus();
      return;
    }
    setPending(true);
    setError(null);
    try {
      const response = await runWhatIf(propertyFromDraft(draft));
      setResult(response);
      onResult(response);
    } catch (requestError) {
      setError(requestError instanceof PortalApiError ? requestError : new PortalApiError("Prediction is temporarily unavailable.", "REQUEST_FAILED"));
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="card p-5 sm:p-6" aria-labelledby="what-if-heading">
      <p className="eyebrow">Shared model</p>
      <h2 id="what-if-heading" className="mt-1 text-2xl font-semibold text-slate-950">What-if estimate</h2>
      <p className="mt-1 max-w-3xl text-sm text-slate-600">Change property characteristics and estimate a value without adding a record to estimator history.</p>
      <form onSubmit={submit} noValidate className="mt-6">
        <fieldset disabled={pending}>
          <legend className="sr-only">What-if property characteristics</legend>
          <PropertyFields
            draft={draft}
            errors={errors}
            disabled={pending}
            onChange={(name: PropertyFieldName, value: string) => {
              setDraft((current) => ({ ...current, [name]: value }));
              setErrors((current) => ({ ...current, [name]: undefined }));
            }}
            onBlur={(name) => setErrors((current) => ({ ...current, [name]: validatePropertyDraft(draft)[name] }))}
            idPrefix="what-if"
          />
        </fieldset>
        <button type="submit" className="button-primary mt-5" disabled={pending}>{pending ? "Calculating…" : "Run what-if"}</button>
      </form>
      {error ? <div className="mt-5"><StatusMessage tone="error" title="What-if estimate unavailable" live>{error.message}{error.correlationId ? <span className="block text-xs">Reference: {error.correlationId}</span> : null}</StatusMessage></div> : null}
      {result ? (
        <div className="mt-5 rounded-xl border border-teal-200 bg-teal-50 p-5" role="status" aria-live="polite">
          <p className="text-sm font-semibold text-teal-900">Estimated value · model {result.prediction.model_version}</p>
          <p className="numeric mt-1 text-3xl font-bold text-teal-900">{formatMoney(result.prediction.predicted_price)}</p>
          {result.prediction.warnings.map((warning) => <p key={warning.field} className="mt-3 text-sm font-medium text-amber-900">Advisory: {warning.field} value {warning.submitted_value}; expected range {warning.advisory_min}–{warning.advisory_max}. {warning.reason}</p>)}
        </div>
      ) : null}
    </section>
  );
}
