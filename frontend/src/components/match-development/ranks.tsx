import { CompactTooltip } from "../asset-tooltip";
import { GameAssetIcon } from "../game-asset-icon";
import {
  AVERAGE_RANK_METHOD,
  currentRankAverage,
  numberText,
  queueShort,
  rankDisplay,
} from "../../lib/development/results";
import type { CurrentRanks, PlayerRank } from "../../lib/development/types";

function Emblem({ url }: { url: string | null }) {
  return (
    <GameAssetIcon
      asset={url ? { name: "Rank emblem", imageUrl: url } : undefined}
      fallback="—"
      className="refined-rank-emblem"
    />
  );
}
export function RankBadge({
  record,
  queueId,
}: {
  record?: PlayerRank;
  queueId: number;
}) {
  const rank = rankDisplay(record);
  const current = `Current ${queueShort(queueId)} rank: ${rank.label}`;
  return (
    <CompactTooltip
      label={current}
      className="refined-rank-trigger"
      content={
        <>
          <strong>{current}</strong>
          <p>
            {rank.status === "ranked"
              ? `${numberText(record?.leaguePoints)} LP. Current rank, not rank at match time or MMR.`
              : rank.status === "unranked"
                ? "Riot returned no ranked entry for this queue."
                : rank.status === "loading"
                  ? "Checking Riot’s League API…"
                  : "No verified rank response is available."}
          </p>
          {record?.fetchedAt ? (
            <small>
              Checked {new Date(record.fetchedAt).toLocaleString("en-US")}.{" "}
              {record.stale
                ? "Stale cached rank; refresh unavailable or in progress."
                : record.cached
                  ? "Cached rank."
                  : ""}
            </small>
          ) : null}
        </>
      }
    >
      <span
        className={`refined-rank-badge refined-rank-badge--${rank.status}`}
        data-rank-status={rank.status}
      >
        <Emblem url={rank.emblemUrl} />
        <span>{rank.label}</span>
      </span>
    </CompactTooltip>
  );
}
export function AverageRank({
  ranks,
  totalPlayers,
}: {
  ranks: CurrentRanks;
  totalPlayers: number;
}) {
  const average = currentRankAverage(ranks.players, totalPlayers);
  const label =
    average.label ?? (ranks.refreshing ? "Loading…" : "Unavailable");
  return (
    <div className="refined-rank-average" aria-live="polite" aria-atomic="true">
      <span className="refined-average-caption">Current avg. tier</span>
      <CompactTooltip
        label={`Current average tier: ${label}. ${average.contributors} of ${totalPlayers} players contribute.`}
        content={
          <>
            <strong>Current average tier: {label}</strong>
            <p>
              {average.contributors} of {totalPlayers} players contribute.
            </p>
            <p>{AVERAGE_RANK_METHOD}</p>
          </>
        }
      >
        <span className="refined-average-value">
          <Emblem url={average.emblemUrl} />
          <strong>{label}</strong>
        </span>
      </CompactTooltip>
    </div>
  );
}
export function RankDetails({
  ranks,
  totalPlayers,
  queueId,
}: {
  ranks: CurrentRanks;
  totalPlayers: number;
  queueId: number;
}) {
  const average = currentRankAverage(ranks.players, totalPlayers);
  const counts = (status: string) =>
    ranks.players.filter((p) => rankDisplay(p).status === status).length;
  return (
    <div className="refined-rank-note">
      <span>Current {queueShort(queueId)} ranks</span>
      <details>
        <summary>Rank details</summary>
        <div className="refined-rank-explanation">
          <p>
            Current queue-specific ranks from Riot’s League API; these are not
            match-time ranks or MMR. Names are the identities recorded in this
            match.
          </p>
          <p>
            {average.contributors} ranked, {counts("unranked")} confirmed
            unranked, {counts("unavailable")} unavailable, {counts("loading")}{" "}
            loading. The average uses {average.contributors} of {totalPlayers}{" "}
            players.
          </p>
          {ranks.players.some((p) => p.cached) ? (
            <p>
              Cached ranks are reused for five minutes.{" "}
              {ranks.players.some((p) => p.stale)
                ? "Some ranks are stale; inspect each rank for its check time."
                : "Each rank’s tooltip includes its check time."}
            </p>
          ) : null}
          <p>{AVERAGE_RANK_METHOD}</p>
        </div>
      </details>
    </div>
  );
}
