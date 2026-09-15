import type { MatchSummary } from "./types";

export type PerformanceTotals = {
  games: number; wins: number; losses: number; winRate: number | null;
  averageKills: number | null; averageDeaths: number | null; averageAssists: number | null;
  kda: number | null; deathless: boolean; csPerMinute: number | null;
};
export type ChampionPerformanceRow = PerformanceTotals & {
  championId: number; championName: string; gameVersion: string;
};
export type MatchPerformance = PerformanceTotals & {
  remakes: number; unknown: number; champions: ChampionPerformanceRow[];
};

function totals(matches: MatchSummary[]): PerformanceTotals {
  const games = matches.length;
  let wins = 0, kills = 0, deaths = 0, assists = 0, cs = 0, seconds = 0;
  for (const match of matches) {
    wins += Number(match.win);
    kills += match.kills; deaths += match.deaths; assists += match.assists;
    if (match.durationSeconds > 0) { cs += match.cs; seconds += match.durationSeconds; }
  }
  return {
    games, wins, losses: games - wins, winRate: games ? wins / games * 100 : null,
    averageKills: games ? kills / games : null, averageDeaths: games ? deaths / games : null,
    averageAssists: games ? assists / games : null, kda: deaths ? (kills + assists) / deaths : null,
    deathless: games > 0 && deaths === 0, csPerMinute: seconds ? cs * 60 / seconds : null,
  };
}

/** Summarizes this history selection, without asserting that it covers a season or a complete recent window. */
export function summarizePerformance(matches: MatchSummary[]): MatchPerformance {
  const unique = [...new Map(matches.map(match => [match.matchId, match])).values()];
  const counted = unique.filter(match => match.remake === false);
  const groups = new Map<number, MatchSummary[]>();
  for (const match of counted) {
    const group = groups.get(match.championId) ?? [];
    group.push(match); groups.set(match.championId, group);
  }
  const champions = [...groups.values()].map(group => {
    const latest = group.reduce((a, b) => a.startedAtMs >= b.startedAtMs ? a : b);
    return { championId: latest.championId, championName: latest.championName, gameVersion: latest.gameVersion, ...totals(group) };
  }).sort((a, b) => b.games - a.games || a.championName.localeCompare(b.championName) || a.championId - b.championId);
  return { ...totals(counted), remakes: unique.filter(match => match.remake === true).length,
    unknown: unique.filter(match => match.remake !== true && match.remake !== false).length, champions };
}
