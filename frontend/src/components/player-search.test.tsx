import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import PlayerSearch from "./player-search";
vi.mock("../lib/player-lookup/use-player-profile", () => ({ usePlayerProfile: () => ({ profile: null, issue: null, loading: false, loadingRecent: false, loadingOlder: false, busy: false, reload: vi.fn(), loadRecent: vi.fn(), loadOlder: vi.fn() }) }));
vi.mock("./player-profile", () => ({ PlayerProfileHeader: ({ identity, children }: { identity: { gameName: string; tagLine: string }; children?: import("react").ReactNode }) => <><h2>{identity.gameName}<span>#{identity.tagLine}</span></h2>{children}</>, PlayerProfilePanel: () => null }));
vi.mock("../lib/game-assets/use-game-assets", async importOriginal => ({ ...await importOriginal<typeof import("../lib/game-assets/use-game-assets")>(), useCurrentProfileAssets: () => null }));
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
const runId = "00000000-0000-0000-0000-000000000001";
const running = { runId, gameName: "Invented", tagLine: "NA1", status: "RUNNING", message: null, retryNotBefore: null, queueId: 420, lastUpdated: null, nextRefreshAt: null, previousRunId: null, hasMore: true, matches: [] };
const assets = {
  assetVersion: "16.17.1",
  champions: { "86": { name: "Garen", imageUrl: "https://assets.test/Garen.png" } },
  items: {
    "3071": { name: "Black Cleaver", imageUrl: "https://assets.test/3071.png" },
    "3340": { name: "Stealth Ward", imageUrl: "https://assets.test/3340.png" },
  },
  spells: {},
};
afterEach(() => vi.useRealTimers());

it("keeps the form and an honest loading state visible before the first history response", () => {
  vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
  render(<PlayerSearch initialRunId={runId} />);
  expect(screen.getByRole("status")).toHaveTextContent("Loading match history");
  expect(screen.getByRole("button", { name: "Find matches" })).toBeEnabled();
  expect(screen.queryByText(/No recent ranked/)).not.toBeInTheDocument();
});

it("ends a stalled history request and retries the same run without starting another lookup", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockImplementationOnce((_url, { signal }: RequestInit) => new Promise((_resolve, reject) => {
    signal?.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
  })).mockResolvedValue(Response.json({ ...running, status: "EMPTY", hasMore: false }));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  await act(async () => vi.advanceTimersByTime(12000));
  expect(screen.getByRole("alert")).toHaveTextContent(/taking longer than expected/);
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Retry loading" })));
  expect(screen.getByText(/No Ranked Solo\/Duo matches/)).toBeVisible();
  expect(fetcher.mock.calls.every(([url]) => url === `/api/player-matches/${runId}`)).toBe(true);
});

it("rejects history belonging to a different run", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...running, runId: "00000000-0000-0000-0000-000000000002", status: "EMPTY", hasMore: false })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("alert")).toBeVisible();
  expect(screen.queryByRole("heading", { name: /Invented/ })).not.toBeInTheDocument();
});

it("shows the retrieved Riot ID in the form without overwriting edits during polling", async () => {
  vi.useFakeTimers();
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json(running)));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByLabelText("Riot ID")).toHaveValue("Invented#NA1");
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "다른 이름" } });
  await act(async () => vi.advanceTimersByTime(2000));
  expect(screen.getByLabelText("Riot ID")).toHaveValue("다른 이름");
});

it("keeps a submitted form busy until its response and shows progress outside the button", async () => {
  vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
  render(<PlayerSearch />);
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Invented#NA1" } });
  fireEvent.click(screen.getByRole("button", { name: "Find matches" }));
  expect(screen.getByRole("status")).toHaveTextContent("Starting player lookup");
  expect(screen.getByRole("button", { name: /Finding matches/ })).toBeDisabled();
});

it("shows new submission progress after a history loading failure", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json({}, { status: 503 })).mockImplementation(() => new Promise(() => {})));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("alert")).toBeVisible();
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Another player#NA1" } });
  fireEvent.click(screen.getByRole("button", { name: "Find matches" }));
  expect(screen.getByRole("status")).toHaveTextContent("Starting player lookup");
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it("polls at two-second intervals and exposes completed rows while lookup continues", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockImplementation(async () => Response.json({ ...running, matches: [{ matchId: "NA1_7000000002", queueId: 420, participantId: 6, championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [3071, 3047, 3053, 6333, 3065, 0, 3364], position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true }] }));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("link", { name: /Garen.*match development/ })).toHaveAttribute("href", `/matches/NA1_7000000002/development?focus=6&historyRunId=${runId}`);
  await act(async () => vi.advanceTimersByTime(1999));
  expect(fetcher.mock.calls.filter(([url]) => String(url).startsWith("/api/player-matches"))).toHaveLength(1);
  await act(async () => vi.advanceTimersByTime(1));
  expect(fetcher.mock.calls.filter(([url]) => String(url).startsWith("/api/player-matches"))).toHaveLength(2);
});
it("submits a Riot ID and stores the run in navigation history", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json({ ...running, queueId: 0 }, { status: 202 })));
  render(<PlayerSearch />);
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Invented#NA1" } });
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Find matches" })));
  expect(push).toHaveBeenCalledWith("/summoners/na/Invented-NA1");
});
it("shows the empty-result message", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json({ ...running, status: "EMPTY", hasMore: false })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByText(/No Ranked Solo\/Duo matches/)).toBeInTheDocument();
});

it("renders patch-matched champion and final-item assets in a compact history row", async () => {
  const match = { matchId: "NA1_7000000002", queueId: 420, participantId: 6, championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [3071, 3047, 0, 0, 0, 0, 3340], position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true };
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async (input: string | URL | Request) => {
    const url = String(input);
    if (url.startsWith("/api/game-assets")) return Response.json({ "16.17.1": assets });
    return Response.json({ ...running, status: "COMPLETE", matches: [match] });
  }));

  await act(async () => render(<PlayerSearch initialRunId={runId} />));

  expect(await screen.findByRole("img", { name: "Garen" })).toHaveAttribute("src", "https://assets.test/Garen.png");
  expect(screen.getByTitle("Black Cleaver")).toBeVisible();
  const inventory = screen.getByLabelText("Final items");
  expect(inventory.children).toHaveLength(7);
  expect(inventory.children[2]).toHaveAttribute("title", "Empty item slot 3");
  expect(inventory.children[6]).toHaveAttribute("title", "Stealth Ward");
  expect(inventory.children[6]).toHaveClass("development-item-slot--trinket");
  expect(screen.getByRole("link", { name: "Garen victory match development" })).toHaveAttribute("href", expect.stringContaining("historyRunId="));
});

it.each(["RUNNING", "FAILED"])("labels an unresolved %s lookup without rendering an empty Riot ID", async (status) => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...running, status, gameName: "", tagLine: "" })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("heading", { name: "Find a player’s match history" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: "#" })).not.toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByLabelText("Riot ID")).toHaveValue("");
  expect(screen.getByRole("button", { name: "Find matches" })).toBeEnabled();
});

it("labels normal queues accurately and unlocks explicit update after its countdown", async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-09-12T00:00:00Z"));
  const fetcher = vi.fn().mockImplementation(async () => Response.json({ ...running, queueId: 480, status: "EMPTY", hasMore: false, lastUpdated: "2026-09-12T00:00:00Z", nextRefreshAt: "2026-09-12T00:00:03Z" }));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByLabelText("Queue Type")).toHaveValue("480");
  expect(screen.getByText(/No Swiftplay matches/)).toBeVisible();
  expect(screen.getByRole("button", { name: "Update" })).toBeDisabled();
  expect(screen.getByText("Update in 0:03")).toBeVisible();
  await act(async () => vi.advanceTimersByTime(3000));
  expect(screen.getByRole("button", { name: "Update" })).toBeEnabled();
  expect(fetcher).toHaveBeenCalledTimes(1);
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Update" })));
  expect(fetcher.mock.calls[1][0]).toBe(`/api/player-matches/${runId}/refresh`);
});

it.each(["RUNNING", "FAILED", "PARTIAL"])("does not claim the end of history when %s has no known next page", async (status) => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...running, status, hasMore: false })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.queryByText("End of match history for this queue.")).not.toBeInTheDocument();
});


it("searches all queues before exposing a result filter and labels each match by its own queue", async () => {
  const match = { matchId: "NA1_21", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, startedAtMs: 1788890400000,
    durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: false };
  const fetcher = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    if (url.startsWith("/api/game-assets")) return Response.json({});
    const input = JSON.parse(String(options.body));
    return Response.json({ ...running, runId: input.queueId === 0 ? runId : "00000000-0000-0000-0000-000000000002",
      gameName: input.gameName, queueId: input.queueId, status: "COMPLETE",
      matches: input.queueId === 0 ? [match, { ...match, matchId: "NA1_22", queueId: 480 }] : [{ ...match, matchId: "NA1_23", queueId: 480 }] });
  });
  vi.stubGlobal("fetch", fetcher);
  render(<PlayerSearch />);
  expect(screen.queryByRole("combobox", { name: "Queue Type" })).not.toBeInTheDocument();
  expect(screen.queryByText(/All supported queues/)).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Invented#NA1" } });
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Find matches" })));
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ gameName: "Invented", tagLine: "NA1", platform: "NA1", queueId: 0 });
  expect(screen.getByLabelText("Queue Type")).toHaveValue("0");
  expect(within(screen.getByLabelText("Queue Type")).getAllByRole("option").map(option => option.textContent)).toEqual(["All queues", "Ranked Solo/Duo", "Ranked Flex", "Draft Pick", "Swiftplay", "ARAM"]);
  const rows = within(screen.getByRole("list", { name: "Recent match history" }));
  expect(rows.getByText("Ranked Solo/Duo")).toBeVisible();
  expect(rows.getByText("Swiftplay")).toBeVisible();
  // Editing a new search must not change whose history the result filter queries.
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Another#NA1" } });
  await act(async () => fireEvent.change(screen.getByLabelText("Queue Type"), { target: { value: "480" } }));
  const posts = fetcher.mock.calls.filter(([url]) => url === "/api/player-matches");
  expect(JSON.parse(posts[1][1].body)).toEqual({ gameName: "Invented", tagLine: "NA1", platform: "NA1", queueId: 480 });
  expect(screen.getByLabelText("Queue Type")).toHaveValue("480");
  expect(within(screen.getByRole("list", { name: "Recent match history" })).queryByText("Ranked Solo/Duo")).not.toBeInTheDocument();
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Find matches" })));
  expect(screen.getByLabelText("Queue Type")).toHaveValue("0");
});

it("keeps an empty raw page scoped to that page when older supported matches may exist", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...running, queueId: 0, status: "EMPTY", hasMore: true })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByText("No supported matches on this page")).toBeVisible();
  expect(screen.getByText("Load older matches to continue through this player's history.")).toBeVisible();
  expect(screen.getByRole("button", { name: "Load older matches" })).toBeEnabled();
  expect(screen.queryByText("No supported matches found on NA1.")).not.toBeInTheDocument();
});

it("uses a full Riot ID example and never relabels loaded identity from edited inputs", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json({ ...running, status: "EMPTY", hasMore: false })));
  const empty = render(<PlayerSearch />);
  expect(screen.getByLabelText("Riot ID")).toHaveAttribute("placeholder", "Game name#tag");
  expect(screen.getByLabelText("Riot ID")).toHaveValue("");
  empty.unmount();
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Edited search#NA1" } });
  expect(screen.getByRole("heading", { name: "Invented#NA1" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: /Edited search/ })).not.toBeInTheDocument();
});
