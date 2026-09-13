import Link from "next/link";
import { useRouter } from "next/navigation";
import { AssetTooltip } from "../asset-tooltip";
import { FinalItemSlots } from "../final-item-slots";
import { GameAssetIcon } from "../game-asset-icon";
import { RankBadge, RankDetails } from "./ranks";
import { developmentHref } from "../../lib/development/route";
import {
  participantName,
  playerName,
  riotId,
  roleLabel,
  numberText,
  rankDisplay,
  queueType,
} from "../../lib/development/results";
import { matchResult } from "../../lib/match-result";
import type {
  CurrentRanks,
  DevelopmentInterval,
  MatchDevelopment,
  MatchDevelopmentParticipant,
  Metric,
} from "../../lib/development/types";
import type { GameAssetCatalog } from "../../lib/game-assets/types";

function SummonerSpells({
  person,
  assets,
  className,
}: {
  person: MatchDevelopmentParticipant;
  assets: GameAssetCatalog | null;
  className: string;
}) {
  return (
    <span
      className={`development-spells ${className}`}
      aria-label="Summoner spells"
    >
      {[person.summonerSpellOneId, person.summonerSpellTwoId].map(
        (id, index) => (
          <span key={index} data-spell-id={id}>
            <AssetTooltip
              asset={assets?.spells[String(id)]}
              kind="spell"
              patch={assets?.assetVersion}
              fallback="?"
              iconClass="development-spell-icon"
            />
          </span>
        ),
      )}
    </span>
  );
}
export function Scoreboard({
  data,
  interval,
  assets,
  ranks,
  metric,
}: {
  data: MatchDevelopment;
  interval: DevelopmentInterval;
  assets: GameAssetCatalog | null;
  ranks: CurrentRanks;
  metric: Metric;
}) {
  const router = useRouter();
  const teamIds = [...new Set(data.roster.map((p) => p.teamId))];
  const currentOpponent = data.roster.find(
    (p) => p.participantId === data.summary.compareParticipantId,
  );
  return (
    <section
      id="scoreboard"
      className="development-scoreboard"
      aria-labelledby="scoreboard-heading"
    >
      <div className="development-section-heading">
        <div>
          <p className="development-kicker">Final state</p>
          <h2 id="scoreboard-heading">Scoreboard</h2>
        </div>
        <RankDetails
          ranks={ranks}
          totalPlayers={data.roster.length}
          queueId={data.summary.queueId}
        />
      </div>
      {teamIds.map((teamId) => {
        const people = data.roster.filter((p) => p.teamId === teamId),
          result = matchResult(people[0]?.win);
        return (
          <section
            key={teamId}
            className={`development-team match-result ${result.className}`}
          >
            <div className="development-team__heading">
              <h3>
                {teamId === 100
                  ? "Blue team"
                  : teamId === 200
                    ? "Red team"
                    : `Team ${teamId}`}
              </h3>
              <span>{result.label}</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>Player</th>
                  {data.summary.queueId !== 450 && <th className="development-hide-narrow">Role</th>}
                  <th>KDA</th>
                  <th>CS</th>
                  <th>Gold</th>
                  <th className="development-items-column">Final items</th>
                </tr>
              </thead>
              <tbody>
                {people.map((person) => {
                  const name = participantName(person, assets),
                    rank = ranks.players.find(
                      (p) => p.participantId === person.participantId,
                    );
                  const compare =
                    currentOpponent?.teamId !== person.teamId
                      ? currentOpponent?.participantId
                      : undefined;
                  const href = developmentHref(
                    data.matchId,
                    person.participantId,
                    compare,
                    interval,
                    metric,
                  );
                  const label = `Select ${riotId(person)}, playing ${name}${data.summary.queueId === 450 ? "" : `, ${roleLabel(person.teamPosition)}`}.${queueType(data.summary.queueId) ? ` Current rank: ${rankDisplay(rank).label}` : ""}`;
                  const selected =
                    person.participantId === data.summary.focusParticipantId;
                  return (
                    <tr
                      key={person.participantId}
                      data-participant-id={person.participantId}
                      className={
                        selected
                          ? "development-roster-focus"
                          : person.participantId ===
                              data.summary.compareParticipantId
                            ? "development-roster-compare"
                            : undefined
                      }
                      onClick={(event) => {
                        if (
                          !(event.target as HTMLElement).closest(
                            "a,button,select,summary,details",
                          )
                        )
                          router.push(href, { scroll: false });
                      }}
                    >
                      <th scope="row">
                        <div className="scoreboard-identity">
                          <Link
                            className="scoreboard-portrait"
                            href={href}
                            scroll={false}
                            aria-label={label}
                            tabIndex={-1}
                          >
                            <GameAssetIcon
                              asset={
                                assets?.champions[String(person.championId)]
                              }
                              fallback={name.slice(0, 2)}
                              className="development-champion-mark"
                            />
                          </Link>
                          <SummonerSpells
                            person={person}
                            assets={assets}
                            className="development-spells--desktop"
                          />
                          <span className="scoreboard-player-copy">
                            <Link
                              className="refined-summoner-line"
                              href={href}
                              scroll={false}
                              aria-label={label}
                              aria-current={selected ? "true" : undefined}
                              title={riotId(person)}
                            >
                              <strong className="refined-summoner-name">
                                {playerName(person)}
                              </strong>
                              {person.tagLine ? (
                                <span className="refined-summoner-tag">
                                  #{person.tagLine}
                                </span>
                              ) : null}
                            </Link>
                            <span className="refined-player-line">
                              <strong>{name}</strong>
                              <RankBadge
                                record={rank}
                                queueId={data.summary.queueId}
                              />
                            </span>
                            <FinalItemSlots
                              interactive
                              itemIds={person.endItemIds}
                              assets={assets}
                              className="development-items--scoreboard development-items--scoreboard-mobile"
                            />
                          </span>
                          <SummonerSpells
                            person={person}
                            assets={assets}
                            className="development-spells--mobile"
                          />
                        </div>
                      </th>
                      {data.summary.queueId !== 450 && <td className="development-hide-narrow">
                        {roleLabel(person.teamPosition)}
                      </td>}
                      <td className="development-number">
                        {person.kills} / {person.deaths} / {person.assists}
                      </td>
                      <td className="development-number">
                        {numberText(person.totalCs)}
                      </td>
                      <td className="development-number">
                        {numberText(person.goldEarned)}
                      </td>
                      <td className="development-items-column">
                        <FinalItemSlots
                          interactive
                          itemIds={person.endItemIds}
                          assets={assets}
                          className="development-items--scoreboard"
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>
        );
      })}
    </section>
  );
}
