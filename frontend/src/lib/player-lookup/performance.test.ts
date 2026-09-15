import { describe, expect, it } from "vitest";
import type { MatchSummary } from "./types";
import { summarizePerformance } from "./performance";

const match = (id: number, values: Partial<MatchSummary> = {}): MatchSummary => ({
  matchId: `NA1_${id}`, queueId: 420, participantId: 1, championId: 86, championName: "Garen",
  gameVersion: "16.18.1", endItemIds: [], position: "TOP", win: true, remake: false,
  startedAtMs: 100_000 + id, durationSeconds: 1200, kills: 8, deaths: 4, assists: 4,
  cs: 100, gold: 8000, timelineAvailable: true, ...values,
});

describe("displayed match performance", () => {
  it("excludes remakes and unknown results from every denominator", () => {
    const result = summarizePerformance([
      match(1), match(2, { win: false, kills: 2, deaths: 6, assists: 6, cs: 300, durationSeconds: 2400 }),
      match(3, { remake: true, kills: 50, deaths: 0, cs: 50 }),
      match(4, { remake: null, championId: 122, championName: "Darius", kills: 90, deaths: 0 }),
    ]);
    expect(result).toMatchObject({ games: 2, wins: 1, losses: 1, winRate: 50, remakes: 1, unknown: 1,
      averageKills: 5, averageDeaths: 5, averageAssists: 5, kda: 2 });
    expect(result.csPerMinute).toBeCloseTo(400 / 60);
    expect(result.champions).toHaveLength(1);
    expect(result.champions[0]).toMatchObject({ championId: 86, games: 2, wins: 1, losses: 1, kda: 2 });
  });

  it("deduplicates overlapping pages and groups a champion across patches", () => {
    const first = match(1, { championId: 122, championName: "Darius", gameVersion: "16.17.1" });
    const result = summarizePerformance([
      first, first,
      match(2, { championId: 122, championName: "Darius", gameVersion: "16.18.1", win: false }),
      match(3),
    ]);
    expect(result.games).toBe(3);
    expect(result.champions.map(row => [row.championId, row.games])).toEqual([[122, 2], [86, 1]]);
    expect(result.champions[0].gameVersion).toBe("16.18.1");
  });

  it("keeps missing outcomes and deathless or zero-duration ratios distinct from zero", () => {
    expect(summarizePerformance([])).toMatchObject({ games: 0, winRate: null, kda: null, csPerMinute: null, averageKills: null });
    expect(summarizePerformance([match(1, { remake: null })])).toMatchObject({ games: 0, unknown: 1, winRate: null });
    expect(summarizePerformance([match(1, { deaths: 0, durationSeconds: 0 })])).toMatchObject({ games: 1, kda: null, csPerMinute: null, deathless: true });
    expect(summarizePerformance([match(1, { kills: 0, assists: 0 })])).toMatchObject({ kda: 0, deathless: false });
  });
});
