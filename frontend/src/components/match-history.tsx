import Link from "next/link";
import type { GameAssetCatalog } from "../lib/game-assets/types";
import { queueLabel, roleLabel } from "../lib/development/results";
import { matchResult } from "../lib/match-result";
import { historyQueues, type MatchSummary } from "../lib/player-lookup/types";
import { FinalItemSlots } from "./final-item-slots";
import { GameAssetIcon } from "./game-asset-icon";

const formatNumber = (n: number) => n.toLocaleString("en-US");
const matchTime = new Intl.DateTimeFormat("en-US", {
  month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit",
  timeZone: "UTC", timeZoneName: "short",
});

function resultLabel(match: MatchSummary) {
  return match.remake === true ? "Remake" : matchResult(match.win).label;
}

export function HistoryControls({ queueId, disabled = false, onChange }: {
  queueId: number; disabled?: boolean; onChange: (queueId: number) => void;
}) {
  return <div className="player-history__controls">
    <h2 id="match-history" tabIndex={-1}>Match history</h2>
    <label className="player-history__filter">Queue Type<select name="queueId" value={queueId}
      disabled={disabled} onChange={event => onChange(Number(event.target.value))}>
      <option value={0}>All queues</option>
      {historyQueues.map(id => <option key={id} value={id}>{queueLabel(id)}</option>)}
      {[430, 490].includes(queueId) && <option value={queueId}>{queueLabel(queueId)}</option>}
    </select></label>
  </div>;
}

export function MatchHistory({ matches, catalogs, hrefFor }: {
  matches: MatchSummary[];
  catalogs: Record<string, GameAssetCatalog | null>;
  hrefFor: (match: MatchSummary) => string;
}) {
  const names = matches.map(match => `${match.championName} ${resultLabel(match).toLowerCase()} match development`);
  const seen = new Set<string>(), repeated = new Set<string>();
  for (const name of names) {
    if (seen.has(name)) repeated.add(name);
    seen.add(name);
  }
  return <ul className="player-history" aria-label="Recent match history">
    {matches.map((match, index) => {
      const assets = catalogs[match.gameVersion];
      const champion = assets?.champions[String(match.championId)];
      const result = matchResult(match.win);
      const label = resultLabel(match);
      const name = names[index];
      const date = new Date(match.startedAtMs).toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
      const previous = matches[index - 1];
      const newDay = !previous || new Date(previous.startedAtMs).toISOString().slice(0, 10) !== new Date(match.startedAtMs).toISOString().slice(0, 10);
      const kda = match.deaths ? ((match.kills + match.assists) / match.deaths).toFixed(2) : "Perfect";
      return <li key={match.matchId}>
        {newDay && <h3 className="profile-history__day"><time dateTime={new Date(match.startedAtMs).toISOString().slice(0, 10)}>{date}</time></h3>}
        <Link className={`player-history__row profile-match match-result ${match.remake === true ? "profile-match--remake" : result.className}`}
          title={match.timelineAvailable ? "Timeline available" : "Timeline loads when opened"}
          href={hrefFor(match)} aria-label={repeated.has(name) ? `${name}, ${matchTime.format(match.startedAtMs)}, game ${index + 1}` : name}>
          <div className="profile-match__result"><small>{queueLabel(match.queueId)}</small><strong>{label}</strong><span>{Math.floor(match.durationSeconds / 60)}:{String(match.durationSeconds % 60).padStart(2, "0")}</span></div>
          <div className="profile-match__champion"><GameAssetIcon asset={champion} fallback={match.championName.slice(0, 2)} className="development-champion-mark" /><div><strong>{champion?.name ?? match.championName}</strong>{match.queueId !== 450 && <small>{roleLabel(match.position)}</small>}</div></div>
          <div className="profile-match__kda"><strong>{match.kills} <span>/</span> <em>{match.deaths}</em> <span>/</span> {match.assists}</strong><small>{kda} KDA</small></div>
          <div className="profile-match__stats"><span>{formatNumber(match.cs)} <small>CS</small> <small>({match.durationSeconds ? (match.cs * 60 / match.durationSeconds).toFixed(1) : "—"}/m)</small></span><small>{formatNumber(match.gold)} gold</small></div>
          <FinalItemSlots itemIds={match.endItemIds} assets={assets ?? null} className="profile-match__items" />
          <span className="profile-match__open" aria-hidden="true">→</span>
        </Link>
      </li>;
    })}
  </ul>;
}
