"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { GameAssetIcon } from "./game-asset-icon";
import { FinalItemSlots } from "./final-item-slots";
import { HistorySkeleton, LoadingStatus } from "./loading-state";
import { developmentHref } from "../lib/development/route";
import { queueLabel, roleLabel } from "../lib/development/results";
import { useGameAssetCatalogs } from "../lib/game-assets/use-game-assets";
import { usePlayerLookup } from "../lib/player-lookup/use-player-lookup";
import { matchResult } from "../lib/match-result";

import { historyQueues } from "../lib/player-lookup/types";

const formatNumber = (n: number) => n.toLocaleString("en-US");

export default function PlayerSearch({ initialRunId }: { initialRunId?: string }) {
  const { lookup, issue, submitting, loading, retryNotBefore, submit, retry, older, refresh, filter, busy: operation, restoreCursor, restoreMore } = usePlayerLookup(initialRunId);
  const [editedName, setEditedName] = useState<string | null>(null);
  const [editedTag, setEditedTag] = useState<string | null>(null);
  const [now, setNow] = useState(0);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    tick(); const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, []);
  const nextRefresh = Math.max(Date.parse(lookup?.nextRefreshAt ?? "") || 0, Date.parse(retryNotBefore ?? "") || 0);
  const remaining = Math.max(0, Math.ceil((nextRefresh - now) / 1000));
  const gameName = editedName ?? lookup?.gameName ?? "";
  const tagLine = editedTag ?? lookup?.tagLine ?? "";
  const catalogs = useGameAssetCatalogs(lookup?.matches.map((match) => match.gameVersion) ?? []);
  const running = !issue && lookup?.status === "RUNNING";
  const count = lookup?.matches.length ?? 0;
  const busy = Boolean(operation) || submitting || loading || running;
  const identityKnown = Boolean(lookup?.gameName && lookup?.tagLine);
  const failed = !issue && lookup?.status === "FAILED";

  return <section className="player-lookup" aria-label="Player lookup">
    <form className="player-search" onSubmit={(event) => {
      event.preventDefault(); void submit({ gameName: gameName.trim(), tagLine: tagLine.trim(), queueId: 0 });
    }}>
      <label>Game name<input name="gameName" placeholder="Game name" maxLength={64} required autoComplete="off"
        autoCapitalize="none" spellCheck={false} value={gameName} onChange={event => setEditedName(event.target.value)} /></label>
      <label className="player-search__tag">Tag line<input name="tagLine" placeholder="NA1" maxLength={16} required autoComplete="off"
        autoCapitalize="none" spellCheck={false} value={tagLine} onChange={event => setEditedTag(event.target.value)} /></label>
      <button type="submit" disabled={submitting}>{submitting ? "Finding matches…" : "Find matches"}<span aria-hidden="true">→</span></button>
    </form>

    {(lookup || busy || issue) && <div className="player-lookup__results">
      {lookup && <div className="player-lookup__identity">
        <h2 title={identityKnown ? `${lookup.gameName}#${lookup.tagLine}` : undefined}>{identityKnown
          ? <>{lookup.gameName}<span>#{lookup.tagLine}</span></> : "Player lookup"}</h2>
        <span>{queueLabel(lookup.queueId)} · Match history</span>
      </div>}
      {lookup && <div className="player-history__controls">
        <label className="player-history__filter">Queue Type<select name="queueId" value={lookup.queueId}
          disabled={busy || !identityKnown} onChange={event => filter(Number(event.target.value))}>
          <option value={0}>All queues</option>
          {historyQueues.map(id => <option key={id} value={id}>{queueLabel(id)}</option>)}
          {[430, 490].includes(lookup.queueId) && <option value={lookup.queueId}>{queueLabel(lookup.queueId)}</option>}
        </select></label>
        <p>Last updated: {lookup.lastUpdated ? <time dateTime={lookup.lastUpdated}>{new Date(lookup.lastUpdated).toLocaleString("en-US")}</time> : "Not yet completed"}</p>
        <button className="entry-button" type="button" disabled={busy || remaining > 0} onClick={refresh}>{operation === "refresh" ? "Updating…" : "Update"}</button>
        {remaining > 0 && <span>Update in {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}</span>}
      </div>}
      <div className="player-lookup__status">
        {issue ? <div className="entry-notice"><p role="alert">{issue.message}</p>
          {issue.retryable && <button className="entry-button" type="button" onClick={retry}>Retry loading</button>}
          {count > 0 && <p>Previously loaded matches are still available below.</p>}
        </div> : busy ? <LoadingStatus
          title={submitting ? "Starting player lookup…" : operation === "filter" ? "Filtering match history…" : operation === "older" ? "Loading older matches…" : operation === "refresh" ? "Updating match history…" : loading ? "Loading match history…" : "Fetching recent matches…"}
          description={count > 0 ? `${count} ${count === 1 ? "match is" : "matches are"} ready to open while the lookup continues.`
            : "Loading up to 20 matches per page. This can take a moment."} />
          : lookup?.status === "EMPTY" && count === 0 ? <div className="entry-notice" role="status">
            <strong>{lookup.hasMore ? "No supported matches on this page" : "No recent matches"}</strong>
            <p>{lookup.hasMore ? "Load older matches to continue through this player's history."
              : lookup.queueId === 0 ? "No supported matches found in this history snapshot." : `No ${queueLabel(lookup.queueId)} matches found on NA1.`}</p>
            {!lookup.hasMore && <p>Check the Riot ID or search for another player.</p>}
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
          <div className="player-history__result"><small>{queueLabel(match.queueId)}</small><strong>{result.label}</strong>
            <time dateTime={new Date(match.startedAtMs).toISOString()}>{new Date(match.startedAtMs).toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" })}</time>
            <span>{Math.floor(match.durationSeconds / 60)}:{String(match.durationSeconds % 60).padStart(2, "0")}</span></div>
          <div className="player-history__champion"><GameAssetIcon asset={champion} fallback={match.championName.slice(0, 2)} className="development-champion-mark" />
            <div><strong>{champion?.name ?? match.championName}</strong>{match.queueId !== 450 && <small>{roleLabel(match.position)}</small>}</div>
            <span className="player-history__mobile-open" aria-hidden="true">→</span></div>
          <div className="player-history__kda"><strong>{match.kills} / {match.deaths} / {match.assists}</strong><small>K / D / A</small></div>
          <div className="player-history__stats"><span>{formatNumber(match.cs)} <small>CS</small></span><span>{formatNumber(match.gold)} <small>gold</small></span></div>
          <FinalItemSlots itemIds={match.endItemIds} assets={assets ?? null} className="player-history__items" />
          <div className="player-history__open"><span>Match development →</span><small>{match.timelineAvailable ? "Timeline available" : "Timeline loads when opened"}</small></div>
        </Link></li>;
      })}
    </ul>}
    {lookup && <div className="player-history__pagination">
      {restoreCursor ? <button className="entry-button" disabled={busy} onClick={restoreMore}>Restore earlier loaded pages</button>
        : lookup.hasMore ? <button className="entry-button" disabled={busy} onClick={older}>{operation === "older" ? "Loading older matches…" : "Load older matches"}</button>
          : !busy && (lookup.status === "COMPLETE" || lookup.status === "EMPTY") ? <p>{lookup.queueId === 0 ? "End of supported match history." : "End of match history for this queue."}</p> : null}
    </div>}
  </section>;
}
