import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import type { RankObservation } from "../lib/player-lookup/profile";
import { buildRankHistorySeries, RankHistoryChart } from "./rank-history-chart";

const rank = (day: number, overrides: Partial<RankObservation> = {}): RankObservation => ({
  id: `rank-${day}`, observedAt: `2026-09-${String(day).padStart(2, "0")}T10:00:00Z`,
  status: "ranked", tier: "DIAMOND", division: "I", leaguePoints: 99, wins: 80, losses: 60, period: "2026", ...overrides,
});

it("uses ranked progression across division promotion and keeps apex LP on one scale", () => {
  const series = buildRankHistorySeries([
    rank(4, { tier: "CHALLENGER", leaguePoints: 600 }),
    rank(2, { tier: "MASTER", leaguePoints: 3 }),
    rank(1), rank(3, { tier: "GRANDMASTER", leaguePoints: 600 }),
  ]);
  expect(series.points.map(point => point.value)).toEqual([2799, 2803, 3400, 3400]);
  expect(series.points.map(point => point.row.id)).toEqual(["rank-1", "rank-2", "rank-3", "rank-4"]);
  expect(series.connections).toHaveLength(3);
});

it("does not connect observations across unknown periods, ranked resets or unranked gaps", () => {
  const series = buildRankHistorySeries([
    rank(1), rank(2, { period: null }), rank(3),
    rank(4, { period: "2026-reset" }),
    rank(5, { status: "unranked", tier: null, division: null, leaguePoints: null, period: "2026-reset" }),
    rank(6, { period: "2026-reset" }),
  ]);
  expect(series.points).toHaveLength(5);
  expect(series.connections).toHaveLength(0);
});

it("shows truthful empty and single-observation states without an invented history curve", () => {
  const { rerender } = render(<RankHistoryChart history={{ trackingSince: null, observations: [], nextCursor: null }} />);
  expect(screen.getByText("LP history begins with your first rank update.")).toBeInTheDocument();
  rerender(<RankHistoryChart history={{ trackingSince: "2026-09-01T10:00:00Z", observations: [rank(1)], nextCursor: null }} />);
  expect(screen.getByText("Diamond I · 99 LP")).toBeInTheDocument();
  expect(screen.getByText("Your next rank update adds another point.")).toBeInTheDocument();
  expect(screen.queryByRole("img", { name: /LP history/ })).not.toBeInTheDocument();
});

it("gives the chart a text alternative with the exact recorded ranks", () => {
  render(<RankHistoryChart history={{ trackingSince: "2026-09-01T10:00:00Z", observations: [rank(1), rank(3, { leaguePoints: 70 })], nextCursor: null }} />);
  expect(screen.getByRole("img", { name: "LP history from 2 rank updates" })).toHaveAccessibleDescription(/Diamond I · 99 LP.*Diamond I · 70 LP/);
});
