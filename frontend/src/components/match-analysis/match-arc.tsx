import Link from "next/link";
import { toAnalysisHref } from "../../lib/analysis/route-context";
import type {
  AnalysisRouteContext,
  MatchAnalysisResponse,
  Transition,
} from "../../lib/analysis/types";
import { AnalysisEmptyState } from "./analysis-empty-state";
import { ArcPrimer } from "./arc-primer";

type Props = {
  arc: MatchAnalysisResponse["arc"];
  evidenceRevision: string;
  focalTeamId: number;
  route: AnalysisRouteContext;
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function inspectHref(
  transition: Transition,
  evidenceRevision: string,
  route: AnalysisRouteContext,
): string {
  return toAnalysisHref({
    ...route,
    mode: "explore",
    selectedObjectId: transition.transitionId,
    interval: transition.interval,
    questionId: transition.questionId,
    evidenceRevision,
    requestedLens: route.selectedObjectId === transition.transitionId
      ? route.requestedLens ?? transition.primaryLens
      : transition.primaryLens,
    returnTarget: route.selectedObjectId === transition.transitionId
      ? route.returnTarget
      : undefined,
    panel: undefined,
  });
}

export function MatchArc({ arc, evidenceRevision, focalTeamId, route }: Props) {
  if (arc.transitions.length === 0) {
    return <AnalysisEmptyState reason={arc.emptyReason} />;
  }

  return (
    <ol className="match-arc" aria-label="Chronological match transitions">
      {arc.transitions.map((transition, index) => {
        const isFocalTeam = transition.anchors.some(
          (anchor) => anchor.teamId === focalTeamId,
        );
        const isSelected = route.selectedObjectId === transition.transitionId;

        return (
          <li
            className={`transition-marker ${
              isFocalTeam ? "transition-marker--focal" : "transition-marker--neutral"
            }${isSelected ? " transition-marker--selected" : ""}`}
            data-testid="transition-marker"
            key={transition.transitionId}
          >
            <span className="transition-marker__dot" aria-hidden="true" />
            <article>
              {index === 0 ? <ArcPrimer /> : null}
              <div className="transition-marker__meta">
                <span>E{index + 1}</span>
                <time dateTime={`PT${Math.floor(transition.interval.startMs / 1_000)}S`}>
                  {clock(transition.interval.startMs)}
                </time>
              </div>
              <h3>{transition.title}</h3>
              <div className="transition-observation">
                <p className="transition-label">Observed</p>
                {transition.anchors.map((anchor, anchorIndex) => (
                  <p key={`${transition.transitionId}-${anchorIndex}`}>
                    {anchor.descriptor ?? anchor.kind.replaceAll("_", " ")}
                  </p>
                ))}
              </div>
              {transition.interpretation ? (
                <div className="transition-interpretation">
                  <p className="transition-label">Interpretation</p>
                  <p>{transition.interpretation.statement}</p>
                </div>
              ) : null}
              <Link
                className="inspect-link"
                aria-label={`Inspect E${index + 1}: ${transition.title}`}
                href={inspectHref(transition, evidenceRevision, route)}
              >
                Inspect E{index + 1}
              </Link>
            </article>
          </li>
        );
      })}
    </ol>
  );
}
