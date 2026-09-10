import { Fragment } from "react";
import { CompactTooltip } from "../asset-tooltip";
import { GameAssetIcon } from "../game-asset-icon";
import { dragonAcquisitions } from "../../lib/development/events";
import {
  finalTeams,
  numberText,
  objectiveColumns,
} from "../../lib/development/results";
import { timeLabel } from "../../lib/development/chart-model";
import { matchResult } from "../../lib/match-result";
import type {
  FinalTeamResult,
  MatchDevelopment,
} from "../../lib/development/types";

export const teamLabel = (id: number) =>
  id === 100 ? "Blue side" : id === 200 ? "Red side" : `Team ${id}`;
export function TeamResults({ data }: { data: MatchDevelopment }) {
  const teams = finalTeams(data);
  const objectives = objectiveColumns(
    data.summary.gameVersion,
    data.summary.mapId,
  );
  const dragons = dragonAcquisitions(
    data.events,
    data.roster,
    data.summary.gameVersion,
  );
  const count = (value: number | null | undefined) =>
    typeof value === "number" && Number.isFinite(value) ? (
      numberText(value)
    ) : (
      <span aria-label="Unavailable" title="Unavailable">—</span>
    );
  const kda = (team: FinalTeamResult) => {
    const values = [team.kills, team.deaths, team.assists];
    if (values.every((value) => value === null)) return count(null);
    return values.map((value, index) => (
      <Fragment key={index}>{index > 0 ? " / " : null}{count(value)}</Fragment>
    ));
  };
  const cell = (team: FinalTeamResult, key: string) =>
    key === "kda" ? (
      kda(team)
    ) : key === "gold" ? (
      count(team.goldEarned)
    ) : key === "dragon" ? (
      <span className="refined-dragon-cell">
        <span className="refined-dragon-order">
          {dragons
            .filter((d) => d.teamId === team.teamId)
            .map((d, index) => (
              <CompactTooltip
                key={d.id}
                label={`${d.name} — ${timeLabel(d.timestampMs, true)}, ${teamLabel(team.teamId)}, dragon ${index + 1}`}
                content={
                  <>
                    <strong>{d.name}</strong>
                    <p>
                      {teamLabel(team.teamId)} —{" "}
                      {timeLabel(d.timestampMs, true)}. Recorded dragon{" "}
                      {index + 1} for this team.
                    </p>
                  </>
                }
              >
                <GameAssetIcon
                  asset={
                    d.imageUrl
                      ? { name: d.name, imageUrl: d.imageUrl }
                      : undefined
                  }
                  fallback="?"
                  className="refined-dragon-icon"
                />
              </CompactTooltip>
            ))}
        </span>
        <span className="refined-dragon-count">
          {count(team.objectives.dragon)}
        </span>
      </span>
    ) : (
      count(team.objectives[key])
    );
  const columns = [
    { key: "kda", label: "Team K/D/A" },
    { key: "gold", label: "Gold earned" },
    ...objectives,
  ];
  const outcome = (team: FinalTeamResult) => {
    const result = matchResult(team.win);
    return (
      <>
        {teamLabel(team.teamId)}{" "}
        <strong className={result.className}>{result.label}</strong>
      </>
    );
  };
  return (
    <section
      className="preview-team-results"
      aria-labelledby="team-results-heading"
    >
      <div className="preview-team-results__heading">
        <h2 id="team-results-heading">Final team results</h2>
        <p>
          <span>Final kills</span>
          {teams.map((team, index) => (
            <Fragment key={team.teamId}>
              {index > 0 ? <span aria-hidden="true">—</span> : null}
              <strong
                className={`match-result ${matchResult(team.win).className}`}
                aria-label={`${teamLabel(team.teamId)}: ${numberText(team.kills)} kills`}
              >
                {count(team.kills)}
              </strong>
            </Fragment>
          ))}
          <span className="preview-final-time">
            End of match: {timeLabel(data.summary.durationMs)}
          </span>
        </p>
      </div>
      <div className="preview-team-results__overflow">
        <table aria-label="Final team results">
          <thead>
            <tr>
              <th scope="col">Team / result</th>
              {columns.map((c) => (
                <th key={c.key} scope="col">
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {teams.map((team) => (
              <tr
                key={team.teamId}
                className={`match-result ${matchResult(team.win).className}`}
              >
                <th scope="row">{outcome(team)}</th>
                {columns.map((c) => (
                  <td key={c.key}>{cell(team, c.key)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <table
        className="refined-team-results-mobile"
        aria-label="Final team results, compact"
      >
        <thead>
          <tr>
            <th scope="col">Final result</th>
            {teams.map((team) => (
              <th
                key={team.teamId}
                scope="col"
                className={`match-result ${matchResult(team.win).className}`}
              >
                <span>{teamLabel(team.teamId)}</span>
                <strong>{matchResult(team.win).label}</strong>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {columns.map((c) => (
            <tr key={c.key}>
              <th scope="row">{c.label}</th>
              {teams.map((team) => (
                <td
                  key={team.teamId}
                  className={`match-result ${matchResult(team.win).className}`}
                >
                  {cell(team, c.key)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
