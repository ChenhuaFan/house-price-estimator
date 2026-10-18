import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PortalApiError } from "@/features/estimator/api";
import { useEstimator } from "@/features/estimator/use-estimator";
import { estimate, propertyInput } from "@/tests/fixtures";

const api = vi.hoisted(() => ({
  createEstimate: vi.fn(),
  loadHistory: vi.fn(),
  deleteEstimate: vi.fn(),
  clearEstimateHistory: vi.fn(),
}));

vi.mock("@/features/estimator/api", async (original) => {
  const actual = await original<typeof import("@/features/estimator/api")>();
  return { ...actual, ...api };
});

describe("useEstimator state boundary", () => {
  beforeEach(() => {
    for (const mock of Object.values(api)) mock.mockReset();
  });

  it("adds successful estimates newest first without duplicates", async () => {
    const existing = estimate();
    const updated = { ...existing, predicted_price: "240000.00" };
    api.createEstimate.mockResolvedValue(updated);
    const { result } = renderHook(() => useEstimator({ items: [existing], next_cursor: null }));

    await act(async () => {
      await result.current.estimate(propertyInput, existing.request_id);
    });

    expect(result.current.state.pending).toBeNull();
    expect(result.current.state.current).toEqual(updated);
    expect(result.current.state.history).toEqual([updated]);
    expect(result.current.state.error).toBeNull();
  });

  it("normalizes unexpected creation failures and rethrows an actionable error", async () => {
    api.createEstimate.mockRejectedValue(new Error("internal detail"));
    const { result } = renderHook(() => useEstimator({ items: [], next_cursor: null }));
    let caught: unknown;

    await act(async () => {
      try {
        await result.current.estimate(propertyInput, crypto.randomUUID());
      } catch (error) {
        caught = error;
      }
    });

    expect(caught).toMatchObject({ code: "REQUEST_FAILED" });
    expect(result.current.state.error?.message).toContain("could not be created");
    expect(result.current.state.pending).toBeNull();
  });

  it("retains structured creation errors", async () => {
    const failure = new PortalApiError("Invalid property.", "VALIDATION_ERROR");
    api.createEstimate.mockRejectedValue(failure);
    const { result } = renderHook(() => useEstimator({ items: [], next_cursor: null }));

    await act(async () => {
      await expect(result.current.estimate(propertyInput, crypto.randomUUID())).rejects.toBe(failure);
    });
    expect(result.current.state.error).toBe(failure);
  });

  it("loads unique history pages and updates the cursor", async () => {
    const first = estimate();
    const second = estimate("33333333-3333-4333-8333-333333333333");
    api.loadHistory.mockResolvedValue({ items: [first, second], next_cursor: "later" });
    const { result } = renderHook(() => useEstimator({ items: [first], next_cursor: "next" }));

    await act(async () => result.current.loadMore());

    expect(api.loadHistory).toHaveBeenCalledWith("next");
    expect(result.current.state.history).toEqual([first, second]);
    expect(result.current.state.nextCursor).toBe("later");
  });

  it("ignores load-more without a cursor and reports history failures", async () => {
    const { result, rerender } = renderHook(
      ({ cursor }) => useEstimator({ items: [], next_cursor: cursor }),
      { initialProps: { cursor: null as string | null } },
    );
    await act(async () => result.current.loadMore());
    expect(api.loadHistory).not.toHaveBeenCalled();

    rerender({ cursor: "next" });
    // Hook state is intentionally initialized once; a new mount represents a server-page refresh.
    const mounted = renderHook(() => useEstimator({ items: [], next_cursor: "next" }));
    api.loadHistory.mockRejectedValue(new Error("network"));
    await act(async () => mounted.result.current.loadMore());
    expect(mounted.result.current.state.error?.message).toBe("More history could not be loaded.");
  });

  it("removes a record from history, current result, and comparison selection", async () => {
    const record = estimate();
    api.createEstimate.mockResolvedValue(record);
    api.deleteEstimate.mockResolvedValue(undefined);
    const { result } = renderHook(() => useEstimator({ items: [record], next_cursor: null }));
    await act(async () => result.current.estimate(record.inputs, record.request_id));
    act(() => result.current.toggleSelected(record.estimate_id));

    await act(async () => result.current.remove(record.estimate_id));

    expect(result.current.state.history).toEqual([]);
    expect(result.current.state.current).toBeNull();
    expect(result.current.state.selectedIds).not.toContain(record.estimate_id);
  });

  it("reopens a persisted history record as the current result", () => {
    const record = estimate();
    const { result } = renderHook(() => useEstimator({ items: [record], next_cursor: null }));

    act(() => result.current.view(record.estimate_id));

    expect(result.current.state.current).toBe(record);
    act(() => result.current.view("missing"));
    expect(result.current.state.current).toBe(record);
  });

  it("reports deletion errors without dropping local history", async () => {
    const record = estimate();
    api.deleteEstimate.mockRejectedValue(new PortalApiError("Delete failed.", "DELETE_FAILED"));
    const { result } = renderHook(() => useEstimator({ items: [record], next_cursor: null }));

    await act(async () => result.current.remove(record.estimate_id));

    expect(result.current.state.history).toEqual([record]);
    expect(result.current.state.error?.message).toBe("Delete failed.");
  });

  it("clears all local state after the durable history is cleared", async () => {
    const record = estimate();
    api.clearEstimateHistory.mockResolvedValue(undefined);
    const { result } = renderHook(() => useEstimator({ items: [record], next_cursor: "next" }));
    act(() => result.current.toggleSelected(record.estimate_id));

    await act(async () => result.current.clear());

    expect(result.current.state.history).toEqual([]);
    expect(result.current.state.nextCursor).toBeNull();
    expect(result.current.state.selectedIds.size).toBe(0);
  });

  it("reports clear failures and limits comparison to four records", async () => {
    api.clearEstimateHistory.mockRejectedValue(new Error("network"));
    const { result } = renderHook(() => useEstimator({ items: [], next_cursor: null }));
    act(() => {
      for (const id of ["1", "2", "3", "4", "5"]) result.current.toggleSelected(id);
    });
    expect([...result.current.state.selectedIds]).toEqual(["1", "2", "3", "4"]);
    act(() => result.current.toggleSelected("2"));
    expect(result.current.state.selectedIds).not.toContain("2");

    await act(async () => result.current.clear());
    expect(result.current.state.error?.message).toBe("History could not be cleared.");
  });
});
