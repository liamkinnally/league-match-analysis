import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { usePlayerLookup } from "./use-player-lookup";
import type { PlayerLookup } from "./types";
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
const id = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
const match = (n: number) => ({ matchId: `NA1_${n}`, queueId: 420, participantId: 6, championName: "Garen", championId: 86,
  gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, remake: false, startedAtMs: n, durationSeconds: 1800,
  kills: 1, deaths: 2, assists: 3, cs: 100, gold: 10000, timelineAvailable: false });
const page = (n: number, ids: number[], extra: Partial<PlayerLookup> = {}): PlayerLookup => ({ platform: "NA1", runId: id(n), gameName: "Invented", tagLine: "NA1", queueId: 420,
  status: "COMPLETE", message: null, retryNotBefore: null, lastUpdated: "2026-09-12T00:00:00Z", nextRefreshAt: null,
  previousRunId: null, hasMore: true, matches: ids.map(id => ({ ...match(id), queueId: extra.queueId ?? 420 })), ...extra });
afterEach(() => { vi.useRealTimers(); push.mockClear(); });
it("appends and deduplicates older results, retries against the original parent, and saves the final handle", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(page(1, [1, 2])))
    .mockResolvedValueOnce(Response.json({}, { status: 503 }))
    .mockResolvedValueOnce(Response.json(page(2, [2, 3], { previousRunId: id(1) })));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(id(1)));
  await act(async () => {});
  await act(async () => hook.result.current.older());
  expect(hook.result.current.lookup?.matches.map(m => m.matchId)).toEqual(["NA1_1", "NA1_2"]);
  expect(hook.result.current.issue).not.toBeNull();
  await act(async () => hook.result.current.retry());
  expect(fetcher.mock.calls.slice(1).map(([url]) => url)).toEqual([`/api/player-matches/${id(1)}/older`, `/api/player-matches/${id(1)}/older`]);
  expect(hook.result.current.lookup?.matches.map(m => m.matchId)).toEqual(["NA1_1", "NA1_2", "NA1_3"]);
  expect(push).toHaveBeenCalledWith(`/summoners/na/Invented-NA1?runId=${id(2)}`);
});
it("restores linked pages in chronological page order without requesting provider pagination", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(page(3, [3], { previousRunId: id(2), hasMore: false })))
    .mockResolvedValueOnce(Response.json(page(2, [2], { previousRunId: id(1) })))
    .mockResolvedValueOnce(Response.json(page(1, [1])));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(id(3)));
  await act(async () => {});
  expect(hook.result.current.lookup?.matches.map(m => m.matchId)).toEqual(["NA1_1", "NA1_2", "NA1_3"]);
  expect(hook.result.current.lookup?.runId).toBe(id(3));
  expect(hook.result.current.lookup?.hasMore).toBe(false);
  expect(fetcher.mock.calls.every(([, options]) => options.method === "GET")).toBe(true);
});
it.each(["cycle", "queue", "identity"])("rejects a %s mismatch while restoring", async (kind) => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(page(2, [2], { previousRunId: id(1) })))
    .mockResolvedValueOnce(Response.json(page(1, [1], kind === "cycle" ? { previousRunId: id(2) } : kind === "queue" ? { queueId: 480 } : { gameName: "Other" })));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(id(2)));
  await act(async () => {});
  expect(hook.result.current.issue).not.toBeNull();
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(hook.result.current.lookup?.matches.some(m => m.matchId === "NA1_1")).toBe(false);
});
it("bounds restoration and resumes the remaining chain only after an explicit request", async () => {
  const fetcher = vi.fn().mockImplementation(async (url: string) => {
    const n = Number(url.slice(-12));
    return Response.json(page(n, [n], { previousRunId: n > 1 ? id(n - 1) : null }));
  });
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(id(21)));
  await act(async () => {});
  expect(fetcher).toHaveBeenCalledTimes(20);
  expect(hook.result.current.restoreCursor).toBe(id(1));
  await act(async () => hook.result.current.restoreMore());
  expect(fetcher).toHaveBeenCalledTimes(21);
  expect(hook.result.current.lookup?.matches).toHaveLength(21);
  expect(hook.result.current.lookup?.matches[0].matchId).toBe("NA1_1");
});
it("retains rows during refresh and replaces the history only after completion", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(page(1, [1])))
    .mockResolvedValueOnce(Response.json(page(2, [], { status: "RUNNING" })))
    .mockResolvedValueOnce(Response.json(page(2, [2], { queueId: 420 })));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(id(1)));
  await act(async () => {});
  act(() => hook.result.current.refresh());
  await act(async () => {});
  expect(hook.result.current.lookup?.matches[0].matchId).toBe("NA1_1");
  await act(async () => vi.advanceTimersByTime(2000));
  expect(hook.result.current.lookup?.matches.map(m => m.matchId)).toEqual(["NA1_2"]);
  expect(hook.result.current.busy).toBeNull();
  expect(push).toHaveBeenLastCalledWith(`/summoners/na/Invented-NA1?runId=${id(2)}`);
});
it("aborts superseded history requests without publishing their results", async () => {
  let firstSignal: AbortSignal | undefined;
  const fetcher = vi.fn().mockImplementationOnce((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
    firstSignal = options.signal!;
    firstSignal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
  })).mockResolvedValue(Response.json(page(2, [2], { queueId: 480 })));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(id(1)));
  await act(async () => {});
  await act(async () => hook.result.current.submit({ gameName: "Invented", tagLine: "NA1", queueId: 480 }));
  expect(firstSignal?.aborted).toBe(true);
  expect(hook.result.current.lookup?.queueId).toBe(480);
  expect(hook.result.current.issue).toBeNull();
});

it("restores forward navigation after a cached search on the same URL", async () => {
  const fetcher = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    const n = options.method === "POST" ? 2 : Number(url.slice(-12));
    return Response.json(page(n, [n]));
  });
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(({ runId }) => usePlayerLookup(runId), { initialProps: { runId: id(1) } });
  await act(async () => {});
  expect(hook.result.current.lookup?.runId).toBe(id(1));
  await act(async () => hook.result.current.submit({ gameName: "Invented", tagLine: "NA1", queueId: 420 }));
  await act(async () => hook.rerender({ runId: id(2) }));
  expect(hook.result.current.lookup?.runId).toBe(id(2));
  await act(async () => hook.result.current.submit({ gameName: "Invented", tagLine: "NA1", queueId: 420 }));
  await act(async () => hook.rerender({ runId: id(2) }));
  await act(async () => hook.rerender({ runId: id(1) }));
  expect(hook.result.current.lookup?.matches[0].matchId).toBe("NA1_1");
  await act(async () => hook.rerender({ runId: id(2) }));
  expect(hook.result.current.lookup?.runId).toBe(id(2));
  expect(hook.result.current.lookup?.matches[0].matchId).toBe("NA1_2");
  expect(push).toHaveBeenCalledTimes(1);
});

it("opens a Korean profile directly without a prior lookup handle", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json(page(1, [], { platform: "KR", gameName: "다른 이름", tagLine: "KR1", queueId: 0 })));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(undefined, { platform: "KR", gameName: "다른 이름", tagLine: "KR1" }));
  await act(async () => {});
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ platform: "KR", gameName: "다른 이름", tagLine: "KR1", queueId: 0 });
  expect(hook.result.current.lookup?.platform).toBe("KR");
});

it("rejects a profile handle belonging to another region before showing its matches", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(page(1, [1]))));
  const hook = renderHook(() => usePlayerLookup(id(1), { platform: "KR", gameName: "Invented", tagLine: "NA1" }));
  await act(async () => {});
  expect(hook.result.current.lookup).toBeNull();
  expect(hook.result.current.issue).not.toBeNull();
});


it("rejects a different identity when an unresolved profile finishes polling", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(page(1, [], { status: "RUNNING", gameName: "", tagLine: "", platform: "KR", queueId: 0 })))
    .mockResolvedValueOnce(Response.json(page(1, [], { gameName: "Someone else", tagLine: "KR1", platform: "KR", queueId: 0 })));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerLookup(undefined, { gameName: "Expected", tagLine: "KR1", platform: "KR" }));
  await act(async () => {});
  await act(async () => vi.advanceTimersByTime(2000));
  expect(hook.result.current.lookup?.gameName).not.toBe("Someone else");
  expect(hook.result.current.issue).not.toBeNull();
});
