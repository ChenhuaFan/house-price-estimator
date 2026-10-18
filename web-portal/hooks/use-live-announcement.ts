"use client";

import { useCallback, useState } from "react";

export function useLiveAnnouncement(): readonly [string, (message: string) => void] {
  const [message, setMessage] = useState("");
  const announce = useCallback((next: string) => {
    setMessage("");
    window.requestAnimationFrame(() => setMessage(next));
  }, []);
  return [message, announce] as const;
}
