"use client";

import Link from "next/link";
import { PlayerProfileHeader, PlayerProfilePanel } from "./player-profile";
import { usePlayerProfile } from "../lib/player-lookup/use-player-profile";
import { useEffect, useState } from "react";
import { GameAssetIcon } from "./game-asset-icon";
import { FinalItemSlots } from "./final-item-slots";
import { HistorySkeleton, LoadingStatus } from "./loading-state";
import { developmentHref } from "../lib/development/route";
import { queueLabel, roleLabel } from "../lib/development/results";
import { useGameAssetCatalogs } from "../lib/game-assets/use-game-assets";
import { usePlayerLookup } from "../lib/player-lookup/use-player-lookup";
import { matchResult } from "../lib/match-result";

import { RecentPerformance, ChampionPerformance } from "./player-performance";
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
  const profileState = usePlayerProfile(identityKnown && lookup ? { runId: lookup.runId, gameName: lookup.gameName, tagLine: lookup.tagLine, updatedAt: lookup.lastUpdated, historyStatus: lookup.status } : null);
  const failed = !issue && lookup?.status === "FAILED";
  const showIssueBySearch = count === 0 && Boolean(failed || issue);

  const historyStatus = (<div className="player-lookup__status">
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
          </div> : lookup ? <p role="status">{count} {count === 1 ? "match" : "matches"}</p> : null}
        {retryNotBefore && <p>Try again after <time dateTime={retryNotBefore}>{new Date(retryNotBefore).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", second: "2-digit" })}</time>.</p>}
      </div>);

  return <section className="player-lookup" aria-label="Player lookup">
    {!identityKnown && <h1 className="profile-sr-only">Find a player’s match history</h1>}
    <form className="player-search" onSubmit={(event) => {
      event.preventDefault(); void submit({ gameName: gameName.trim(), tagLine: tagLine.trim(), queueId: 0 });
    }}>
      <label>Game name<input name="gameName" placeholder="ex. Doublelift" maxLength={64} required autoComplete="off"
        autoCapitalize="none" spellCheck={false} value={gameName} onChange={event => setEditedName(event.target.value)} /></label>
      <label className="player-search__tag">Tag line<input name="tagLine" placeholder="NA01" maxLength={16} required autoComplete="off"
        autoCapitalize="none" spellCheck={false} value={tagLine} onChange={event => setEditedTag(event.target.value)} /></label>
      <button type="submit" disabled={submitting}>{submitting ? "Finding matches…" : "Find matches"}<span aria-hidden="true">→</span></button>
    </form>
    {showIssueBySearch && historyStatus}

    {(lookup || busy || issue) && <div className="player-lookup__results">
      {lookup && identityKnown && <PlayerProfileHeader identity={{ gameName: lookup.gameName, tagLine: lookup.tagLine }} {...profileState}>
        <button className="entry-button profile-update" type="button" disabled={busy || profileState.loadingRecent || remaining > 0} onClick={refresh}>{operation === "refresh" ? "Updating…" : "Update"}</button>
        {remaining > 0 ? <small>Update in {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}</small> : lookup.lastUpdated && <small>Updated <time dateTime={lookup.lastUpdated}>{new Date(lookup.lastUpdated).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}</time></small>}
      </PlayerProfileHeader>}
      <div className={identityKnown ? "profile-layout" : undefined}>
        {identityKnown && <aside className="profile-sidebar" aria-label="Player ranks and champions">
          <PlayerProfilePanel {...profileState} now={now} />
          {lookup && <ChampionPerformance matches={lookup.matches} catalogs={catalogs} />}
        </aside>}
        <div className="profile-history">
          {lookup && identityKnown && <RecentPerformance matches={lookup.matches} catalogs={catalogs} />}
          {lookup && <div className="player-history__controls">
            <h2>Match history</h2>
            <label className="player-history__filter">Queue Type<select name="queueId" value={lookup.queueId}
              disabled={busy || !identityKnown} onChange={event => filter(Number(event.target.value))}>
              <option value={0}>All queues</option>
              {historyQueues.map(id => <option key={id} value={id}>{queueLabel(id)}</option>)}
              {[430, 490].includes(lookup.queueId) && <option value={lookup.queueId}>{queueLabel(lookup.queueId)}</option>}
            </select></label>
          </div>}
          {!showIssueBySearch && historyStatus}
          {busy && count === 0 && <HistorySkeleton />}
          {lookup && count > 0 && <ul className="player-history" aria-label="Recent match history">
            {lookup.matches.map((match, index) => {
              const assets = catalogs[match.gameVersion];
              const champion = assets?.champions[String(match.championId)];
              const result = matchResult(match.win);
              const label = match.remake === true ? "Remake" : result.label;
              const date = new Date(match.startedAtMs).toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
              const previous = lookup.matches[index - 1];
              const newDay = !previous || new Date(previous.startedAtMs).toISOString().slice(0, 10) !== new Date(match.startedAtMs).toISOString().slice(0, 10);
              const kda = match.deaths ? ((match.kills + match.assists) / match.deaths).toFixed(2) : "Perfect";
              return <li key={match.matchId}>
                {newDay && <h3 className="profile-history__day"><time dateTime={new Date(match.startedAtMs).toISOString().slice(0, 10)}>{date}</time></h3>}
                <Link className={`player-history__row profile-match match-result ${match.remake === true ? "profile-match--remake" : result.className}`}
                  title={match.timelineAvailable ? "Timeline available" : "Timeline loads when opened"}
                  href={developmentHref(match.matchId, match.participantId, undefined, undefined, undefined, { historyRunId: lookup.runId })} aria-label={`${match.championName} ${label.toLowerCase()} match development`}>
                  <div className="profile-match__result"><small>{queueLabel(match.queueId)}</small><strong>{label}</strong><span>{Math.floor(match.durationSeconds / 60)}:{String(match.durationSeconds % 60).padStart(2, "0")}</span></div>
                  <div className="profile-match__champion"><GameAssetIcon asset={champion} fallback={match.championName.slice(0, 2)} className="development-champion-mark" /><div><strong>{champion?.name ?? match.championName}</strong>{match.queueId !== 450 && <small>{roleLabel(match.position)}</small>}</div></div>
                  <div className="profile-match__kda"><strong>{match.kills} <span>/</span> <em>{match.deaths}</em> <span>/</span> {match.assists}</strong><small>{kda} KDA</small></div>
                  <div className="profile-match__stats"><span>{formatNumber(match.cs)} <small>CS</small> <small>({match.durationSeconds ? (match.cs * 60 / match.durationSeconds).toFixed(1) : "—"}/m)</small></span><small>{formatNumber(match.gold)} gold</small></div>
                  <FinalItemSlots itemIds={match.endItemIds} assets={assets ?? null} className="profile-match__items" />
                  <span className="profile-match__open" aria-hidden="true">→</span>
                </Link>
              </li>;
            })}
          </ul>}
          {lookup && <div className="player-history__pagination">
            {restoreCursor ? <button className="entry-button" disabled={busy} onClick={restoreMore}>Restore earlier pages</button>
              : lookup.hasMore ? <button className="entry-button" disabled={busy} onClick={older}>{operation === "older" ? "Loading older matches…" : "Load older matches"}</button>
                : !busy && (lookup.status === "COMPLETE" || lookup.status === "EMPTY") ? <p>{lookup.queueId === 0 ? "End of supported match history." : "End of match history for this queue."}</p> : null}
          </div>}
        </div>
      </div>
    </div>}

  </section>;
}
