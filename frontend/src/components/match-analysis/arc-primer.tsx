"use client";

import { useState, useSyncExternalStore } from "react";

const ARC_PRIMER_KEY = "league-analysis:arc-primer:v1";
const ARC_PRIMER_EVENT = `${ARC_PRIMER_KEY}:changed`;

function storedDismissal(): boolean {
  try {
    return localStorage.getItem(ARC_PRIMER_KEY) === "1";
  } catch {
    return false;
  }
}

function serverDismissal(): boolean {
  return false;
}

function subscribeToDismissal(onChange: () => void): () => void {
  const onStorage = (event: StorageEvent) => {
    if (event.key === ARC_PRIMER_KEY) onChange();
  };
  window.addEventListener("storage", onStorage);
  window.addEventListener(ARC_PRIMER_EVENT, onChange);
  return () => {
    window.removeEventListener("storage", onStorage);
    window.removeEventListener(ARC_PRIMER_EVENT, onChange);
  };
}

export function ArcPrimer() {
  const stored = useSyncExternalStore(
    subscribeToDismissal,
    storedDismissal,
    serverDismissal,
  );
  const [dismissedForRender, setDismissedForRender] = useState(false);

  if (stored || dismissedForRender) return null;

  return (
    <aside className="arc-primer" aria-label="Match Arc marker explanation">
      <span>An evidence-backed transition, not a score</span>
      <button
        type="button"
        onClick={() => {
          try {
            localStorage.setItem(ARC_PRIMER_KEY, "1");
          } catch {
            // Dismissal still applies to this render when storage is unavailable.
          }
          setDismissedForRender(true);
          window.dispatchEvent(new Event(ARC_PRIMER_EVENT));
        }}
      >
        Got it
      </button>
    </aside>
  );
}
