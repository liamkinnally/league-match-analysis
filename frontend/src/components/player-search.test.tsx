import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import PlayerSearch from "./player-search";
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
const runId = "00000000-0000-0000-0000-000000000001";
const running = { runId, gameName: "Invented", tagLine: "NA1", status: "RUNNING", message: null, retryNotBefore: null, matches: [] };
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
  })).mockResolvedValue(Response.json({ ...running, status: "EMPTY" }));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  await act(async () => vi.advanceTimersByTime(12000));
  expect(screen.getByRole("alert")).toHaveTextContent(/taking longer than expected/);
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Retry loading" })));
  expect(screen.getByText(/No recent ranked Solo\/Duo matches/)).toBeVisible();
  expect(fetcher.mock.calls.every(([url]) => url === `/api/player-matches/${runId}`)).toBe(true);
});

it("rejects history belonging to a different run", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...running, runId: "00000000-0000-0000-0000-000000000002", status: "EMPTY" })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("alert")).toBeVisible();
  expect(screen.queryByRole("heading", { name: /Invented/ })).not.toBeInTheDocument();
});

it("shows the retrieved Riot ID in the form without overwriting edits during polling", async () => {
  vi.useFakeTimers();
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json(running)));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByLabelText("Game name")).toHaveValue("Invented");
  fireEvent.change(screen.getByLabelText("Game name"), { target: { value: "다른 이름" } });
  await act(async () => vi.advanceTimersByTime(2000));
  expect(screen.getByLabelText("Game name")).toHaveValue("다른 이름");
});

it("keeps a submitted form busy until its response and shows progress outside the button", async () => {
  vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
  render(<PlayerSearch />);
  fireEvent.change(screen.getByLabelText("Game name"), { target: { value: "Invented" } });
  fireEvent.change(screen.getByLabelText("Tag line"), { target: { value: "NA1" } });
  fireEvent.click(screen.getByRole("button", { name: "Find matches" }));
  expect(screen.getByRole("status")).toHaveTextContent("Starting player lookup");
  expect(screen.getByRole("button", { name: /Finding matches/ })).toBeDisabled();
});

it("shows new submission progress after a history loading failure", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json({}, { status: 503 })).mockImplementation(() => new Promise(() => {})));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("alert")).toBeVisible();
  fireEvent.change(screen.getByLabelText("Game name"), { target: { value: "Another player" } });
  fireEvent.change(screen.getByLabelText("Tag line"), { target: { value: "NA1" } });
  fireEvent.click(screen.getByRole("button", { name: "Find matches" }));
  expect(screen.getByRole("status")).toHaveTextContent("Starting player lookup");
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it("polls at two-second intervals and exposes completed rows while lookup continues", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockImplementation(async () => Response.json({ ...running, matches: [{ matchId: "NA1_7000000002", participantId: 6, championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [3071, 3047, 3053, 6333, 3065, 0, 3364], position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true }] }));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("link", { name: /Garen.*match development/ })).toHaveAttribute("href", "/matches/NA1_7000000002/development?focus=6");
  await act(async () => vi.advanceTimersByTime(1999));
  expect(fetcher.mock.calls.filter(([url]) => String(url).startsWith("/api/player-matches"))).toHaveLength(1);
  await act(async () => vi.advanceTimersByTime(1));
  expect(fetcher.mock.calls.filter(([url]) => String(url).startsWith("/api/player-matches"))).toHaveLength(2);
});
it("submits a Riot ID and stores the run in navigation history", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json(running, { status: 202 })));
  render(<PlayerSearch />);
  fireEvent.change(screen.getByLabelText("Game name"), { target: { value: "Invented" } });
  fireEvent.change(screen.getByLabelText("Tag line"), { target: { value: "NA1" } });
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Find matches" })));
  expect(push).toHaveBeenCalledWith(`/search?runId=${runId}`);
});
it("shows the empty-result message", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json({ ...running, status: "EMPTY" })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByText(/No recent ranked Solo\/Duo matches/)).toBeInTheDocument();
});

it("renders patch-matched champion and final-item assets in a compact history row", async () => {
  const match = { matchId: "NA1_7000000002", participantId: 6, championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [3071, 3047, 0, 0, 0, 0, 3340], position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true };
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
  expect(screen.getByText("Match development →")).toBeVisible();
});

it.each(["RUNNING", "FAILED"])("labels an unresolved %s lookup without rendering an empty Riot ID", async (status) => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...running, status, gameName: "", tagLine: "" })));
  await act(async () => render(<PlayerSearch initialRunId={runId} />));
  expect(screen.getByRole("heading", { name: "Player lookup" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: "#" })).not.toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByLabelText("Game name")).toHaveValue("");
  expect(screen.getByRole("button", { name: "Find matches" })).toBeEnabled();
});
