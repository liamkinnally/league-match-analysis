import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { usePlayerProfile } from "./use-player-profile";
import { profileFixture } from "./profile.test-fixture";
const runId = "00000000-0000-0000-0000-000000000001";
const subject = { runId, ...profileFixture.identity, updatedAt: "2026-09-14T10:00:00Z", historyStatus: "COMPLETE" };
afterEach(() => vi.useRealTimers());
it("waits for first account verification while history is running and shows the automatically fetched profile", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn()
    .mockImplementationOnce(async () => Response.json({}, { status: 404 }))
    .mockImplementation(async () => Response.json(profileFixture));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerProfile({ ...subject, historyStatus: "RUNNING" }));
  await act(async () => {});
  expect(hook.result.current.loading).toBe(true);
  expect(hook.result.current.issue).toBeNull();
  await act(async () => vi.advanceTimersByTime(2000));
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(hook.result.current.profile?.summoner.summonerLevel).toBe(123);
  expect(hook.result.current.loading).toBe(false);
  expect(fetcher.mock.calls.every(([, options]) => options.method === "GET")).toBe(true);
});
it("stops waiting for account verification when the history lookup fails", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockImplementation(async () => Response.json({}, { status: 404 }));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(({ status }) => usePlayerProfile({ ...subject, historyStatus: status }), { initialProps: { status: "RUNNING" } });
  await act(async () => {});
  await act(async () => hook.rerender({ status: "FAILED" }));
  expect(hook.result.current.loading).toBe(false);
  expect(hook.result.current.issue?.message).toContain("not available");
  await act(async () => vi.advanceTimersByTime(20_000));
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it("bounds verification polling if a running lookup never gains a verified identity", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockImplementation(async () => Response.json({}, { status: 404 }));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerProfile({ ...subject, historyStatus: "RUNNING" }));
  await act(async () => {});
  await act(async () => vi.advanceTimersByTimeAsync(15 * 60_000));
  expect(hook.result.current.loading).toBe(false);
  expect(hook.result.current.issue).not.toBeNull();
  expect(fetcher.mock.calls.length).toBeLessThanOrEqual(450);
  const calls = fetcher.mock.calls.length;
  await act(async () => vi.advanceTimersByTime(20_000));
  expect(fetcher).toHaveBeenCalledTimes(calls);
});
it("polls only refreshing cached projections and stops once sections finish", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockImplementationOnce(async () => Response.json({ ...profileFixture, soloRank: { ...profileFixture.soloRank, refreshing: true } })).mockImplementation(async () => Response.json(profileFixture));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerProfile(subject));
  await act(async () => {});
  expect(fetcher).toHaveBeenCalledTimes(1);
  await act(async () => vi.advanceTimersByTime(2000));
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(hook.result.current.profile?.soloRank.refreshing).toBe(false);
  await act(async () => vi.advanceTimersByTime(20000));
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(fetcher.mock.calls.every(([, options]) => options.method === "GET")).toBe(true);
});
it("retains previous profile data on failure and requires explicit recent collection", async () => {
  const fetcher = vi.fn().mockImplementation(async () => Response.json(profileFixture)); vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerProfile(subject)); await act(async () => {});
  expect(fetcher.mock.calls.filter(([, options]) => options.method === "POST")).toHaveLength(0);
  fetcher.mockImplementation(async () => Response.json({ message: "private" }, { status: 503 }));
  await act(async () => hook.result.current.reload());
  expect(hook.result.current.profile?.summoner.summonerLevel).toBe(123);
  expect(hook.result.current.issue?.message).toContain("Previously loaded values");
  fetcher.mockImplementation(async () => Response.json(profileFixture));
  await act(async () => { hook.result.current.loadRecent(); hook.result.current.loadRecent(); });
  expect(fetcher.mock.calls.filter(([, options]) => options.method === "POST")).toHaveLength(1);
});
it("aborts stale identity requests and rejects profiles bound to another player", async () => {
  let late: ((response: Response) => void) | undefined;
  const fetcher = vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { late = resolve; })).mockImplementation(async () => Response.json(profileFixture)); vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(({ value }) => usePlayerProfile(value), { initialProps: { value: subject } });
  await act(async () => {});
  const oldSignal = fetcher.mock.calls[0][1].signal as AbortSignal;
  await act(async () => hook.rerender({ value: { ...subject, gameName: "Different", runId: "00000000-0000-0000-0000-000000000002" } }));
  expect(oldSignal.aborted).toBe(true);
  expect(hook.result.current.profile).toBeNull();
  expect(hook.result.current.issue).not.toBeNull();
  await act(async () => late?.(Response.json(profileFixture)));
  expect(hook.result.current.profile).toBeNull();
});
it("appends paginated observations without duplicates and without importing private fields", async () => {
  const fetcher = vi.fn().mockImplementationOnce(async () => Response.json({ ...profileFixture, rankHistory: { ...profileFixture.rankHistory, nextCursor: "YWJj" } })).mockImplementation(async () => Response.json({ ...profileFixture, rankHistory: { ...profileFixture.rankHistory, observations: [...profileFixture.rankHistory.observations, { ...profileFixture.rankHistory.observations[0], id: "00000000-0000-0000-0000-000000000011", observedAt: "2026-09-13T10:00:00Z", puuid: "private" }] } })); vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerProfile(subject)); await act(async () => {});
  await act(async () => hook.result.current.loadOlder());
  expect(hook.result.current.profile?.rankHistory.observations).toHaveLength(2);
  expect(hook.result.current.profile?.rankHistory.observations[1]).not.toHaveProperty("puuid");
  expect(fetcher.mock.calls[1][0]).toBe(`/api/player-matches/${runId}/profile?cursor=YWJj`);
});
it("honors a server cooldown even when an earlier profile allowed recent collection", async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-09-14T11:00:00Z"));
  const fetcher = vi.fn().mockImplementationOnce(async () => Response.json(profileFixture)).mockImplementation(async () => Response.json({ retryNotBefore: "2026-09-14T12:00:00Z" }, { status: 429 })); vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => usePlayerProfile(subject)); await act(async () => {});
  await act(async () => hook.result.current.loadRecent());
  await act(async () => hook.result.current.loadRecent());
  expect(fetcher.mock.calls.filter(([, options]) => options.method === "POST")).toHaveLength(1);
});

it("keeps identical Riot IDs isolated when the selected platform changes", async () => {
  const fetcher = vi.fn().mockImplementation(async () => Response.json(profileFixture));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(({ platform }: { platform: "NA1" | "KR" }) => usePlayerProfile({ ...subject, platform }), { initialProps: { platform: "NA1" } });
  await act(async () => {});
  expect(hook.result.current.profile?.summoner.summonerLevel).toBe(123);
  await act(async () => hook.rerender({ platform: "KR" }));
  expect(hook.result.current.profile).toBeNull();
  expect(hook.result.current.issue).not.toBeNull();
});
