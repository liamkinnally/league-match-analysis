"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { parseTimeline, type TimelineLookup } from "../../lib/development/timeline";
import { retryDate } from "../../lib/player-lookup/types";

export function DeferredTimeline({ matchId }: { matchId: string }) {
  const router = useRouter();
  const routerRef = useRef(router);
  const refreshed = useRef<string | null>(null);
  const [state, setState] = useState<TimelineLookup | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [now, setNow] = useState(0);
  useEffect(() => { routerRef.current = router; }, [router]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = Date.now() + 120_000;
    async function poll(method: "GET" | "POST") {
      try {
        const response = await fetch(`/api/matches/${matchId}/timeline`, {
          method, cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(12_000)]),
        });
        const body = await response.json().catch(() => null);
        if (controller.signal.aborted) return;
        if (!response.ok) {
          setState({ matchId, runId: null, status: "FAILED", message: null, retryNotBefore: retryDate(body?.retryNotBefore) });
          return;
        }
        const next = parseTimeline(body);
        if (next.matchId !== matchId) throw new Error("MISMATCHED_TIMELINE");
        setState(next);
        if (next.status === "NOT_REQUESTED") {
          if (method === "POST") throw new Error("TIMELINE_NOT_STARTED");
          await poll("POST");
        } else if (next.status === "RUNNING") {
          if (Date.now() >= deadline) throw new Error("TIMELINE_TIMEOUT");
          timer = setTimeout(() => void poll("GET"), 2000);
        } else if (next.status === "AVAILABLE" && refreshed.current !== matchId) {
          refreshed.current = matchId;
          routerRef.current.refresh();
        }
      } catch {
        if (!controller.signal.aborted) setState({ matchId, runId: null, status: "FAILED", message: null, retryNotBefore: null });
      }
    }
    void poll(attempt > 0 ? "POST" : "GET");
    return () => { controller.abort(); clearTimeout(timer); };
  }, [matchId, attempt]);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    tick(); const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, []);
  const failed = state?.status === "FAILED";
  const unavailable = state?.status === "UNAVAILABLE";
  const ready = state?.status === "AVAILABLE";
  const retryBlocked = state?.retryNotBefore && Date.parse(state.retryNotBefore) > now;
  return <div className="development-timeline-notice" role={failed ? "alert" : "status"}>
    <h3>{unavailable ? "Timeline unavailable" : failed ? "Timeline could not load" : ready ? "Timeline ready" : state?.status === "RUNNING" ? "Loading timeline…" : "Preparing timeline…"}</h3>
    <p>{unavailable ? "Riot has no timeline for this match. Final statistics remain available."
      : failed ? "Final statistics remain available. Retry to load the timeline."
        : ready ? "Updating this view with recorded timeline samples."
          : "Final statistics are ready below while the recorded timeline loads."}</p>
    {failed && <button type="button" disabled={Boolean(retryBlocked)} onClick={() => { setState(null); setAttempt(value => value + 1); }}>Retry timeline</button>}
    {retryBlocked && <p>Retry after <time dateTime={state.retryNotBefore!}>{new Date(state.retryNotBefore!).toLocaleTimeString()}</time>.</p>}
  </div>;
}
