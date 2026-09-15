import type { ReactNode } from "react";
import { GameAssetIcon } from "./game-asset-icon";
import { ProfileTime, RankObservations } from "./rank-observations";
import { RankHistoryChart } from "./rank-history-chart";
import { useCurrentProfileAssets } from "../lib/game-assets/use-game-assets";
import type { PlayerProfile, ProfileIdentity, SoloRank } from "../lib/player-lookup/profile";

type ProfileState = {
  identity: ProfileIdentity; profile: PlayerProfile | null; loading: boolean;
  issue: { message: string; retryNotBefore: string | null } | null;
  loadingOlder: boolean; busy: boolean; loadOlder: () => void; reload: () => void;
};

export function PlayerProfileHeader({ identity, profile, loading, issue, busy, reload, children }: Pick<ProfileState, "identity" | "profile" | "loading" | "issue" | "busy" | "reload"> & { children?: ReactNode }) {
  const assets = useCurrentProfileAssets();
  const player = profile?.identity ?? identity;
  const iconId = profile?.summoner.profileIconId;
  const level = profile?.summoner.summonerLevel;
  return <header className="profile-header" aria-label="Searched player profile">
    <div className="profile-header__portrait">
      <GameAssetIcon asset={iconId == null ? undefined : assets?.profileIcons?.[String(iconId)]} fallback={player.gameName.slice(0, 2)} className="profile-header__icon" />
      {level != null && <span className="profile-header__level" aria-label={`Level ${level}`}>{level.toLocaleString("en-US")}</span>}
    </div>
    <div className="profile-header__identity"><h1>{player.gameName}<span>#{player.tagLine}</span></h1>
      <p>North America <span className="profile-header__region">NA</span></p>
      {!profile && <small>{loading ? "Loading profile…" : "Profile details unavailable"}</small>}
      {profile?.summoner.refreshing && <small>Updating profile…</small>}
      {profile && (profile.summoner.stale || profile.summoner.error) && <small>Profile may be out of date</small>}
      {issue && <div className="profile-header__issue" role="status">{issue.message} <button type="button" className="entry-button" disabled={busy} onClick={reload}>Check profile</button></div>}
    </div>
    {children && <div className="profile-header__actions">{children}</div>}
  </header>;
}

function RankDetails({ rank, compact = false }: { rank: SoloRank; compact?: boolean }) {
  const assets = useCurrentProfileAssets();
  const tier = rank.tier ? rank.tier[0] + rank.tier.slice(1).toLowerCase() : "";
  const division = rank.tier && ["MASTER", "GRANDMASTER", "CHALLENGER"].includes(rank.tier) ? "" : ` ${rank.division ?? ""}`;
  const total = rank.wins !== null && rank.losses !== null ? rank.wins + rank.losses : null;
  return <div className={`profile-rank${compact ? " profile-rank--compact" : ""}`}>
    {rank.status === "ranked" && <GameAssetIcon asset={assets?.ranks?.[rank.tier ?? ""]} fallback={tier.slice(0, 1)} className="profile-rank__emblem" />}
    <div className="profile-rank__value">
      <strong>{rank.status === "ranked" ? `${tier}${division}` : rank.status === "unranked" ? "Unranked" : rank.status === "loading" ? "Loading rank…" : "Rank unavailable"}</strong>
      {rank.status === "ranked" && <span className="profile-rank__lp">{rank.leaguePoints?.toLocaleString("en-US")} <small>LP</small></span>}
      {rank.status === "unavailable" && <small>Try updating this profile.</small>}
    </div>
    {rank.status === "ranked" && total !== null && <div className="profile-rank__record">
      <span className="profile-rank__record-label">Ranked record</span>
      {total > 0 ? <><strong>{rank.wins}W <span>–</span> {rank.losses}L</strong><span className="profile-rank__rate">{Number((100 * rank.wins! / total).toFixed(1))}% <small>win rate</small></span></> : <span>No ranked games recorded</span>}
    </div>}
    {(rank.refreshing || rank.stale || rank.error) && <small className="profile-rank__freshness">{rank.refreshing ? "Updating rank…" : rank.error ? "Update unavailable · Showing saved rank" : "Rank may be out of date"}</small>}
  </div>;
}

export function PlayerProfilePanel({ profile, loading, issue, loadingOlder, busy, loadOlder }: Pick<ProfileState, "profile" | "loading" | "issue" | "loadingOlder" | "busy" | "loadOlder">) {
  return <div className="profile-ranks">
    <section className="profile-panel profile-panel--rank" aria-labelledby="solo-rank-title">
      <h2 id="solo-rank-title">Ranked Solo/Duo</h2>
      {profile ? <>
        <RankDetails rank={profile.soloRank} />
        {issue && <small className="profile-rank__freshness">Saved rank may be out of date</small>}
        <div className="profile-lp-history"><RankHistoryChart history={profile.rankHistory} /></div>
        <RankObservations history={profile.rankHistory} busy={loadingOlder || busy} loadOlder={loadOlder} />
        <details className="profile-record-note"><summary>About this record</summary><p>Wins and losses are the current ranked record from Riot. They are separate from the recent matches shown here and are not a verified total for the entire season.</p>{profile.soloRank.fetchedAt && <p>Rank updated <ProfileTime value={profile.soloRank.fetchedAt} />.</p>}</details>
      </> : <p className="profile-panel__empty">{loading ? "Loading rank…" : "Rank unavailable. Update this profile to try again."}</p>}
    </section>
    {profile && <details className="profile-panel profile-flex"><summary><span>Ranked Flex</span><span>{profile.flexRank.status === "ranked" ? `${profile.flexRank.leaguePoints?.toLocaleString("en-US")} LP` : profile.flexRank.status === "unranked" ? "Unranked" : profile.flexRank.status === "loading" ? "Loading…" : "Unavailable"}</span></summary><RankDetails rank={profile.flexRank} compact /></details>}
  </div>;
}
