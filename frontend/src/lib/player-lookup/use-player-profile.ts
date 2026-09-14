"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { parsePlayerProfile, profileNeedsPolling, sameProfileIdentity, type PlayerProfile, type ProfileIdentity } from "./profile";
import { retryDate } from "./types";

type Subject = ProfileIdentity & { runId: string; updatedAt: string | null; historyStatus: string };
type Issue = { message: string; retryNotBefore: string | null };
class ProfileRequestError extends Error { constructor(readonly issue: Issue) { super(issue.message); } }
async function requestProfile(url: string, signal: AbortSignal, method = "GET"): Promise<PlayerProfile> {
  const controller = new AbortController(), abort = () => controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) controller.abort();
  const timer = setTimeout(abort, 12_000);
  try {
    const response = await fetch(url, { signal: controller.signal, cache: "no-store", method });
    const body: unknown = await response.json().catch(() => null);
    if (!response.ok) throw new ProfileRequestError({ message: response.status === 429 ? "Recent Solo/Duo collection is cooling down." : response.status === 404 ? "Profile details are not available for this lookup yet." : "Profile details could not be loaded. Previously loaded values may be out of date.", retryNotBefore: body && typeof body === "object" && "retryNotBefore" in body ? retryDate(body.retryNotBefore) : null });
    return parsePlayerProfile(body);
  } finally { clearTimeout(timer); signal.removeEventListener("abort", abort); }
}
function pause(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, 2000);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });
}
export function usePlayerProfile(subject: Subject | null) {
  const identityKey = subject ? JSON.stringify([subject.gameName.toLowerCase(), subject.tagLine.toLowerCase()]) : "";
  const runId = subject?.runId, updatedAt = subject?.updatedAt, historyStatus = subject?.historyStatus;
  const [state, setState] = useState<{ key: string; profile: PlayerProfile | null; issue: Issue | null }>({ key: "", profile: null, issue: null });
  const [pending, setPending] = useState<"load" | "recent" | "older" | null>(null);
  const [revision, setRevision] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  const profile = state.key === identityKey ? state.profile : null;
  const issue = state.key === identityKey ? state.issue : null;
  const execute = useCallback(async (kind: "load" | "recent" | "older", cursor?: string | null) => {
    if (!runId || !identityKey) return;
    controllerRef.current?.abort();
    const controller = new AbortController(); controllerRef.current = controller;
    const { signal } = controller;
    setPending(kind);
    const [gameName, tagLine] = JSON.parse(identityKey) as [string, string];
    const expected = { gameName, tagLine };
    const accept = (next: PlayerProfile, append = false) => {
      if (!sameProfileIdentity(next.identity, expected)) throw new Error("PROFILE_IDENTITY_MISMATCH");
      if (signal.aborted) return;
      setState(previous => {
        if (append && previous.key === identityKey && previous.profile) {
          const rows = [...previous.profile.rankHistory.observations, ...next.rankHistory.observations];
          const ids = new Set<string>();
          return { key: identityKey, issue: null, profile: { ...previous.profile, rankHistory: { ...next.rankHistory, observations: rows.filter(row => !ids.has(row.id) && !!ids.add(row.id)) } } };
        }
        return { key: identityKey, profile: next, issue: null };
      });
    };
    try {
      let next = await requestProfile(`/api/player-matches/${runId}/${kind === "recent" ? "recent-record" : "profile"}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`, signal, kind === "recent" ? "POST" : "GET");
      accept(next, kind === "older");
      if (kind !== "older") {
        const deadline = Date.now() + 15 * 60_000;
        for (let attempt = 0; profileNeedsPolling(next) && attempt < 450 && Date.now() < deadline; attempt++) {
          await pause(signal);
          next = await requestProfile(`/api/player-matches/${runId}/profile`, signal);
          accept(next);
        }
        if (profileNeedsPolling(next) && !signal.aborted) throw new Error("PROFILE_POLL_LIMIT");
      }
    } catch (error) {
      if (!signal.aborted) setState(previous => ({ key: identityKey, profile: previous.key === identityKey ? previous.profile : null, issue: error instanceof ProfileRequestError ? error.issue : { message: "Profile details could not be loaded. Previously loaded values may be out of date.", retryNotBefore: null } }));
    } finally { if (!signal.aborted) { setPending(null); controllerRef.current = null; } }
  }, [runId, identityKey]);
  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => { if (!cancelled) void execute("load"); });
    return () => { cancelled = true; controllerRef.current?.abort(); };
  }, [execute, updatedAt, historyStatus, revision]);
  return { profile, issue, loading: pending === "load", loadingRecent: pending === "recent", loadingOlder: pending === "older", busy: pending !== null,
    reload: () => setRevision(value => value + 1),
    loadRecent: () => { if (!pending && !controllerRef.current && profile?.recentSolo.canLoad && (!issue?.retryNotBefore || Date.parse(issue.retryNotBefore) <= Date.now()) && (!profile.recentSolo.retryNotBefore || Date.parse(profile.recentSolo.retryNotBefore) <= Date.now())) void execute("recent"); },
    loadOlder: () => { if (!pending && !controllerRef.current && profile?.rankHistory.nextCursor) void execute("older", profile.rankHistory.nextCursor); },
  };
}
