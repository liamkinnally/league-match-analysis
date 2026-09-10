import type {
  AnalysisRouteContext,
  MatchAnalysisResponse,
} from "../../lib/analysis/types";
import { MatchArc } from "./match-arc";
import { MatchHeader } from "./match-header";
import { Investigation } from "./investigation";
import { ModeSwitch } from "./mode-switch";
import { Review } from "./review";
import { TransitionStage } from "./transition-stage";

type Props = {
  response: MatchAnalysisResponse;
  route: AnalysisRouteContext;
};

export function MatchAnalysisShell({ response, route }: Props) {
  const activeInvestigation = response.active ? (
    <Investigation active={response.active} route={route} />
  ) : null;

  return (
    <main className="analysis-shell">
      <MatchHeader match={response.match} />
      <ModeSwitch response={response} route={route} />
      {route.mode === "review" ? (
        <>
          <Review
            review={response.review}
            active={response.active}
            evidenceRevision={response.evidenceRevision}
            route={route}
            showReceipt={
              response.active?.lens.type !== "STATE" &&
              response.active?.lens.type !== "RECEIPT"
            }
          />
          {activeInvestigation}
        </>
      ) : route.mode === "investigate" && activeInvestigation ? (
        activeInvestigation
      ) : (
        <>
          <section className="arc-introduction" aria-labelledby="match-arc-title">
            <p className="eyebrow">Match Arc</p>
            <h2 id="match-arc-title">How this match changed</h2>
            <p>
              A chronological record of material transitions supported by the match
              timeline.
            </p>
          </section>
          <MatchArc
            arc={response.arc}
            evidenceRevision={response.evidenceRevision}
            focalTeamId={response.match.focalParticipant.teamId}
            route={route}
          />
          {response.active ? (
            <TransitionStage active={response.active} route={route} />
          ) : null}
        </>
      )}
    </main>
  );
}
