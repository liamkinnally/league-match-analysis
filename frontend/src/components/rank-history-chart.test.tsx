import { render, screen } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { expect, it } from "vitest";
import { parsePlayerProfile, type RankObservation } from "../lib/player-lookup/profile";
import { profileFixture } from "../lib/player-lookup/profile.test-fixture";
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

it("joins adjacent stored rank updates when the backend supplies no period key", () => {
  const first = profileFixture.rankHistory.observations[0];
  const history = parsePlayerProfile({ ...profileFixture, rankHistory: {
    ...profileFixture.rankHistory,
    observations: [
      { ...first, id: "00000000-0000-0000-0000-000000000013", observedAt: "2026-09-16T10:01:00Z", leaguePoints: 21, losses: 61 },
      { ...first, id: "00000000-0000-0000-0000-000000000012", observedAt: "2026-09-15T10:01:00Z" },
      first,
    ],
  } }).rankHistory;
  const { container } = render(<RankHistoryChart history={history} />);
  const chart = container.querySelector("svg")!;
  const points = Array.from(chart.querySelectorAll("circle"), point => `${point.getAttribute("cx")} ${point.getAttribute("cy")}`);
  const segments = Array.from(chart.querySelectorAll("path"), line => line.getAttribute("d"));
  expect(segments).toEqual([`M ${points[0]} L ${points[1]}`, `M ${points[1]} L ${points[2]}`]);
});

it("does not connect observations across changes in period keys, invalid ranks or unranked gaps", () => {
  const series = buildRankHistorySeries([
    rank(1), rank(2, { period: null }), rank(3),
    rank(4, { period: "2026-reset" }),
    rank(5, { status: "unranked", tier: null, division: null, leaguePoints: null, period: "2026-reset" }),
    rank(6, { period: "2026-reset" }),
    rank(7, { tier: null, period: "2026-reset" }), rank(8, { period: "2026-reset" }),
  ]);
  expect(series.points).toHaveLength(6);
  expect(series.connections).toHaveLength(0);
});

it("starts a new segment if either cumulative record count falls", () => {
  const series = buildRankHistorySeries([
    rank(1, { period: null }), rank(2, { period: null, wins: 2, losses: 61 }),
    rank(3, { period: null, wins: 4, losses: 62 }), rank(4, { period: null, wins: 4, losses: 3 }),
    rank(5, { period: null, wins: null, losses: null }),
  ]);
  expect(series.connections.map(([a, b]) => [a.row.id, b.row.id])).toEqual([
    ["rank-2", "rank-3"], ["rank-4", "rank-5"],
  ]);
});

it("shows truthful empty and single-observation states without an invented history curve", () => {
  const { rerender } = render(<RankHistoryChart history={{ trackingSince: null, observations: [], nextCursor: null }} />);
  expect(screen.getByText("LP history begins with your first rank update.")).toBeInTheDocument();
  rerender(<RankHistoryChart history={{ trackingSince: "2026-09-01T10:00:00Z", observations: [rank(1)], nextCursor: null }} />);
  expect(screen.getByText("Diamond I · 99 LP")).toBeInTheDocument();
  expect(screen.getByText("Your next rank update adds another point.")).toBeInTheDocument();
  expect(screen.queryByRole("img", { name: /LP history/ })).not.toBeInTheDocument();
});

it("server-renders each chart point's timestamp and rank along with the chart description", () => {
  const html = renderToString(<RankHistoryChart timeZone="UTC" history={{ trackingSince: "2026-09-01T10:00:00Z", observations: [rank(1), rank(3, { leaguePoints: 70 })], nextCursor: null }} />);
  render(<div dangerouslySetInnerHTML={{ __html: html }} />);
  const chart = screen.getByRole("img", { name: "LP history from 2 rank updates" });
  expect(chart).toHaveAccessibleDescription(/Diamond I · 99 LP.*Diamond I · 70 LP/);
  expect(Array.from(chart.querySelectorAll("circle title"), title => title.textContent)).toEqual([
    "9/1/2026, 10:00:00 AM: Diamond I · 99 LP",
    "9/3/2026, 10:00:00 AM: Diamond I · 70 LP",
  ]);
});
