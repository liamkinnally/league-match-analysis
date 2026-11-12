import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import PlayerSearch from "./player-search";
import { MatchHistory } from "./match-history";
import { RankHistoryChart } from "./rank-history-chart";
import { summarizePerformance } from "../lib/player-lookup/performance";
import { profileFixture } from "../lib/player-lookup/profile.test-fixture";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("./match-history", async importOriginal => {
  const actual = await importOriginal<typeof import("./match-history")>();
  return { ...actual, MatchHistory: vi.fn(actual.MatchHistory) };
});
vi.mock("./rank-history-chart", async importOriginal => {
  const actual = await importOriginal<typeof import("./rank-history-chart")>();
  return { ...actual, RankHistoryChart: vi.fn(actual.RankHistoryChart) };
});
vi.mock("../lib/player-lookup/performance", async importOriginal => {
  const actual = await importOriginal<typeof import("../lib/player-lookup/performance")>();
  return { ...actual, summarizePerformance: vi.fn(actual.summarizePerformance) };
});
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

it("ticks update and rank freshness without rebuilding history summaries, rows or charts", async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-09-14T10:01:59Z"));
  const runId = "00000000-0000-0000-0000-000000000001";
  const match = { matchId: "NA1_21", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, remake: false, startedAtMs: 1788890400000,
    durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: false };
  const history = { runId, ...profileFixture.identity, status: "COMPLETE", message: null, retryNotBefore: "2026-09-14T10:02:04Z",
    queueId: 420, lastUpdated: "2026-09-14T10:01:59Z", nextRefreshAt: "2026-09-14T10:02:02Z", previousRunId: null,
    hasMore: false, matches: [match] };
  const fetcher = vi.fn(async (url: string) => {
    if (url.includes("scope=current-profile")) return Response.json({ assetVersion: "16.17.1", champions: {}, items: {}, spells: {} });
    if (url.startsWith("/api/game-assets")) return Response.json({ "16.17.1": { assetVersion: "16.17.1", champions: {}, items: {}, spells: {} } });
    if (url.endsWith("/profile")) return Response.json(profileFixture);
    return Response.json(history);
  });
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("region", { name: "Recent performance" })).toHaveTextContent("1 game in this history");
  expect(screen.getByRole("link", { name: "Garen victory match development" })).toBeVisible();
  expect(screen.getByText("just now")).toBeVisible();
  expect(screen.getByText("Update in 0:05")).toBeVisible();
  expect(screen.getByRole("button", { name: "Update" })).toBeDisabled();
  const calls = { summary: vi.mocked(summarizePerformance).mock.calls.length,
    rows: vi.mocked(MatchHistory).mock.calls.length, chart: vi.mocked(RankHistoryChart).mock.calls.length };
  await act(async () => vi.advanceTimersByTime(3000));
  expect(screen.getByText("1 minute ago")).toBeVisible();
  expect(screen.getByRole("button", { name: "Update" })).toBeDisabled();
  expect(screen.getByText("Update in 0:02")).toBeVisible();
  await act(async () => vi.advanceTimersByTime(2000));
  expect(screen.getByRole("button", { name: "Update" })).toBeEnabled();
  expect(vi.mocked(summarizePerformance).mock.calls.length).toBe(calls.summary);
  expect(vi.mocked(MatchHistory).mock.calls.length).toBe(calls.rows);
  expect(vi.mocked(RankHistoryChart).mock.calls.length).toBe(calls.chart);
  const requestsBeforeUpdate = fetcher.mock.calls.filter(([url]) => !url.startsWith("/api/game-assets"));
  expect(requestsBeforeUpdate.map(([url]) => url)).toEqual([`/api/player-matches/${runId}`, `/api/player-matches/${runId}/profile`]);
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Update" })));
  expect(fetcher.mock.calls.some(([url]) => url === `/api/player-matches/${runId}/refresh`)).toBe(true);
});
