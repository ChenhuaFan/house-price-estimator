"use client";

import { useCallback, useState } from "react";
import type { Estimate, HistoryPage, PropertyInput } from "@/lib/api/types";
import {
  clearEstimateHistory,
  createEstimate,
  deleteEstimate,
  loadHistory,
  PortalApiError,
} from "@/features/estimator/api";

interface EstimatorState {
  history: Estimate[];
  nextCursor: string | null;
  current: Estimate | null;
  selectedIds: Set<string>;
  pending: "create" | "history" | "delete" | "clear" | null;
  error: PortalApiError | null;
}

export function useEstimator(initial: HistoryPage) {
  const [state, setState] = useState<EstimatorState>({
    history: initial.items,
    nextCursor: initial.next_cursor,
    current: null,
    selectedIds: new Set(),
    pending: null,
    error: null,
  });

  const estimate = useCallback(async (input: PropertyInput, requestId: string) => {
    setState((value) => ({ ...value, pending: "create", error: null }));
    try {
      const created = await createEstimate(input, requestId);
      setState((value) => ({
        ...value,
        pending: null,
        current: created,
        history: [created, ...value.history.filter((item) => item.estimate_id !== created.estimate_id)],
      }));
      return created;
    } catch (error) {
      const apiError = error instanceof PortalApiError
        ? error
        : new PortalApiError("The estimate could not be created. Please retry.", "REQUEST_FAILED");
      setState((value) => ({ ...value, pending: null, error: apiError }));
      throw apiError;
    }
  }, []);

  const loadMore = useCallback(async () => {
    if (!state.nextCursor || state.pending) return;
    setState((value) => ({ ...value, pending: "history", error: null }));
    try {
      const page = await loadHistory(state.nextCursor);
      setState((value) => ({
        ...value,
        pending: null,
        history: [...value.history, ...page.items.filter(
          (item) => !value.history.some((existing) => existing.estimate_id === item.estimate_id),
        )],
        nextCursor: page.next_cursor,
      }));
    } catch (error) {
      const apiError = error instanceof PortalApiError
        ? error
        : new PortalApiError("More history could not be loaded.", "REQUEST_FAILED");
      setState((value) => ({ ...value, pending: null, error: apiError }));
    }
  }, [state.nextCursor, state.pending]);

  const remove = useCallback(async (estimateId: string) => {
    setState((value) => ({ ...value, pending: "delete", error: null }));
    try {
      await deleteEstimate(estimateId);
      setState((value) => {
        const selected = new Set(value.selectedIds);
        selected.delete(estimateId);
        return {
          ...value,
          pending: null,
          history: value.history.filter((item) => item.estimate_id !== estimateId),
          current: value.current?.estimate_id === estimateId ? null : value.current,
          selectedIds: selected,
        };
      });
    } catch (error) {
      const apiError = error instanceof PortalApiError
        ? error
        : new PortalApiError("The estimate could not be deleted.", "REQUEST_FAILED");
      setState((value) => ({ ...value, pending: null, error: apiError }));
    }
  }, []);

  const clear = useCallback(async () => {
    setState((value) => ({ ...value, pending: "clear", error: null }));
    try {
      await clearEstimateHistory();
      setState((value) => ({
        ...value,
        pending: null,
        history: [],
        nextCursor: null,
        current: null,
        selectedIds: new Set(),
      }));
    } catch (error) {
      const apiError = error instanceof PortalApiError
        ? error
        : new PortalApiError("History could not be cleared.", "REQUEST_FAILED");
      setState((value) => ({ ...value, pending: null, error: apiError }));
    }
  }, []);

  const toggleSelected = useCallback((estimateId: string) => {
    setState((value) => {
      const selected = new Set(value.selectedIds);
      if (selected.has(estimateId)) selected.delete(estimateId);
      else if (selected.size < 4) selected.add(estimateId);
      return { ...value, selectedIds: selected };
    });
  }, []);

  return { state, estimate, loadMore, remove, clear, toggleSelected };
}
