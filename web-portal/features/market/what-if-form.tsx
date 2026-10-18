"use client";

import { Calculator } from "lucide-react";
import { useState } from "react";
import { PropertyFields } from "@/components/ui/property-fields";
import { Button, Panel, Spinner } from "@/components/ui/primitives";
import { StatusMessage } from "@/components/ui/status-message";
import { PortalApiError } from "@/features/estimator/api";
import { runWhatIf } from "@/features/market/api";
import type { MarketOverview, PropertyFieldName, WhatIfResponse } from "@/lib/api/types";
import { formatMoney } from "@/lib/format";
import {
  emptyPropertyDraft,
  propertyFromDraft,
  validatePropertyDraft,
  type PropertyDraft,
  type PropertyErrors,
} from "@/lib/validation/property";

/** Collects a non-persistent scenario and displays its prediction against the market. */
export function WhatIfForm({
  onResult,
  disabled = false,
  marketOverview,
}: {
  onResult: (value: WhatIfResponse) => void;
  disabled?: boolean;
  marketOverview: MarketOverview;
}): React.ReactElement {
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
      setError(
        requestError instanceof PortalApiError
          ? requestError
          : new PortalApiError("Prediction is temporarily unavailable.", "REQUEST_FAILED"),
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <Panel className="p-4" aria-labelledby="what-if-heading">
      <h2 id="what-if-heading" className="text-xl font-extrabold text-ink">What-if estimate</h2>
      <p className="mt-1 text-xs text-ink-muted">Adjust all seven model fields without adding a record to estimator history.</p>
      <form onSubmit={submit} noValidate className="mt-4">
        <fieldset disabled={disabled || pending}>
          <legend className="sr-only">What-if property characteristics</legend>
          <PropertyFields
            draft={draft}
            errors={errors}
            disabled={disabled || pending}
            compact
            onChange={(name: PropertyFieldName, value: string) => {
              setDraft((current) => ({ ...current, [name]: value }));
              setErrors((current) => ({ ...current, [name]: undefined }));
            }}
            onBlur={(name) => setErrors((current) => ({ ...current, [name]: validatePropertyDraft(draft)[name] }))}
            idPrefix="what-if"
          />
        </fieldset>
        <Button type="submit" className="mt-4 w-full" disabled={disabled || pending}>{pending ? <Spinner label="Calculating…" /> : <><Calculator className="size-4" aria-hidden="true" />Run what-if</>}</Button>
      </form>
      {error ? <div className="mt-4"><StatusMessage tone="error" title="What-if estimate unavailable" live>{error.message}{error.correlationId ? <span className="block text-xs">Reference: {error.correlationId}</span> : null}</StatusMessage></div> : null}
      {result ? (
        <div className="mt-4 rounded-lg border border-red-100 bg-primary-soft p-4" role="status" aria-live="polite">
          <p className="text-xs font-bold text-ink-muted">Estimated property value · model {result.prediction.model_version}</p>
          <p className="numeric mt-1 text-3xl font-extrabold text-primary">{formatMoney(result.prediction.predicted_price)}</p>
          {marketOverview.averagePrice ? <p className="mt-2 text-xs text-ink-muted">Filtered-market context: {formatMoney(Number(result.prediction.predicted_price) - Number(marketOverview.averagePrice))} versus the segment average. This is context, not confidence.</p> : null}
          {result.prediction.warnings.map((warning) => <p key={warning.field} className="mt-3 text-xs font-semibold text-amber-900">Advisory: {warning.field} value {warning.submitted_value}; expected range {warning.advisory_minimum}–{warning.advisory_maximum}. {warning.message}</p>)}
        </div>
      ) : null}
    </Panel>
  );
}
