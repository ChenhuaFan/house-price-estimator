import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useLiveAnnouncement } from "@/hooks/use-live-announcement";

describe("useLiveAnnouncement", () => {
  it("publishes repeated accessible messages on a new animation frame", () => {
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => { callback(0); return 1; });
    const { result } = renderHook(() => useLiveAnnouncement());
    act(() => result.current[1]("Dashboard updated."));
    expect(result.current[0]).toBe("Dashboard updated.");
    act(() => result.current[1]("Dashboard updated."));
    expect(result.current[0]).toBe("Dashboard updated.");
  });
});
