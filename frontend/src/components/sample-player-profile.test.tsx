import { fireEvent, render, screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type { MatchSummary } from "../lib/player-lookup/types";
import { SamplePlayerProfile } from "./sample-player-profile";

vi.mock("../lib/game-assets/use-game-assets", () => ({
  useGameAssetCatalogs: () => ({}),
  useCurrentProfileAssets: () => null,
}));

it("filters the sample history and recent stats together while preserving the Challenger profile", () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const solo: MatchSummary = {
    matchId: "sample-solo", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, remake: false,
    startedAtMs: 1788890400000, durationSeconds: 1800, kills: 8, deaths: 3, assists: 7,
    cs: 228, gold: 13700, timelineAvailable: true,
  };
  const flex = { ...solo, matchId: "sample-flex", queueId: 440, win: false, championName: "Camille", championId: 164, participantId: 1 };
  const earlier = { ...solo, matchId: "sample-earlier", startedAtMs: solo.startedAtMs - 2 * 3600_000 };
  render(<SamplePlayerProfile matches={[solo, earlier, flex]} />);

  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Northline#DEMO");
  fireEvent.click(screen.getByRole("button", { name: "Update" }));
  expect(screen.getByText("Sample profile is up to date.")).toBeVisible();
  expect(fetcher).not.toHaveBeenCalled();
  const ranked = within(screen.getByRole("region", { name: "Ranked Solo/Duo" }));
  expect(ranked.getByText("Challenger")).toBeVisible();
  expect(ranked.getByText("1,284")).toHaveTextContent("1,284 LP");
  const recent = within(screen.getByRole("region", { name: "Recent performance" }));
  expect(recent.getByText("3 games in this history")).toBeVisible();
  const filter = screen.getByRole("combobox", { name: "Queue Type" });
  fireEvent.change(filter, { target: { value: "440" } });
  expect(recent.getByText("1 game in this history")).toBeVisible();
  expect(screen.getByRole("link", { name: "Camille defeat match development" })).toHaveAttribute("href", "/matches/sample-flex/development?focus=1&compare=6");
  expect(screen.queryByRole("link", { name: /^Garen victory match development/ })).toBeNull();
  fireEvent.change(filter, { target: { value: "450" } });
  expect(screen.getByRole("status")).toHaveTextContent("No ARAM matches in this sample.");
  expect(ranked.getByText("1,284")).toBeVisible();
  fireEvent.change(filter, { target: { value: "0" } });
  const garenLinks = screen.getAllByRole("link", { name: /^Garen victory match development/ });
  expect(garenLinks[0]).toHaveAttribute("href", "/matches/sample-solo/development?focus=6&compare=1");
  expect(garenLinks[0].getAttribute("aria-label")).not.toEqual(garenLinks[1].getAttribute("aria-label"));
  expect(garenLinks[0].getAttribute("aria-label")).toContain("UTC");
  expect(fetcher).not.toHaveBeenCalled();
});
