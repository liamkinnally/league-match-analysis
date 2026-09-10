import type { MatchAnalysisResponse } from "../../lib/analysis/types";
import { matchResult } from "../../lib/match-result";

type Props = {
  match: MatchAnalysisResponse["match"];
};

export function MatchHeader({ match }: Props) {
  const participant = match.focalParticipant;
  const result = matchResult(participant.win);

  return (
    <header className={`match-header match-result ${result.className}`}>
      <p className="eyebrow">Post-match analysis</p>
      <div className="match-header__title-row">
        <div>
          <h1>{participant.championName}</h1>
          <p className="match-header__summary">
            {participant.teamPosition} · <span className="match-result__label">{result.label}</span>
          </p>
        </div>
        <dl className="match-header__meta" aria-label="Match details">
          <div>
            <dt>Duration</dt>
            <dd>{Math.round(match.durationMs / 60_000)} min</dd>
          </div>
          <div>
            <dt>Patch</dt>
            <dd>{match.gameVersion}</dd>
          </div>
        </dl>
      </div>
    </header>
  );
}
