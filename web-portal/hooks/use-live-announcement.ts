"use client";

/** Shared accessible announcement state for client-side actions. */
import { useCallback, useState } from "react";

/** Re-announces repeated text by clearing the live region for one animation frame. */
export function useLiveAnnouncement(): readonly [string, (message: string) => void] {
  const [message, setMessage] = useState("");
  const announce = useCallback((next: string) => {
    setMessage("");
    window.requestAnimationFrame(() => setMessage(next));
  }, []);

  return [message, announce] as const;
}
