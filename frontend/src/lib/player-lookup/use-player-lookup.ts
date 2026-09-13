"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { parseLookup, retryDate, runIdPattern, type PlayerLookup } from "./types";

const unavailable = "Live lookup is unavailable. Explore the sample match.";
type Input = { gameName: string; tagLine: string; queueId: number };
type LookupIssue = { message: string; retryNotBefore?: string | null; retryable?: boolean };
type Command = { kind: "search" | "filter" | "restore" | "older" | "refresh"; runId?: string; input?: Input };
class LookupRequestError extends Error {
  constructor(readonly issue: LookupIssue) { super(issue.message); }
}
async function requestLookup(url: string, signal: AbortSignal, method: "GET" | "POST" = "GET", input?: Input) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) controller.abort();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 12_000);
  try {
    const response = await fetch(url, {
      cache: "no-store", signal: controller.signal, method,
      ...(input ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) } : {}),
    });
    const body: unknown = await response.json().catch(() => null);
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (!response.ok) {
      const retry = body && typeof body === "object" && "retryNotBefore" in body ? retryDate(body.retryNotBefore) : null;
      throw new LookupRequestError({
        message: response.status === 404 ? "Lookup not found. Search again."
          : response.status === 400 ? "Enter a Riot game name, tag line, and supported queue."
          : response.status === 429 ? "Lookup is busy or cooling down. Try again after the indicated time." : unavailable,
        retryNotBefore: retry, retryable: response.status !== 404,
      });
    }
    return parseLookup(body);
  } catch (error) {
    if (timedOut) throw new LookupRequestError({ message: "Match history is taking longer than expected. Retry loading to check this lookup.", retryable: true });
    throw error;
  } finally {
    clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}
const sameId = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();
function assertHistory(a: PlayerLookup, b: PlayerLookup) {
  if (a.queueId !== b.queueId || (a.gameName && b.gameName &&
      (a.gameName.toLowerCase() !== b.gameName.toLowerCase() || a.tagLine.toLowerCase() !== b.tagLine.toLowerCase())))
    throw new Error("HISTORY_MISMATCH");
}
export function mergeHistory(pages: PlayerLookup[]): PlayerLookup | null {
  if (!pages.length) return null;
  const seen = new Set<string>();
  return { ...pages.at(-1)!, lastUpdated: pages[0].lastUpdated, nextRefreshAt: pages[0].nextRefreshAt,
    matches: pages.flatMap(page => page.matches).filter(match => {
      if (seen.has(match.matchId)) return false;
      seen.add(match.matchId); return true;
    }) };
}
function pause(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, 2000);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });
}

export function usePlayerLookup(initialRunId?: string) {
  const router = useRouter();
  const [pages, setPages] = useState<PlayerLookup[]>([]);
  const pagesRef = useRef<PlayerLookup[]>([]);
  const [issue, setIssue] = useState<LookupIssue | null>(null);
  const [busy, setBusy] = useState<Command["kind"] | null>(null);
  const [restoreCursor, setRestoreCursor] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const retryCommand = useRef<{ command: Command; continueRestore: boolean } | null>(null);
  const ownNavigation = useRef<string | null>(null);
  const currentRunId = useRef(initialRunId);
  const routerRef = useRef(router);
  useEffect(() => { routerRef.current = router; }, [router]);
  const execute = useCallback(async (command: Command, continueRestore = false) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const { signal } = controller;
    retryCommand.current = { command, continueRestore };
    setIssue(null); setBusy(command.kind);
    const publish = (next: PlayerLookup[]) => {
      if (signal.aborted) return;
      pagesRef.current = next; setPages(next);
    };
    const navigate = (runId: string) => {
      if (currentRunId.current && sameId(currentRunId.current, runId)) {
        ownNavigation.current = null;
        return;
      }
      ownNavigation.current = runId;
      routerRef.current.push(`/search?runId=${runId}`);
    };
    const read = async (runId: string) => {
      const result = await requestLookup(`/api/player-matches/${runId}`, signal);
      if (!sameId(result.runId, runId)) throw new Error("LOOKUP_RUN_MISMATCH");
      return result;
    };
    try {
      if (command.kind === "restore") {
        let chain = continueRestore ? [...pagesRef.current] : [];
        const visited = new Set(chain.map(page => page.runId.toLowerCase()));
        let cursor: string | null = command.runId!;
        // Restore only server-linked pages, in bounded batches. Longer chains require an explicit continuation.
        for (let i = 0; cursor && i < 20; i++) {
          if (visited.has(cursor.toLowerCase())) throw new Error("HISTORY_CYCLE");
          visited.add(cursor.toLowerCase());
          let result = await read(cursor);
          if (chain.length) assertHistory(chain[0], result);
          if (!chain.length) {
            publish([result]);
            while (result.status === "RUNNING") {
              await pause(signal); const next = await read(cursor); assertHistory(result, next); result = next; publish([result]);
            }
          } else if (result.status === "RUNNING") throw new Error("INCOMPLETE_PARENT");
          chain = [result, ...chain];
          cursor = result.previousRunId;
        }
        publish(chain); setRestoreCursor(cursor);
      } else {
        const base = [...pagesRef.current];
        const parent = base.at(-1);
        const searching = command.kind === "search" || command.kind === "filter";
        const path = searching ? "" : `/${command.runId}/${command.kind}`;
        let result = await requestLookup(`/api/player-matches${path}`, signal, "POST", command.input);
        if (searching && result.queueId !== command.input!.queueId) throw new Error("QUEUE_MISMATCH");
        if (!searching && parent) assertHistory(parent, result);
        const validateOlder = (page: PlayerLookup) => {
          if (command.kind !== "older") return;
          if (!sameId(page.runId, command.runId!) && (!page.previousRunId || !sameId(page.previousRunId, command.runId!)
            || base.some(p => sameId(p.runId, page.runId)))) throw new Error("HISTORY_LINK_MISMATCH");
        };
        validateOlder(result);
        const adopt = (page: PlayerLookup) => {
          validateOlder(page);
          if (command.kind === "older") {
            publish(sameId(page.runId, command.runId!) ? [...base.slice(0, -1), page] : [...base, page]);
          } else publish([page]);
        };
        // Keep existing results during a new lookup or refresh. A new, empty view can expose completed rows immediately.
        let navigated = false;
        if (!base.length) { adopt(result); navigate(result.runId); navigated = true; }
        while (result.status === "RUNNING") {
          const run = result.runId;
          await pause(signal); const next = await read(run); assertHistory(result, next); result = next;
          if (!base.length) adopt(result);
        }
        if (result.status === "FAILED" && base.length) throw new LookupRequestError({ message: result.message ?? "Lookup could not finish. Retry loading to try again.", retryable: true, retryNotBefore: result.retryNotBefore });
        // Cached search may point to an older page; the search endpoint normally returns the newest page.
        if (command.kind !== "older" && result.previousRunId) throw new Error("UNEXPECTED_HISTORY_PARENT");
        adopt(result); setRestoreCursor(null);
        if (!navigated) navigate(result.runId);
      }
    } catch (error) {
      if (!signal.aborted) setIssue(error instanceof LookupRequestError ? error.issue : { message: unavailable, retryable: true });
    } finally {
      if (!signal.aborted) { setBusy(null); controllerRef.current = null; }
    }
  }, []);

  useEffect(() => {
    const expectedNavigation = ownNavigation.current;
    ownNavigation.current = null;
    currentRunId.current = initialRunId;
    if (initialRunId && expectedNavigation && sameId(expectedNavigation, initialRunId)) return;
    if (!initialRunId || !runIdPattern.test(initialRunId)) return;
    const previousController = controllerRef.current;
    let cancelled = false;
    let controller: AbortController | null = null;
    void Promise.resolve().then(() => {
      if (cancelled || controllerRef.current !== previousController) return;
      void execute({ kind: "restore", runId: initialRunId });
      controller = controllerRef.current;
    });
    return () => { cancelled = true; controller?.abort(); };
  }, [initialRunId, execute]);
  useEffect(() => () => controllerRef.current?.abort(), []);
  const lookup = mergeHistory(pages);
  const visibleIssue = issue ?? (initialRunId && !runIdPattern.test(initialRunId) ? { message: "Lookup not found. Search again." } : null);
  return {
    lookup, issue: visibleIssue, submitting: busy === "search", loading: busy === "restore" || (!!initialRunId && !lookup && !visibleIssue),
    busy, restoreCursor, retryNotBefore: visibleIssue?.retryNotBefore ?? lookup?.retryNotBefore,
    retry: () => { if (retryCommand.current) void execute(retryCommand.current.command, retryCommand.current.continueRestore); },
    submit: (input: Input) => execute({ kind: "search", input }),
    filter: (queueId: number) => { if (lookup && !busy && queueId !== lookup.queueId) void execute({ kind: "filter", input: { gameName: lookup.gameName, tagLine: lookup.tagLine, queueId } }); },
    older: () => { if (lookup && !busy) void execute({ kind: "older", runId: lookup.runId }); },
    refresh: () => { if (lookup && !busy) void execute({ kind: "refresh", runId: lookup.runId }); },
    restoreMore: () => { if (restoreCursor && !busy) void execute({ kind: "restore", runId: restoreCursor }, true); },
  };
}
