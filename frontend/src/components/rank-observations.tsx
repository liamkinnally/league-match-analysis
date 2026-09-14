import type { PlayerProfile, RankObservation } from "../lib/player-lookup/profile";

export function rankValue(value: Pick<RankObservation, "status" | "tier" | "division" | "leaguePoints">): string {
  if (value.status === "unranked") return "Unranked";
  if (value.tier === null || value.division === null || value.leaguePoints === null) return "Rank unavailable";
  const tier = value.tier.slice(0, 1) + value.tier.slice(1).toLowerCase();
  const division = ["MASTER", "GRANDMASTER", "CHALLENGER"].includes(value.tier) ? "" : ` ${value.division}`;
  return `${tier}${division} · ${value.leaguePoints} LP`;
}
export function ProfileTime({ value }: { value: string }) {
  return <time dateTime={value}>{new Date(value).toLocaleString("en-US", { month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit" })}</time>;
}
export function RankObservations({ history, busy, loadOlder }: { history: PlayerProfile["rankHistory"]; busy: boolean; loadOlder: () => void }) {
  return <details className="player-rank-observations">
    <summary>View rank observations <span>{history.observations.length} loaded</span></summary>
    <p>Recorded snapshots only. Gaps and ranked resets may occur between observations; changes are not attributed to individual matches.</p>
    {history.observations.length ? <ol aria-label="Observed Solo/Duo ranks">{history.observations.map(row => <li key={row.id}>
      <ProfileTime value={row.observedAt} /><strong>{rankValue(row)}</strong>
      {row.wins !== null && row.losses !== null && <span>{row.wins}W–{row.losses}L</span>}
    </li>)}</ol> : <p>No rank observations recorded yet.</p>}
    {history.nextCursor && <button type="button" className="entry-button" disabled={busy} onClick={loadOlder}>{busy ? "Loading observations…" : "Load older observations"}</button>}
  </details>;
}
