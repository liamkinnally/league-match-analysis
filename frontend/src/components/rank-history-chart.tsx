"use client";

import { useId } from "react";
import type { PlayerProfile, RankObservation } from "../lib/player-lookup/profile";
import { rankValue } from "./rank-observations";
import "./player-performance.css";

type Point = { row: RankObservation; time: number; value: number };
const tiers = ["IRON", "BRONZE", "SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND"];
const divisions = ["IV", "III", "II", "I"];

function progression(row: RankObservation): number | null {
  if (row.status !== "ranked" || row.leaguePoints === null || row.tier === null) return null;
  // Master, Grandmaster and Challenger share an LP ladder; promotion between them adds no LP.
  if (["MASTER", "GRANDMASTER", "CHALLENGER"].includes(row.tier)) return 2800 + row.leaguePoints;
  const tier = tiers.indexOf(row.tier), division = divisions.indexOf(row.division ?? "");
  return tier < 0 || division < 0 ? null : tier * 400 + division * 100 + row.leaguePoints;
}

export function buildRankHistorySeries(rows: RankObservation[]): { points: Point[]; connections: [Point, Point][] } {
  const points: Point[] = [], connections: [Point, Point][] = [];
  const ordered = [...new Map(rows.map(row => [row.id, row])).values()].sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt));
  let previous: Point | null = null;
  for (const row of ordered) {
    const value = progression(row), time = Date.parse(row.observedAt);
    if (value === null || !Number.isFinite(time)) { previous = null; continue; }
    const point = { row, value, time };
    if (previous && row.period !== null && row.period === previous.row.period) connections.push([previous, point]);
    points.push(point); previous = point;
  }
  return { points, connections };
}

function rankLabel(row: RankObservation): string {
  if (!row.tier) return "";
  const name = row.tier.slice(0, 1) + row.tier.slice(1).toLowerCase();
  return tiers.includes(row.tier) ? `${name} ${row.division}` : name;
}

export function RankHistoryChart({ history }: { history: PlayerProfile["rankHistory"] }) {
  const descriptionId = useId();
  const { points, connections } = buildRankHistorySeries(history.observations);
  const first = points[0], last = points.at(-1);
  const heading = <div className="player-rank-chart__heading"><h3>LP history</h3><span>{points.length} {points.length === 1 ? "update" : "updates"}</span></div>;
  if (!first || !last) return <div className="player-rank-chart">{heading}<p className="player-rank-chart__empty">LP history begins with your first rank update.</p></div>;
  if (points.length === 1) return <div className="player-rank-chart">{heading}<p className="player-rank-chart__empty"><strong>{rankValue(first.row)}</strong><br />Your next rank update adds another point.</p></div>;
  const lowest = points.reduce((a, b) => a.value <= b.value ? a : b);
  const highest = points.reduce((a, b) => a.value >= b.value ? a : b);
  const range = highest.value - lowest.value;
  const x = (point: Point) => last.time === first.time ? 197 : 84 + (point.time - first.time) / (last.time - first.time) * 226;
  const y = (point: Point) => range === 0 ? 78 : 132 - (point.value - lowest.value) / range * 108;
  const ticks = range === 0 ? [lowest] : [highest, lowest];
  const sameRank = points.every(point => rankLabel(point.row) === rankLabel(first.row));
  const sameDay = new Date(first.time).toDateString() === new Date(last.time).toDateString();
  const formatDate = (row: RankObservation) => new Date(row.observedAt).toLocaleString("en-US", sameDay
    ? { hour: "numeric", minute: "2-digit" } : { month: "short", day: "numeric" });
  return <figure className="player-rank-chart">
    {heading}
    <svg viewBox="0 0 320 156" role="img" aria-label={`LP history from ${points.length} rank updates`} aria-describedby={descriptionId}>
      <desc id={descriptionId}>{points.map(point => `${new Date(point.time).toLocaleString("en-US")}: ${rankValue(point.row)}`).join("; ")}. Points are recorded profile updates.</desc>
      {ticks.map(point => <g key={point.row.id}>
        <line className="player-rank-chart__grid" x1="84" x2="312" y1={y(point)} y2={y(point)} />
        <text className="player-rank-chart__axis" x="76" y={y(point) - (sameRank ? -3 : 4)} textAnchor="end">
          {point.row.leaguePoints?.toLocaleString("en-US")} LP
          {!sameRank && <tspan x="76" dy="12">{rankLabel(point.row)}</tspan>}
        </text>
      </g>)}
      {connections.map(([a, b]) => <path key={`${a.row.id}-${b.row.id}`} className="player-rank-chart__line" d={`M ${x(a)} ${y(a)} L ${x(b)} ${y(b)}`} />)}
      {points.map(point => <circle key={point.row.id} className="player-rank-chart__point" cx={x(point)} cy={y(point)} r="4">
        <title>{new Date(point.time).toLocaleString("en-US")}: {rankValue(point.row)}</title>
      </circle>)}
    </svg>
    <div className="player-rank-chart__dates"><time dateTime={first.row.observedAt}>{formatDate(first.row)}</time><time dateTime={last.row.observedAt}>{formatDate(last.row)}</time></div>
    <figcaption>Data based on profile updates.</figcaption>
  </figure>;
}
