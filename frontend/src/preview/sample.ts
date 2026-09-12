import "server-only";

import source from "./sample.json";
import { parseMatchDevelopment } from "../lib/development/response-guards";
import type { DemoMatch, MatchDevelopment, MatchDevelopmentSample, MatchDevelopmentWindow } from "../lib/development/types";

// This adapter only projects the bundled invented match. Backend responses remain
// authoritative for stored matches; no live match data is calculated here.
export const previewDemo: DemoMatch = {
  matchId: "__preview_sample", focusParticipantId: 6, compareParticipantId: 1, invented: true,
};

type Observation = { totalGold: number | null; cs: number | null; xp: number | null; level: number | null };

const difference = (left: number | null | undefined, right: number | null | undefined) =>
  left != null && right != null ? left - right : null;

function windowSummary(before: MatchDevelopmentSample, after: MatchDevelopmentSample, champion: string): MatchDevelopmentWindow {
  const signed = (value: number) => value > 0 ? `+${value}` : String(value);
  const details = ([ ["CS", "csDifference"], ["gold", "goldDifference"], ["XP", "xpDifference"] ] as const)
    .flatMap(([name, key]) => before[key] != null && after[key] != null
      ? [`${name} ${signed(before[key])} to ${signed(after[key])}`] : []);
  return { id: `${before.timestampMs}-${after.timestampMs}`, startMs: before.timestampMs, endMs: after.timestampMs,
    before, after, summary: `${champion}'s recorded comparison: ${details.join(", ") || "no comparable CS, gold, or XP observations"}.` };
}

function windows(samples: MatchDevelopmentSample[], champion: string) {
  const selectable = new Map<string, MatchDevelopmentWindow>();
  const candidates: MatchDevelopmentWindow[] = [];
  for (const [index, before] of samples.entries()) {
    const next = samples[index + 1];
    if (next && ["goldDifference", "csDifference", "xpDifference"].some((key) =>
      before[key as keyof MatchDevelopmentSample] != null && next[key as keyof MatchDevelopmentSample] != null)) {
      const window = windowSummary(before, next, champion);
      selectable.set(window.id, window);
    }
    if (before.goldDifference == null) continue;
    const after = samples.slice(index + 1).find((sample) => sample.timestampMs - before.timestampMs >= 120_000
      && sample.timestampMs - before.timestampMs <= 180_000 && sample.goldDifference != null);
    if (!after) continue;
    const window = windowSummary(before, after, champion);
    selectable.set(window.id, window);
    if (Math.abs(after.goldDifference! - before.goldDifference) >= 300) candidates.push(window);
  }
  const magnitude = (window: MatchDevelopmentWindow) => Math.abs(window.after.goldDifference! - window.before.goldDifference!);
  candidates.sort((a, b) => magnitude(b) - magnitude(a) || a.startMs - b.startMs);
  const suggested: MatchDevelopmentWindow[] = [];
  for (const candidate of candidates) {
    if (suggested.every((selected) => selected.endMs <= candidate.startMs || candidate.endMs <= selected.startMs)) suggested.push(candidate);
    if (suggested.length === 3) break;
  }
  return { windows: [...selectable.values()].sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs),
    suggestedWindows: suggested.sort((a, b) => a.startMs - b.startMs) };
}

export function sampleDevelopment(matchId: string, focus: number, compare?: number): MatchDevelopment | undefined {
  if (matchId !== previewDemo.matchId) return undefined;
  const focal = source.roster.find((player) => player.participantId === focus);
  if (!focal || (compare !== undefined && !source.roster.some((player) => player.participantId === compare))) return undefined;
  const samples = source.frames.map((frame): MatchDevelopmentSample => {
    const people = frame.participants as Record<string, Observation>;
    const left = people[String(focus)], right = compare === undefined ? undefined : people[String(compare)];
    return { timestampMs: frame.timestampMs,
      goldDifference: difference(left?.totalGold, right?.totalGold), csDifference: difference(left?.cs, right?.cs),
      xpDifference: difference(left?.xp, right?.xp), focalLevel: left?.level ?? null, compareLevel: right?.level ?? null,
      focalTotalGold: left?.totalGold ?? null, focalCs: left?.cs ?? null, focalXp: left?.xp ?? null };
  });
  return parseMatchDevelopment(structuredClone({ matchId, summary: { ...source.summary,
    focusParticipantId: focus, compareParticipantId: compare ?? null, win: focal.win,
    kills: focal.kills, deaths: focal.deaths, assists: focal.assists, totalCs: focal.totalCs, goldEarned: focal.goldEarned },
    roster: source.roster, teams: source.teams, timelineAvailable: true, samples,
    ...windows(samples, focal.championName), events: source.events,
  }));
}
