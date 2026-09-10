"use client";

import Link from "next/link";
import { useId, useState } from "react";
import { GameAssetIcon } from "./game-asset-icon";
import { FinalItemSlots } from "./final-item-slots";
import { HistorySkeleton, LoadingStatus } from "./loading-state";
import { developmentHref } from "../lib/development/route";
import { roleLabel } from "../lib/development/results";
import { useGameAssetCatalogs } from "../lib/game-assets/use-game-assets";
import { usePlayerLookup } from "../lib/player-lookup/use-player-lookup";
import { matchResult } from "../lib/match-result";

const formatNumber = (n: number) => n.toLocaleString("en-US");

export default function PlayerSearch({ initialRunId }: { initialRunId?: string }) {
  const { lookup, issue, submitting, loading, retryNotBefore, submit, retry } = usePlayerLookup(initialRunId);
  const [editedName, setEditedName] = useState<string | null>(null);
  const [editedTag, setEditedTag] = useState<string | null>(null);
  const scopeId = useId();
  const gameName = editedName ?? lookup?.gameName ?? "";
  const tagLine = editedTag ?? lookup?.tagLine ?? "";
  const catalogs = useGameAssetCatalogs(lookup?.matches.map((match) => match.gameVersion) ?? []);
  const running = !issue && lookup?.status === "RUNNING";
  const count = lookup?.matches.length ?? 0;
  const busy = submitting || loading || running;
  const failed = !issue && lookup?.status === "FAILED";

  return <section className="player-lookup" aria-label="Player lookup">
    <form className="player-search" aria-describedby={scopeId} onSubmit={(event) => {
      event.preventDefault(); void submit({ gameName: gameName.trim(), tagLine: tagLine.trim() });
    }}>
      <label>Game name<input name="gameName" placeholder="Game name" maxLength={64} required autoComplete="off"
        autoCapitalize="none" spellCheck={false} value={gameName} onChange={event => setEditedName(event.target.value)} /></label>
      <label className="player-search__tag">Tag line<input name="tagLine" placeholder="NA1" maxLength={16} required autoComplete="off"
        autoCapitalize="none" spellCheck={false} value={tagLine} onChange={event => setEditedTag(event.target.value)} /></label>
      <button type="submit" disabled={submitting}>{submitting ? "Finding matches…" : "Find matches"}<span aria-hidden="true">→</span></button>
    </form>
    <p id={scopeId} className="player-search__scope">NA1 — Ranked Solo/Duo — Latest five matches</p>

    {(lookup || busy || issue) && <div className="player-lookup__results">
      {lookup && <div className="player-lookup__identity">
        <h2 title={`${lookup.gameName}#${lookup.tagLine}`}>{lookup.gameName}<span>#{lookup.tagLine}</span></h2>
        <span>Recent matches</span>
      </div>}
      <div className="player-lookup__status">
        {issue ? <div className="entry-notice"><p role="alert">{issue.message}</p>
          {issue.retryable && <button className="entry-button" type="button" onClick={retry}>Retry loading</button>}
          {count > 0 && <p>Previously loaded matches are still available below.</p>}
        </div> : busy ? <LoadingStatus
          title={submitting ? "Starting player lookup…" : loading ? "Loading match history…" : "Fetching recent matches…"}
          description={count > 0 ? `${count} ${count === 1 ? "match is" : "matches are"} ready to open while the lookup continues.`
            : "This can take a moment. Matches will appear as they are ready."} />
          : lookup?.status === "EMPTY" ? <div className="entry-notice" role="status">
            <strong>No recent matches</strong><p>No recent ranked Solo/Duo matches found on NA1.</p>
            <p>Check the Riot ID or search for another player.</p>
          </div> : failed || lookup?.status === "PARTIAL" ? <div className="entry-notice" role="status">
            <strong>{failed ? "Lookup could not finish" : "Some matches are unavailable"}</strong>
            <p>{lookup?.message ?? (failed ? "Search again or explore the sample match." : "Completed matches are ready to open below.")}</p>
          </div> : lookup ? <p role="status">{count} recent {count === 1 ? "match" : "matches"} — Ready to review</p> : null}
        {retryNotBefore && <p>Try again after <time dateTime={retryNotBefore}>{new Date(retryNotBefore).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", second: "2-digit" })}</time>.</p>}
      </div>
      {busy && count === 0 && <HistorySkeleton />}
    </div>}

    {lookup && count > 0 && <ul className="player-history" aria-label="Recent match history">
      {lookup.matches.map((match) => {
        const assets = catalogs[match.gameVersion];
        const champion = assets?.champions[String(match.championId)];
        const result = matchResult(match.win);
        return <li key={match.matchId}><Link className={`player-history__row match-result ${result.className}`}
          href={developmentHref(match.matchId, match.participantId)} aria-label={`${match.championName} ${result.label.toLowerCase()} match development`}>
          <div className="player-history__result"><small>Ranked Solo/Duo</small><strong>{result.label}</strong>
            <time dateTime={new Date(match.startedAtMs).toISOString()}>{new Date(match.startedAtMs).toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" })}</time>
            <span>{Math.floor(match.durationSeconds / 60)}:{String(match.durationSeconds % 60).padStart(2, "0")}</span></div>
          <div className="player-history__champion"><GameAssetIcon asset={champion} fallback={match.championName.slice(0, 2)} className="development-champion-mark" />
            <div><strong>{champion?.name ?? match.championName}</strong><small>{roleLabel(match.position)}</small></div>
            <span className="player-history__mobile-open" aria-hidden="true">→</span></div>
          <div className="player-history__kda"><strong>{match.kills} / {match.deaths} / {match.assists}</strong><small>K / D / A</small></div>
          <div className="player-history__stats"><span>{formatNumber(match.cs)} <small>CS</small></span><span>{formatNumber(match.gold)} <small>gold</small></span></div>
          <FinalItemSlots itemIds={match.endItemIds} assets={assets ?? null} className="player-history__items" />
          <div className="player-history__open"><span>Match development →</span><small>{match.timelineAvailable ? "Timeline available" : "Timeline unavailable — final stats only"}</small></div>
        </Link></li>;
      })}
    </ul>}
  </section>;
}
