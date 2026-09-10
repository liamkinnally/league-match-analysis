"use client";

import { useEffect, useRef } from "react";

export function ReviewBeatRestoration({ beatId, enabled }: { beatId: string; enabled: boolean }) {
  const restored = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || restored.current === beatId) return;
    const target = document.getElementById(beatId);
    if (!target) return;
    target.scrollIntoView({ behavior: "instant", block: "start" });
    restored.current = beatId;
  }, [beatId, enabled]);

  return null;
}
