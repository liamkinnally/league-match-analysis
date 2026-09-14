import { GameAssetIcon } from "./game-asset-icon";
import { ProfileTime, RankObservations, rankValue } from "./rank-observations";
import { useCurrentProfileAssets } from "../lib/game-assets/use-game-assets";
import type { PlayerProfile, ProfileIdentity, RecentSoloRecord, SoloRank } from "../lib/player-lookup/profile";

function currentRecord(rank: SoloRank): string {
  if (rank.status === "loading") return "Loading current ranked record…";
  if (rank.status === "unranked") return "Unranked";
  if (rank.status !== "ranked" || rank.wins === null || rank.losses === null) return "Current ranked record unavailable";
  const total = rank.wins + rank.losses;
  return total ? `${Number((100 * rank.wins / total).toFixed(1))}% · ${rank.wins}W–${rank.losses}L` : "No ranked games recorded";
}
export function recentRecordLabel(recent: RecentSoloRecord): string {
  if (recent.completeness === "loading") return "Loading recent Solo/Duo record…";
  if (recent.completeness === "unverified") return `${recent.sampleSize} games with recorded outcomes loaded · ${recent.wins}W–${recent.losses}L · eligibility unverified`;
  if (recent.completeness === "incomplete_budget" || recent.completeness === "incomplete_missing") return `${recent.sampleSize} verified games loaded · ${recent.wins}W–${recent.losses}L · recent record incomplete`;
  if (!recent.sampleSize) return "No eligible games in the checked range";
  const rate = Number((100 * recent.wins / recent.sampleSize).toFixed(1));
  return `${rate}% · ${recent.wins}W–${recent.losses}L · ${recent.completeness === "complete_target" ? "latest 20 eligible games" : `${recent.sampleSize} eligible games in the checked range`}`;
}
function SectionFreshness({ label, value, failed }: { label: string; value: PlayerProfile["summoner"] | SoloRank; failed: boolean }) {
  return <small className="player-profile__freshness">
    {label} {value.fetchedAt ? <>fetched <ProfileTime value={value.fetchedAt} /></> : "not fetched"}
    {value.refreshing && " · Updating…"}
    {(failed || value.stale || value.error) && " · May be out of date"}
    {value.error && " · Refresh unavailable"}
    {value.retryNotBefore && <> · Retry after <ProfileTime value={value.retryNotBefore} /></>}
  </small>;
}
export function PlayerProfilePanel({ identity, profile, loading, issue, loadingRecent, loadingOlder, busy, now, loadRecent, loadOlder, reload }: {
  identity: ProfileIdentity; profile: PlayerProfile | null; loading: boolean;
  issue: { message: string; retryNotBefore: string | null } | null;
  loadingRecent: boolean; loadingOlder: boolean; busy: boolean; now: number;
  loadRecent: () => void; loadOlder: () => void; reload: () => void;
}) {
  const assets = useCurrentProfileAssets();
  const loadedIdentity = profile?.identity ?? identity;
  const iconId = profile?.summoner.profileIconId;
  const retry = Math.max(Date.parse(profile?.recentSolo.retryNotBefore ?? "") || 0, Date.parse(issue?.retryNotBefore ?? "") || 0);
  const cooldownExpired = retry > 0 && retry <= now;
  const recentBlocked = busy || retry > now || (!profile?.recentSolo.canLoad && !cooldownExpired);
  return <section className="player-profile" aria-label="Searched player profile">
    <div className="player-profile__heading">
      <GameAssetIcon asset={iconId === null || iconId === undefined ? undefined : assets?.profileIcons?.[String(iconId)]} fallback={loadedIdentity.gameName.slice(0, 2)} className="player-profile__icon" />
      <div><h2>{loadedIdentity.gameName}<span>#{loadedIdentity.tagLine}</span></h2>
        {profile?.summoner.summonerLevel !== null && profile?.summoner.summonerLevel !== undefined && <span className="player-profile__level">Level {profile.summoner.summonerLevel.toLocaleString("en-US")}</span>}
        {profile ? <SectionFreshness label="Profile" value={profile.summoner} failed={Boolean(issue)} /> : <small>{loading ? "Loading profile details…" : "Profile details unavailable"}</small>}
      </div>
    </div>
    {issue && <p className="player-profile__notice" role="status">{issue.message} <button type="button" className="entry-button" disabled={busy} onClick={reload}>Check profile</button></p>}
    {profile && <>
      <div className="player-profile__row"><span className="player-profile__label">Solo/Duo</span>
        <div className="player-profile__rank">{profile.soloRank.status === "ranked" && <GameAssetIcon asset={assets?.ranks?.[profile.soloRank.tier ?? ""]} fallback={profile.soloRank.tier?.slice(0, 1) ?? "R"} className="player-profile__rank-icon" />}
          <strong>{profile.soloRank.status === "loading" ? "Loading rank…" : profile.soloRank.status === "unavailable" ? "Rank unavailable" : rankValue({ ...profile.soloRank, status: profile.soloRank.status })}</strong>
          <SectionFreshness label="Rank" value={profile.soloRank} failed={Boolean(issue)} />
        </div>
      </div>
      <div className="player-profile__row"><span className="player-profile__label">Current ranked record</span><div><strong>{currentRecord(profile.soloRank)}</strong><small>Reporting period not specified by the source.</small></div></div>
      <div className="player-profile__row"><span className="player-profile__label">Recent Solo/Duo</span><div><strong>{recentRecordLabel(profile.recentSolo)}</strong>
        <small>Solo/Duo only · Snapshot <ProfileTime value={profile.recentSolo.snapshotAt} /> · May span ranked periods.</small>
        {profile.recentSolo.completeness === "unverified" && <small>Remake eligibility is not verified. An eligible win rate is unavailable.</small>}
        {(profile.recentSolo.canLoad || retry > 0 || loadingRecent) && <div className="player-profile__recent-action"><button type="button" className="entry-button" disabled={recentBlocked} onClick={profile.recentSolo.canLoad ? loadRecent : reload}>{loadingRecent ? "Loading recent Solo/Duo…" : !profile.recentSolo.canLoad && cooldownExpired ? "Check recent record availability" : "Load recent Solo/Duo record"}</button>
          {retry > now && <small>Available after <ProfileTime value={new Date(retry).toISOString()} /></small>}
        </div>}
      </div></div>
      <div className="player-profile__row"><span className="player-profile__label">Observed rank history</span><div>
        {profile.rankHistory.trackingSince ? <small>Tracking since <ProfileTime value={profile.rankHistory.trackingSince} /></small> : <small>Tracking begins after the first successful rank refresh.</small>}
        <RankObservations history={profile.rankHistory} busy={loadingOlder || busy} loadOlder={loadOlder} />
      </div></div>
    </>}
  </section>;
}
