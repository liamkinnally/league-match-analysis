import { act, fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import Link from "next/link";
import Home from "../app/page";
import SearchPage from "../app/search/page";
import ProfilePage from "../app/summoners/[region]/[riotId]/page";

vi.mock("server-only", () => ({}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("./sample-match-link", () => ({
  SampleMatchLink: () => <Link href="/matches/sample">Explore sample match</Link>,
  SampleMatchPending: () => <p>Loading sample match…</p>,
}));
vi.mock("../lib/game-assets/use-game-assets", () => ({ useGameAssetCatalogs: () => ({}), useCurrentProfileAssets: () => null }));
vi.mock("../lib/player-lookup/use-player-profile", () => ({ usePlayerProfile: () => ({ profile: null, loading: false, busy: false }) }));

const runId = "00000000-0000-0000-0000-000000000001";
const history = {
  runId, platform: "NA1", gameName: "Invented", tagLine: "NA1", status: "COMPLETE",
  message: null, retryNotBefore: null, queueId: 0, lastUpdated: null,
  nextRefreshAt: null, previousRunId: null, hasMore: false,
  matches: [{ matchId: "NA1_7000000002", queueId: 420, participantId: 6,
    championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [],
    position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800,
    kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true }],
};

it("keeps the sample on Home", () => {
  render(<Home />);
  expect(screen.getByRole("link", { name: "Explore sample match" })).toBeVisible();
});

it("offers the sample before searching and removes it when a search displays matches", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json(history)));
  render(await SearchPage({ searchParams: Promise.resolve({}) }));
  expect(screen.getByRole("link", { name: "Explore sample match" })).toBeVisible();
  fireEvent.change(screen.getByLabelText("Riot ID"), { target: { value: "Invented#NA1" } });
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Find matches" })));
  expect(await screen.findByRole("list", { name: "Recent match history" })).toBeVisible();
  expect(screen.queryByRole("link", { name: "Explore sample match" })).not.toBeInTheDocument();
});

it.each(["history", "profile"])("omits the sample below matches restored on the %s route", async route => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json(history)));
  const page = route === "profile"
    ? await ProfilePage({ params: Promise.resolve({ region: "na", riotId: "Invented-NA1" }), searchParams: Promise.resolve({ runId }) })
    : await SearchPage({ searchParams: Promise.resolve({ runId }) });
  await act(async () => render(page));
  expect(await screen.findByRole("list", { name: "Recent match history" })).toBeVisible();
  expect(screen.queryByRole("link", { name: "Explore sample match" })).not.toBeInTheDocument();
});

it.each(["EMPTY", "FAILED"])("keeps the sample available when a lookup is %s without matches", async status => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json({ ...history, status, matches: [] })));
  await act(async () => render(await SearchPage({ searchParams: Promise.resolve({ runId }) })));
  expect(screen.queryByRole("list", { name: "Recent match history" })).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Explore sample match" })).toBeVisible();
});
