"use client";

import { useEffect, useReducer, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { ExportFormat, ExportJob, MarketPageModel, WhatIfResponse } from "@/lib/api/types";
import { PortalApiError } from "@/features/estimator/api";
import { cancelExport, createExport, getExport } from "@/features/market/api";
import { MarketDashboardView } from "@/features/market/market-dashboard-view";
import { marketFilterKeys, marketSelection } from "@/features/market/model";
import type { MarketFilterValues } from "@/features/market/filter-form";
import { useLiveAnnouncement } from "@/hooks/use-live-announcement";

interface TrackedExport {
  job: ExportJob;
  retryAfterSeconds: number;
}

type ExportAction =
  | { type: "upsert"; value: TrackedExport }
  | { type: "remove"; id: string };

function exportReducer(state: TrackedExport[], action: ExportAction): TrackedExport[] {
  if (action.type === "remove") return state.filter((item) => item.job.export_id !== action.id);
  const exists = state.some((item) => item.job.export_id === action.value.job.export_id);
  return exists
    ? state.map((item) => item.job.export_id === action.value.job.export_id ? action.value : item)
    : [action.value, ...state];
}

const terminalStatuses = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"]);

export function MarketDashboard({ initialModel }: { initialModel: MarketPageModel }): React.ReactElement {
  const router = useRouter();
  const [isNavigating, startNavigation] = useTransition();
  const [whatIf, setWhatIf] = useState<WhatIfResponse | null>(null);
  const [exports, dispatchExport] = useReducer(exportReducer, []);
  const [exportError, setExportError] = useState<PortalApiError | null>(null);
  const [creatingFormat, setCreatingFormat] = useState<ExportFormat | null>(null);
  const [announcement, announce] = useLiveAnnouncement();

  useEffect(() => {
    const active = exports.filter((item) => !terminalStatuses.has(item.job.status));
    if (active.length === 0) return;
    const controller = new AbortController();
    const delay = Math.max(1, Math.min(...active.map((item) => item.retryAfterSeconds))) * 1000;
    let timer: number | undefined;

    async function poll(): Promise<void> {
      if (document.visibilityState !== "visible") {
        timer = window.setTimeout(() => void poll(), 1000);
        return;
      }
      await Promise.all(active.map(async (item) => {
        try {
          const updated = await getExport(item.job.export_id, controller.signal);
          dispatchExport({ type: "upsert", value: updated });
        } catch (requestError) {
          if (requestError instanceof Error && requestError.name === "AbortError") return;
          setExportError(requestError instanceof PortalApiError
            ? requestError
            : new PortalApiError("Export status could not be refreshed.", "EXPORT_STATUS_FAILED"));
        }
      }));
      if (!controller.signal.aborted) timer = window.setTimeout(() => void poll(), delay);
    }

    timer = window.setTimeout(() => void poll(), delay);
    return () => {
      if (timer !== undefined) window.clearTimeout(timer);
      controller.abort();
    };
  }, [exports]);

  function navigate(params: URLSearchParams, replace = false): void {
    const suffix = params.toString();
    startNavigation(() => {
      if (replace) router.replace(suffix ? `/market?${suffix}` : "/market", { scroll: false });
      else router.push(suffix ? `/market?${suffix}` : "/market", { scroll: false });
    });
  }

  function baseParams(): URLSearchParams {
    const params = new URLSearchParams();
    for (const key of marketFilterKeys) {
      const value = initialModel.query[key];
      if (value) params.set(key, String(value));
    }
    params.set("sort", initialModel.query.sort);
    params.set("limit", String(initialModel.query.limit));
    params.set("dataset_version_id", initialModel.datasetVersion.id);
    return params;
  }

  function applyFilters(values: MarketFilterValues): void {
    const params = baseParams();
    for (const key of marketFilterKeys) {
      const value = values[key];
      if (value) params.set(key, value);
      else params.delete(key);
    }
    params.delete("cursor");
    navigate(params);
  }

  function resetFilters(): void {
    const params = new URLSearchParams({
      sort: initialModel.query.sort,
      limit: String(initialModel.query.limit),
      dataset_version_id: initialModel.datasetVersion.id,
    });
    navigate(params);
  }

  function sortBy(column: string): void {
    const params = baseParams();
    const [currentField, currentDirection] = initialModel.query.sort.split(":");
    params.set("sort", `${column}:${currentField === column && currentDirection === "asc" ? "desc" : "asc"}`);
    params.delete("cursor");
    navigate(params);
  }

  function nextPage(): void {
    if (!initialModel.page.nextCursor) return;
    const params = baseParams();
    params.set("cursor", initialModel.page.nextCursor);
    navigate(params);
  }

  async function startExport(format: ExportFormat): Promise<void> {
    setCreatingFormat(format);
    setExportError(null);
    try {
      const request = {
        format,
        selection: marketSelection(initialModel),
        ...(format === "PDF" ? {
          pdf_options: {
            template: "market-analysis-v1" as const,
            include_details: true,
            detail_limit: 500,
            metrics: [
              { id: "count", method: "COUNT" as const, field: "*" as const },
              { id: "average_price", method: "AVG" as const, field: "price" as const },
            ],
            ...(whatIf ? { what_if: whatIf } : {}),
          },
        } : {}),
      };
      const accepted = await createExport(request, crypto.randomUUID());
      dispatchExport({ type: "upsert", value: accepted });
      announce(`${format} export queued.`);
    } catch (requestError) {
      setExportError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("The export could not be queued.", "EXPORT_FAILED"));
    } finally {
      setCreatingFormat(null);
    }
  }

  async function cancel(id: string): Promise<void> {
    setExportError(null);
    try {
      await cancelExport(id);
      const tracked = exports.find((item) => item.job.export_id === id);
      if (tracked) dispatchExport({ type: "upsert", value: { ...tracked, job: { ...tracked.job, status: "CANCELLED" } } });
      announce("Export cancelled.");
    } catch (requestError) {
      setExportError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("The export could not be cancelled.", "EXPORT_CANCEL_FAILED"));
    }
  }

  return (
    <MarketDashboardView
      model={initialModel}
      values={initialModel.query}
      navigating={isNavigating}
      whatIf={whatIf}
      exports={exports.map((item) => item.job)}
      creatingFormat={creatingFormat}
      exportError={exportError}
      announcement={announcement}
      onApplyFilters={applyFilters}
      onResetFilters={resetFilters}
      onSort={sortBy}
      onNext={nextPage}
      onBack={() => router.back()}
      onExport={startExport}
      onCancelExport={cancel}
      onDismissExport={(id) => dispatchExport({ type: "remove", id })}
      onWhatIfResult={setWhatIf}
    />
  );
}
