"use client";

/** Smart container for dynamic dashboard reads, what-if state, and background export jobs. */
import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import type { ExportFormat, ExportJob, MarketPageModel, WhatIfResponse } from "@/lib/api/types";
import { PortalApiError } from "@/features/estimator/api";
import { cancelExport, createExport, getExport, loadMarketDashboard } from "@/features/market/api";
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
    ? state.map((item) =>
        item.job.export_id === action.value.job.export_id ? action.value : item,
      )
    : [action.value, ...state];
}

const terminalStatuses = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"]);

/** Coordinates client interactions while keeping the server-rendered dashboard visible. */
export function MarketDashboard({ initialModel }: { initialModel: MarketPageModel }): React.ReactElement {
  const [model, setModel] = useState(initialModel);
  const [isNavigating, setIsNavigating] = useState(false);
  const [dashboardError, setDashboardError] = useState<PortalApiError | null>(null);
  const [whatIf, setWhatIf] = useState<WhatIfResponse | null>(null);
  const [exports, dispatchExport] = useReducer(exportReducer, []);
  const [exportError, setExportError] = useState<PortalApiError | null>(null);
  const [creatingFormat, setCreatingFormat] = useState<ExportFormat | null>(null);
  const [announcement, announce] = useLiveAnnouncement();
  const dashboardRequest = useRef<AbortController | null>(null);
  const pageTrail = useRef<string[]>([]);

  useEffect(() => {
    const active = exports.filter((item) => !terminalStatuses.has(item.job.status));
    if (active.length === 0) return;

    const controller = new AbortController();
    const delay = Math.max(1, Math.min(...active.map((item) => item.retryAfterSeconds))) * 1000;
    let timer: number | undefined;

    // Hidden tabs retry locally instead of losing the active export polling cycle.
    async function poll(): Promise<void> {
      if (document.visibilityState !== "visible") {
        timer = window.setTimeout(() => void poll(), 1000);
        return;
      }

      await Promise.all(
        active.map(async (item) => {
          try {
            const updated = await getExport(item.job.export_id, controller.signal);
            dispatchExport({ type: "upsert", value: updated });
          } catch (requestError) {
            if (requestError instanceof Error && requestError.name === "AbortError") return;

            setExportError(
              requestError instanceof PortalApiError
                ? requestError
                : new PortalApiError(
                    "Export status could not be refreshed.",
                    "EXPORT_STATUS_FAILED",
                  ),
            );
          }
        }),
      );

      if (!controller.signal.aborted) timer = window.setTimeout(() => void poll(), delay);
    }

    timer = window.setTimeout(() => void poll(), delay);

    return () => {
      if (timer !== undefined) window.clearTimeout(timer);
      controller.abort();
    };
  }, [exports]);

  const updateDashboard = useCallback(async (
    params: URLSearchParams,
    historyMode: "push" | "replace" | "none" = "push",
  ): Promise<void> => {
    dashboardRequest.current?.abort();
    const controller = new AbortController();
    dashboardRequest.current = controller;
    setIsNavigating(true);
    setDashboardError(null);

    try {
      const next = await loadMarketDashboard(params, controller.signal);
      if (controller.signal.aborted) return;
      setModel(next);

      const suffix = params.toString();
      const target = suffix ? `/market?${suffix}` : "/market";
      if (historyMode === "push") {
        pageTrail.current.push(`${window.location.pathname}${window.location.search}`);
        window.history.pushState(null, "", target);
      } else if (historyMode === "replace") {
        window.history.replaceState(null, "", target);
      }
      announce("Market dashboard updated.");
    } catch (requestError) {
      if (requestError instanceof Error && requestError.name === "AbortError") return;
      setDashboardError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("The dashboard could not be updated. Please retry.", "DASHBOARD_UPDATE_FAILED"));
    } finally {
      if (dashboardRequest.current === controller) {
        dashboardRequest.current = null;
        setIsNavigating(false);
      }
    }
  }, [announce]);

  useEffect(() => () => dashboardRequest.current?.abort(), []);

  useEffect(() => {
    function restoreFromHistory(): void {
      if (window.location.pathname === "/market") {
        void updateDashboard(new URLSearchParams(window.location.search), "none");
      }
    }
    window.addEventListener("popstate", restoreFromHistory);
    return () => window.removeEventListener("popstate", restoreFromHistory);
  }, [updateDashboard]);

  function baseParams(): URLSearchParams {
    const params = new URLSearchParams();
    for (const key of marketFilterKeys) {
      const value = model.query[key];
      if (value) params.set(key, String(value));
    }

    params.set("sort", model.query.sort);
    params.set("limit", String(model.query.limit));
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
    void updateDashboard(params);
  }

  function resetFilters(): void {
    const params = new URLSearchParams({
      sort: model.query.sort,
      limit: String(model.query.limit),
    });
    void updateDashboard(params);
  }

  function sortBy(column: string): void {
    const params = baseParams();
    const [currentField, currentDirection] = model.query.sort.split(":");

    params.set("sort", `${column}:${currentField === column && currentDirection === "asc" ? "desc" : "asc"}`);
    params.delete("cursor");
    void updateDashboard(params);
  }

  function nextPage(): void {
    if (!model.page.nextCursor) return;

    const params = baseParams();
    params.set("cursor", model.page.nextCursor);
    void updateDashboard(params);
  }

  function previousPage(): void {
    const previous = pageTrail.current.pop();
    if (previous) {
      const url = new URL(previous, window.location.origin);
      void updateDashboard(url.searchParams, "replace");
      return;
    }

    const params = baseParams();
    params.delete("cursor");
    void updateDashboard(params, "replace");
  }

  async function startExport(format: ExportFormat): Promise<void> {
    setCreatingFormat(format);
    setExportError(null);

    try {
      const request = {
        format,
        selection: marketSelection(model),
        ...(format === "PDF"
          ? {
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
            }
          : {}),
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
      if (tracked) {
        dispatchExport({
          type: "upsert",
          value: { ...tracked, job: { ...tracked.job, status: "CANCELLED" } },
        });
      }

      announce("Export cancelled.");
    } catch (requestError) {
      setExportError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("The export could not be cancelled.", "EXPORT_CANCEL_FAILED"));
    }
  }

  return (
    <MarketDashboardView
      model={model}
      values={model.query}
      navigating={isNavigating}
      whatIf={whatIf}
      exports={exports.map((item) => item.job)}
      creatingFormat={creatingFormat}
      dashboardError={dashboardError}
      exportError={exportError}
      announcement={announcement}
      onApplyFilters={applyFilters}
      onResetFilters={resetFilters}
      onSort={sortBy}
      onNext={nextPage}
      onBack={previousPage}
      onExport={startExport}
      onCancelExport={cancel}
      onDismissExport={(id) => dispatchExport({ type: "remove", id })}
      onWhatIfResult={setWhatIf}
    />
  );
}
