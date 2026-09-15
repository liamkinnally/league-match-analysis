"use client";

import type { GameAssetCatalog } from "../lib/game-assets/types";
import { summarizePerformance, type ChampionPerformanceRow } from "../lib/player-lookup/performance";
import type { MatchSummary } from "../lib/player-lookup/types";
import { GameAssetIcon } from "./game-asset-icon";
import "./player-performance.css";

type Props = { matches: MatchSummary[]; catalogs: Record<string, GameAssetCatalog | null> };
const decimal = (value: number | null) => value === null ? "—" : value.toFixed(1);
const ratio = (row: { kda: number | null; deathless: boolean }) => row.deathless ? "Deathless" : row.kda === null ? "—" : `${row.kda.toFixed(2)}:1`;
const rate = (value: number | null) => value === null ? "—" : `${Math.round(value)}%`;

function ChampionIcon({ row, catalogs }: { row: ChampionPerformanceRow; catalogs: Props["catalogs"] }) {
  return <GameAssetIcon className="player-performance__portrait" fallback={row.championName.slice(0, 2)} asset={catalogs[row.gameVersion]?.champions[String(row.championId)]} />;
}

export function RecentPerformance({ matches, catalogs }: Props) {
  const result = summarizePerformance(matches);
  return <section className="player-performance" aria-label="Recent performance">
    <header className="player-performance__heading"><h2>Recent performance</h2><span>{result.games} {result.games === 1 ? "game" : "games"} in this history</span></header>
    {result.games > 0 ? <div className="player-performance__body">
      <div className="player-performance__record">
        <div className="player-performance__ring">
          <svg viewBox="0 0 108 108" aria-hidden="true"><circle className="player-performance__ring-base" cx="54" cy="54" r="46" />
            <circle className="player-performance__ring-wins" cx="54" cy="54" r="46" pathLength="100" strokeDasharray={`${result.winRate} 100`} /></svg>
          <div><strong>{rate(result.winRate)}</strong><span>Win rate</span></div>
        </div>
        <p><strong>{result.wins}W</strong><span> / </span><strong>{result.losses}L</strong></p>
      </div>
      <div className="player-performance__averages">
        <span className="player-performance__label">KDA</span><strong className="player-performance__kda">{ratio(result)}</strong>
        <p aria-label="Average kills, deaths, and assists">{decimal(result.averageKills)} <span>/</span> {decimal(result.averageDeaths)} <span>/</span> {decimal(result.averageAssists)}</p>
        <small>{decimal(result.csPerMinute)} CS / min</small>
      </div>
      <div className="player-performance__champions"><span className="player-performance__label">Most played</span>
        <ul>{result.champions.slice(0, 3).map(row => <li key={row.championId}>
          <ChampionIcon row={row} catalogs={catalogs} /><div><strong>{row.championName}</strong><small>{row.games} {row.games === 1 ? "game" : "games"}</small></div>
          <div className="player-performance__champion-result"><strong>{rate(row.winRate)}</strong><small>{row.wins}W / {row.losses}L</small></div>
        </li>)}</ul>
      </div>
    </div> : <p className="player-performance__empty">No completed results to summarize yet.</p>}
    {(result.remakes > 0 || result.unknown > 0) && <p className="player-performance__note">
      {result.remakes > 0 && `${result.remakes} ${result.remakes === 1 ? "remake" : "remakes"} excluded.`}
      {result.remakes > 0 && result.unknown > 0 && " "}
      {result.unknown > 0 && `${result.unknown} ${result.unknown === 1 ? "result unavailable" : "results unavailable"}.`}
    </p>}
  </section>;
}

export function ChampionPerformance({ matches, catalogs }: Props) {
  const result = summarizePerformance(matches);
  return <section className="player-champions" aria-label="Champion performance">
    <header className="player-performance__heading"><h2>Champions</h2><span>This history</span></header>
    {result.champions.length ? <table>
      <thead><tr><th scope="col">Champion</th><th scope="col">KDA</th><th scope="col">Games</th><th scope="col">Win rate</th></tr></thead>
      <tbody>{result.champions.slice(0, 6).map(row => <tr key={row.championId}>
        <th scope="row"><div><ChampionIcon row={row} catalogs={catalogs} /><span>{row.championName}</span></div></th>
        <td><strong>{row.deathless ? "—" : row.kda?.toFixed(1) ?? "—"}</strong><small>{row.deathless ? "Deathless" : `${decimal(row.csPerMinute)} CS/m`}</small></td>
        <td>{row.games}</td><td>{rate(row.winRate)}</td>
      </tr>)}</tbody>
    </table> : <p className="player-performance__empty">Champion stats will appear with completed results.</p>}
  </section>;
}
