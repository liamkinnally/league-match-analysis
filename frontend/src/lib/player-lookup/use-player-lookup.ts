"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";
import { parseLookup, retryDate, runIdPattern, type PlayerLookup } from "./types";

const unavailable = "Live lookup is unavailable. Explore the sample match.";
const requestTimeoutMs = 12_000;
type LookupIssue = { message: string; retryNotBefore?: string | null; retryable?: boolean };

class LookupRequestError extends Error {
  constructor(readonly issue: LookupIssue) { super(issue.message); }
}

async function requestLookup(url: string, signal: AbortSignal, input?: { gameName: string; tagLine: string }) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) controller.abort();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, requestTimeoutMs);
  try {
    const response = await fetch(url, {
      cache: "no-store", signal: controller.signal,
      ...(input ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) } : {}),
    });
    const body: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const retry = body && typeof body === "object" && "retryNotBefore" in body ? retryDate(body.retryNotBefore) : null;
      throw new LookupRequestError({
        message: response.status === 404 ? "Lookup not found. Search again."
          : response.status === 400 ? "Enter a Riot game name and tag line."
          : response.status === 429 ? "Lookup is busy or cooling down. Try again after the indicated time." : unavailable,
        retryNotBefore: retry, retryable: !input && response.status !== 404,
      });
    }
    return parseLookup(body);
  } catch (error) {
    if (timedOut) throw new LookupRequestError({ message: input
      ? "The request is taking longer than expected. Try searching this Riot ID again."
      : "Match history is taking longer than expected. Retry loading to check this lookup.", retryable: !input });
    throw error;
  } finally {
    clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}

export function usePlayerLookup(initialRunId?: string) {
  const router = useRouter();
  const [lookup, setLookup] = useState<PlayerLookup | null>(null);
  const [readIssue, setReadIssue] = useState<LookupIssue | null>(null);
  const [submitIssue, setSubmitIssue] = useState<LookupIssue | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [navigating, startNavigation] = useTransition();
  const [attempt, setAttempt] = useState(0);
  const submitController = useRef<AbortController | null>(null);
  const validRun = Boolean(initialRunId && runIdPattern.test(initialRunId));

  useEffect(() => () => submitController.current?.abort(), []);
  useEffect(() => {
    if (!validRun || submitting) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function poll() {
      try {
        const result = await requestLookup(`/api/player-matches/${initialRunId}`, controller.signal);
        if (controller.signal.aborted) return;
        if (result.runId.toLowerCase() !== initialRunId?.toLowerCase()) throw new Error("LOOKUP_RUN_MISMATCH");
        setLookup(result);
        setReadIssue(null);
        if (result.status === "RUNNING") timer = setTimeout(poll, 2000);
      } catch (error) {
        if (!controller.signal.aborted) setReadIssue(error instanceof LookupRequestError ? error.issue : { message: unavailable, retryable: true });
      }
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [initialRunId, validRun, attempt, submitting]);

  async function submit(input: { gameName: string; tagLine: string }) {
    if (submitController.current || navigating) return;
    setSubmitting(true);
    setSubmitIssue(null);
    const controller = new AbortController();
    submitController.current = controller;
    try {
      const result = await requestLookup("/api/player-matches", controller.signal, input);
      if (!controller.signal.aborted) startNavigation(() => router.push(`/search?runId=${result.runId}`));
    } catch (error) {
      if (!controller.signal.aborted) setSubmitIssue(error instanceof LookupRequestError ? error.issue : { message: unavailable });
    } finally {
      if (!controller.signal.aborted) setSubmitting(false);
      if (submitController.current === controller) submitController.current = null;
    }
  }

  const issue = submitIssue ?? readIssue ?? (initialRunId && !validRun ? { message: "Lookup not found. Search again." } : null);
  return {
    lookup, issue: submitting || navigating ? null : issue, submitting: submitting || navigating,
    loading: validRun && !lookup && !issue,
    retryNotBefore: issue?.retryNotBefore ?? lookup?.retryNotBefore,
    retry: () => { setReadIssue(null); setAttempt(value => value + 1); },
    submit,
  };
}
