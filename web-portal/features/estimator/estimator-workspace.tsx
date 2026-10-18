"use client";

/** Smart container for estimator mutations, history, and comparison selection. */
import { useCallback, useState } from "react";
import type { Estimate, PropertyInput } from "@/lib/api/types";
import type { EstimatorPageModel } from "@/features/estimator/server";
import {
  clearEstimateHistory,
  createEstimate,
  deleteEstimate,
  loadHistory,
  PortalApiError,
} from "@/features/estimator/api";
import { EstimatorWorkspaceView } from "@/features/estimator/estimator-workspace-view";
import { useLiveAnnouncement } from "@/hooks/use-live-announcement";

type PendingAction = "create" | "history" | "delete" | "clear" | null;

/** Owns interactive estimator state seeded by the Server Component page model. */
export function EstimatorWorkspace({ initialModel }: { initialModel: EstimatorPageModel }): React.ReactElement {
  const [history, setHistory] = useState(initialModel.history.items);
  const [nextCursor, setNextCursor] = useState(initialModel.history.next_cursor);
  const [current, setCurrent] = useState<Estimate | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [scenario, setScenario] = useState<PropertyInput | null>(null);
  const [pending, setPending] = useState<PendingAction>(null);
  const [error, setError] = useState<PortalApiError | null>(null);
  const [announcement, announce] = useLiveAnnouncement();

  const estimate = useCallback(async (input: PropertyInput, requestId: string) => {
    setPending("create");
    setError(null);

    try {
      const created = await createEstimate(input, requestId);
      setCurrent(created);
      setHistory((items) => [
        created,
        ...items.filter((item) => item.estimate_id !== created.estimate_id),
      ]);
      announce("Estimate created and added to history.");
      return created;
    } catch (requestError) {
      const apiError = requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("The estimate could not be created. Please retry.", "REQUEST_FAILED");
      setError(apiError);
      throw apiError;
    } finally {
      setPending(null);
    }
  }, [announce]);

  const loadMore = useCallback(async () => {
    if (!nextCursor || pending) return;

    setPending("history");
    setError(null);

    try {
      const page = await loadHistory(nextCursor);
      setHistory((items) => [
        ...items,
        ...page.items.filter(
          (item) => !items.some((existing) => existing.estimate_id === item.estimate_id),
        ),
      ]);
      setNextCursor(page.next_cursor);
      announce(`${page.items.length} more estimates loaded.`);
    } catch (requestError) {
      setError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("More history could not be loaded.", "REQUEST_FAILED"));
    } finally {
      setPending(null);
    }
  }, [announce, nextCursor, pending]);

  const remove = useCallback(async (estimateId: string) => {
    setPending("delete");
    setError(null);

    try {
      await deleteEstimate(estimateId);
      setHistory((items) => items.filter((item) => item.estimate_id !== estimateId));
      setSelectedIds((ids) => {
        const next = new Set(ids);
        next.delete(estimateId);
        return next;
      });
      setCurrent((value) => value?.estimate_id === estimateId ? null : value);
      announce("Estimate deleted.");
    } catch (requestError) {
      setError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("The estimate could not be deleted.", "REQUEST_FAILED"));
    } finally {
      setPending(null);
    }
  }, [announce]);

  const clear = useCallback(async () => {
    setPending("clear");
    setError(null);

    try {
      await clearEstimateHistory();
      setHistory([]);
      setNextCursor(null);
      setCurrent(null);
      setSelectedIds(new Set());
      announce("Estimate history cleared.");
    } catch (requestError) {
      setError(requestError instanceof PortalApiError
        ? requestError
        : new PortalApiError("History could not be cleared.", "REQUEST_FAILED"));
    } finally {
      setPending(null);
    }
  }, [announce]);

  const toggleSelected = useCallback((estimateId: string) => {
    setSelectedIds((ids) => {
      const next = new Set(ids);
      if (next.has(estimateId)) next.delete(estimateId);
      else if (next.size < 4) next.add(estimateId);
      return next;
    });
  }, []);

  const compared = history.filter((item) => selectedIds.has(item.estimate_id));

  return (
    <EstimatorWorkspaceView
      history={history}
      current={current}
      compared={compared}
      selectedIds={selectedIds}
      nextCursor={nextCursor}
      busy={pending !== null}
      warning={initialModel.warning}
      error={error}
      scenario={scenario}
      announcement={announcement}
      onEstimate={estimate}
      onToggle={toggleSelected}
      onView={(id) => setCurrent(history.find((item) => item.estimate_id === id) ?? null)}
      onReuse={(id) => setScenario(history.find((item) => item.estimate_id === id)?.inputs ?? null)}
      onDelete={remove}
      onClear={clear}
      onLoadMore={loadMore}
    />
  );
}
