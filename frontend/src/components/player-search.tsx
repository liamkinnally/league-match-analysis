"use client";

import { PlayerSearchForm } from "./player-search-form";
import { regionFor, type PlayerIdentity } from "../lib/player-lookup/regions";
import { PlayerProfileHeader, PlayerProfilePanel } from "./player-profile";
import { usePlayerProfile } from "../lib/player-lookup/use-player-profile";
import { useEffect, useState, type ReactNode } from "react";
import { HistoryControls, MatchHistory } from "./match-history";
import { HistorySkeleton } from "./loading-state";
import { developmentHref } from "../lib/development/route";
import { queueLabel } from "../lib/development/results";
import { useGameAssetCatalogs } from "../lib/game-assets/use-game-assets";
import { usePlayerLookup } from "../lib/player-lookup/use-player-lookup";

import { RecentPerformance, ChampionPerformance } from "./player-performance";

function HistoryUpdate({ nextRefreshAt, retryNotBefore, lastUpdated, busy, updating, onRefresh }: {
  nextRefreshAt: string | null; retryNotBefore?: string | null; lastUpdated: string | null;
  busy: boolean; updating: boolean; onRefresh: () => void;
}) {
  const [now, setNow] = useState(0);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    tick(); const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, []);
  const nextRefresh = Math.max(Date.parse(nextRefreshAt ?? "") || 0, Date.parse(retryNotBefore ?? "") || 0);
  const remaining = Math.max(0, Math.ceil((nextRefresh - now) / 1000));
  return <>
    <button className="entry-button profile-update" type="button" disabled={busy || remaining > 0} onClick={onRefresh}>{updating ? "Updating…" : "Update"}</button>
    {remaining > 0 ? <small>Update in {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}</small> : lastUpdated && <small>Updated <time dateTime={lastUpdated}>{new Date(lastUpdated).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}</time></small>}
  </>;
}

export default function PlayerSearch({ initialRunId, initialIdentity, children }: { initialRunId?: string; initialIdentity?: PlayerIdentity; children?: ReactNode }) {
  const { lookup, issue, submitting, loading, retryNotBefore, submit, retry, older, refresh, filter, busy: operation, restoreCursor, restoreMore } = usePlayerLookup(initialRunId, initialIdentity);
  const catalogs = useGameAssetCatalogs(lookup?.matches.map((match) => match.gameVersion) ?? []);
  const running = !issue && lookup?.status === "RUNNING";
  const count = lookup?.matches.length ?? 0;
  const busy = Boolean(operation) || submitting || loading || running;
  const identityKnown = Boolean(lookup?.gameName && lookup?.tagLine);
  const profileState = usePlayerProfile(identityKnown && lookup ? { runId: lookup.runId, gameName: lookup.gameName, tagLine: lookup.tagLine, platform: lookup.platform, updatedAt: lookup.lastUpdated, historyStatus: lookup.status } : null);
  const failed = !issue && lookup?.status === "FAILED";

  const historyStatus = (<div className={`player-lookup__status player-lookup__status--search${identityKnown ? " player-lookup__status--profile" : ""}`}>
        {issue ? <div className="entry-notice"><p role="alert">{issue.message}</p>
          {issue.retryable && <button className="entry-button" type="button" onClick={retry}>Retry loading</button>}
          {count > 0 && <p>Previously loaded matches are still available below.</p>}
        </div> : busy ? <p className="player-lookup__feedback" role="status">
          <span className="entry-spinner" aria-hidden="true" />
          <span>{submitting ? "Starting player lookup…" : operation === "filter" ? "Filtering match history…" : operation === "older" ? "Loading older matches…" : operation === "refresh" ? "Updating match history…" : loading ? "Loading match history…" : "Fetching recent matches…"}</span>
          <span className="profile-sr-only"> {count > 0 ? `${count} ${count === 1 ? "match is" : "matches are"} ready to open while the lookup continues.`
            : "Loading up to 20 matches per page. This can take a moment."}</span>
        </p>
          : lookup?.status === "EMPTY" && count === 0 ? <div className="entry-notice" role="status">
            <strong>{lookup.hasMore ? "No supported matches on this page" : "No recent matches"}</strong>
            <p>{lookup.hasMore ? "Load older matches to continue through this player's history."
              : lookup.queueId === 0 ? "No supported matches found in this history snapshot." : `No ${queueLabel(lookup.queueId)} matches found in ${regionFor(lookup.platform).name}.`}</p>
            {!lookup.hasMore && <p>Check the Riot ID or search for another player.</p>}
          </div> : failed || lookup?.status === "PARTIAL" ? <div className="entry-notice" role="status">
            <strong>{failed ? "Lookup could not finish" : "Some matches are unavailable"}</strong>
            <p>{lookup?.message ?? (failed ? "Search again to retry this lookup." : "Completed matches are ready to open below.")}</p>
          </div> : lookup ? <p className="player-lookup__feedback" role="status">{count} {count === 1 ? "match" : "matches"}</p> : null}
        {retryNotBefore && <p>Try again after <time dateTime={retryNotBefore}>{new Date(retryNotBefore).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", second: "2-digit" })}</time>.</p>}
      </div>);

  return <><section className="player-lookup" aria-label="Player lookup">
    {!identityKnown && <h1 className="profile-sr-only">Find a player’s match history</h1>}
    <PlayerSearchForm identity={lookup?.gameName ? lookup : initialIdentity} submitting={submitting} profile={identityKnown} onSubmit={submit} />
    {(lookup || busy || issue) && historyStatus}

    {(lookup || busy || issue) && <div className="player-lookup__results">
      {lookup && identityKnown && <PlayerProfileHeader identity={{ gameName: lookup.gameName, tagLine: lookup.tagLine, platform: lookup.platform }} {...profileState}>
        <HistoryUpdate nextRefreshAt={lookup.nextRefreshAt} retryNotBefore={retryNotBefore} lastUpdated={lookup.lastUpdated}
          busy={busy || profileState.loadingRecent} updating={operation === "refresh"} onRefresh={refresh} />
      </PlayerProfileHeader>}
      <div className={identityKnown ? "profile-layout" : undefined}>
        {identityKnown && <aside className="profile-sidebar" aria-label="Player ranks and champions">
          <PlayerProfilePanel {...profileState} live />
          {lookup && <ChampionPerformance matches={lookup.matches} catalogs={catalogs} />}
        </aside>}
        <div className="profile-history">
          {lookup && identityKnown && <RecentPerformance matches={lookup.matches} catalogs={catalogs} />}
          {lookup && <HistoryControls queueId={lookup.queueId} disabled={busy || !identityKnown} onChange={filter} />}
          {busy && count === 0 && <HistorySkeleton />}
          {lookup && count > 0 && <MatchHistory matches={lookup.matches} catalogs={catalogs}
            hrefFor={match => developmentHref(match.matchId, match.participantId, undefined, undefined, undefined, { historyRunId: lookup.runId })} />}
          {lookup && <div className="player-history__pagination">
            {restoreCursor ? <button className="entry-button" disabled={busy} onClick={restoreMore}>Restore earlier pages</button>
              : lookup.hasMore ? <button className="entry-button" disabled={busy} onClick={older}>{operation === "older" ? "Loading older matches…" : "Load older matches"}</button>
                : !busy && (lookup.status === "COMPLETE" || lookup.status === "EMPTY") ? <p>{lookup.queueId === 0 ? "End of supported match history." : "End of match history for this queue."}</p> : null}
          </div>}
        </div>
      </div>
    </div>}

  </section>{count === 0 ? children : null}</>;
}
