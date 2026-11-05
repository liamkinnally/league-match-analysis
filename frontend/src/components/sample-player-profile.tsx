"use client";

import { useState } from "react";
import { queueLabel } from "../lib/development/results";
import { developmentHref } from "../lib/development/route";
import { useGameAssetCatalogs } from "../lib/game-assets/use-game-assets";
import type { MatchSummary } from "../lib/player-lookup/types";
import { samplePlayerProfile } from "../sample/profile";
import { HistoryControls, MatchHistory } from "./match-history";
import { ChampionPerformance, RecentPerformance } from "./player-performance";
import { PlayerProfileHeader, PlayerProfilePanel } from "./player-profile";

function sampleMatchHref(match: MatchSummary) {
  const opponent = match.participantId === 1 ? 6 : 1;
  return developmentHref(match.matchId, match.participantId, opponent);
}

export function SamplePlayerProfile({ matches }: { matches: MatchSummary[] }) {
  const [queueId, setQueueId] = useState(0);
  const [checkedForUpdate, setCheckedForUpdate] = useState(false);
  const selected = queueId === 0 ? matches : matches.filter(match => match.queueId === queueId);
  const catalogs = useGameAssetCatalogs(matches.map(match => match.gameVersion));
  return <>
    <PlayerProfileHeader identity={samplePlayerProfile.identity} profile={samplePlayerProfile} label="Sample player profile">
      <button className="entry-button profile-update sample-profile-update" type="button" disabled={checkedForUpdate}
        onClick={() => setCheckedForUpdate(true)}>Update</button>
      <a className="entry-button sample-history-shortcut" href="#match-history">View match history</a>
      <small aria-live="polite">{checkedForUpdate ? "Sample profile is up to date." : "Sample profile · Synthetic data"}</small>
    </PlayerProfileHeader>
    <div className="profile-layout">
      <aside className="profile-sidebar" aria-label="Player ranks and champions">
        <PlayerProfilePanel profile={samplePlayerProfile} timeZone="UTC" />
        <ChampionPerformance matches={selected} catalogs={catalogs} />
      </aside>
      <div className="profile-history">
        <RecentPerformance matches={selected} catalogs={catalogs} />
        <HistoryControls queueId={queueId} onChange={setQueueId} />
        <div className="player-lookup__status">
          <p role="status">{selected.length
            ? `${selected.length} ${selected.length === 1 ? "match" : "matches"}`
            : queueId ? `No ${queueLabel(queueId)} matches in this sample.` : "No matches in this sample."}</p>
        </div>
        {selected.length > 0 && <MatchHistory matches={selected} catalogs={catalogs} hrefFor={sampleMatchHref} />}
      </div>
    </div>
  </>;
}
