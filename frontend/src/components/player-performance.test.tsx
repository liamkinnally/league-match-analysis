import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { MatchSummary } from "../lib/player-lookup/types";
import { RecentPerformance } from "./player-performance";

const match = (id: number, values: Partial<MatchSummary> = {}): MatchSummary => ({
  matchId: `NA1_${id}`, queueId: 420, participantId: 1, championId: 86, championName: "Garen",
  gameVersion: "16.18.1", endItemIds: [], position: "TOP", win: true, remake: false,
  startedAtMs: 100_000 + id, durationSeconds: 1200, kills: 4, deaths: 4, assists: 4,
  cs: 100, gold: 8000, timelineAvailable: true, ...values,
});

describe("recent performance color thresholds", () => {
  it.each([
    { wins: 0, expected: "0%", losing: true },
    { wins: 1, expected: "50%", losing: false },
    { wins: 2, expected: "100%", losing: false },
  ])("only marks win rates below 50% as losing ($expected)", ({ wins, expected, losing }) => {
    const { container } = render(<RecentPerformance catalogs={{}} matches={[
      match(1, { win: wins > 0 }), match(2, { win: wins > 1 }),
    ]} />);
    const ring = container.querySelector(".player-performance__ring");
    expect(ring).toHaveTextContent(expected);
    expect(ring?.classList.contains("player-performance__ring--losing")).toBe(losing);
    const champion = within(screen.getByRole("list")).getByText(expected);
    expect(champion.classList.contains("player-performance__winrate--losing")).toBe(losing);
  });

  it("uses unrounded values when a losing rate or low KDA rounds up to its boundary", () => {
    const matches = Array.from({ length: 200 }, (_, index) => match(index, {
      win: index < 99, kills: 1999, deaths: 1000, assists: 0,
    }));
    const { container } = render(<RecentPerformance catalogs={{}} matches={matches} />);
    const ring = container.querySelector(".player-performance__ring");
    expect(ring).toHaveTextContent("50%");
    expect(ring).toHaveClass("player-performance__ring--losing");
    expect(within(screen.getByRole("list")).getByText("50%")).toHaveClass("player-performance__winrate--losing");
    expect(screen.getByText("2.00:1")).toHaveClass("player-performance__kda--low");
  });

  it("colors each most-played champion independently of the overall record", () => {
    const { container } = render(<RecentPerformance catalogs={{}} matches={[
      match(1), match(2),
      match(3, { championId: 103, championName: "Ahri" }),
      match(4, { championId: 103, championName: "Ahri", win: false }),
      match(5, { championId: 122, championName: "Darius", win: false }),
    ]} />);
    const ring = container.querySelector(".player-performance__ring");
    expect(ring).toHaveTextContent("60%");
    expect(ring).not.toHaveClass("player-performance__ring--losing");
    const champions = screen.getAllByRole("listitem");
    expect(within(champions.find(row => row.textContent?.includes("Garen"))!).getByText("100%"))
      .not.toHaveClass("player-performance__winrate--losing");
    expect(within(champions.find(row => row.textContent?.includes("Ahri"))!).getByText("50%"))
      .not.toHaveClass("player-performance__winrate--losing");
    expect(within(champions.find(row => row.textContent?.includes("Darius"))!).getByText("0%"))
      .toHaveClass("player-performance__winrate--losing");
  });

  it.each([
    { kills: 2, expected: "1.50:1", low: true },
    { kills: 4, expected: "2.00:1", low: false },
    { kills: 8, expected: "3.00:1", low: false },
  ])("only uses the low KDA style below 2.0:1 ($expected)", ({ kills, expected, low }) => {
    render(<RecentPerformance catalogs={{}} matches={[match(1, { kills })]} />);
    expect(screen.getByText(expected).classList.contains("player-performance__kda--low")).toBe(low);
  });

  it("keeps deathless values distinct from low KDA and absent results distinct from zero", () => {
    const view = render(<RecentPerformance catalogs={{}} matches={[match(1, { deaths: 0 })]} />);
    expect(screen.getByText("Deathless")).not.toHaveClass("player-performance__kda--low");
    for (const matches of [[], [match(1, { remake: null })], [match(1, { remake: true })]]) {
      view.rerender(<RecentPerformance catalogs={{}} matches={matches} />);
      expect(screen.getByText("No completed results to summarize yet.")).toBeVisible();
      expect(view.container.querySelector(".player-performance__ring")).toBeNull();
      expect(view.container.querySelector(".player-performance__kda")).toBeNull();
    }
  });
});
