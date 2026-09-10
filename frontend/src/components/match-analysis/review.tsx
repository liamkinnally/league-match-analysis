import Link from "next/link";
import {
  exploreThisMomentHref,
  investigateHref,
  toAnalysisHref,
} from "../../lib/analysis/route-context";
import type {
  ActiveAnalysis,
  AnalysisRouteContext,
  Review as ReviewValue,
  ReviewEpisode as ReviewEpisodeValue,
} from "../../lib/analysis/types";
import { ReviewEpisode } from "./review-episode";
import { ReviewBeatRestoration } from "./review-beat-restoration";
import { StateReceipt } from "./state-receipt";

type Props = {
  review: ReviewValue;
  active?: ActiveAnalysis | null;
  evidenceRevision?: string;
  route: AnalysisRouteContext;
  showReceipt?: boolean;
};

function currentEpisode(
  review: ReviewValue,
  active: ActiveAnalysis | null,
  route: AnalysisRouteContext,
) {
  if (
    active?.linkedReviewEpisode &&
    review.episodes.some((episode) => episode.id === active.linkedReviewEpisode?.id)
  ) {
    return active.linkedReviewEpisode;
  }
  const selected = review.episodes.find(
    (episode) => episode.id === route.selectedObjectId,
  );
  if (selected) return selected;
  const firstId = review.learningOrder[0];
  return review.episodes.find((episode) => episode.id === firstId) ?? review.episodes[0];
}

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(
    seconds % 60,
  ).padStart(2, "0")}`;
}

function cueForPrevious(cue: string | undefined): string {
  if (cue === "EARLIER") return "LATER";
  if (cue === "LATER") return "EARLIER";
  return cue ?? "SAME_TIME";
}

function cueLabel(cue: string): string {
  return cue.toLocaleLowerCase().replaceAll("_", " ");
}

function episodeHref(
  episode: ReviewEpisodeValue,
  evidenceRevision: string,
  route: AnalysisRouteContext,
): string {
  return toAnalysisHref({
    ...route,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: episode.questionId,
    evidenceRevision,
    requestedLens: route.requestedLens ?? "SEQUENCE",
    returnTarget: undefined,
    panel: undefined,
  });
}

export function Review({
  review,
  active = null,
  evidenceRevision,
  route,
  showReceipt = true,
}: Props) {
  const episode = currentEpisode(review, active, route);
  if (!episode) {
    return (
      <section className="review-empty">
        <h2>No review cases are available</h2>
        <p>The current evidence did not support a learning-order episode.</p>
      </section>
    );
  }

  const resolvedEvidenceRevision =
    evidenceRevision ?? route.evidenceRevision ?? active?.context.evidenceRevision;
  if (!resolvedEvidenceRevision) {
    throw new Error("REVIEW_EVIDENCE_REVISION_REQUIRED");
  }
  const orderedIndex = review.learningOrder.indexOf(episode.id);
  const caseNumber = orderedIndex >= 0 ? orderedIndex + 1 : 1;
  const episodeById = new Map(review.episodes.map((item) => [item.id, item]));
  const learningEpisodes = review.learningOrder.flatMap((id) => {
    const item = episodeById.get(id);
    return item ? [item] : [];
  });
  const chronologicalEpisodes = review.chronologicalOrder.flatMap((id) => {
    const item = episodeById.get(id);
    return item ? [item] : [];
  });
  const previousEpisode = orderedIndex > 0 ? learningEpisodes[orderedIndex - 1] : undefined;
  const nextEpisode = orderedIndex >= 0 ? learningEpisodes[orderedIndex + 1] : undefined;
  const activeRoute: AnalysisRouteContext = {
    ...route,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: episode.questionId,
    evidenceRevision: resolvedEvidenceRevision,
    requestedLens: route.requestedLens ?? "SEQUENCE",
    panel: undefined,
  };
  const investigationBeat =
    episode.beats.find((beat) => beat.kind === "RECEIPT") ?? episode.beats[0];
  const returnBeat = route.returnTarget?.mode === "review" &&
    route.returnTarget.objectId === episode.id
    ? episode.beats.find((beat) => beat.id === route.returnTarget?.beatId)
    : undefined;
  const linkedActive =
    active?.linkedReviewEpisode?.id === episode.id &&
    active.sourceTransitionId === episode.transitionId
      ? active
      : null;
  const returnHref = linkedActive
    ? toAnalysisHref({
        ...route,
        mode: "explore",
        selectedObjectId: linkedActive.sourceTransitionId,
        interval: episode.interval,
        questionId: episode.questionId,
        evidenceRevision: linkedActive.context.evidenceRevision,
        requestedLens: route.requestedLens ?? "SEQUENCE",
        returnTarget: undefined,
        panel: undefined,
      })
    : toAnalysisHref({
        ...route,
        mode: "explore",
        selectedObjectId: undefined,
        interval: undefined,
        questionId: undefined,
        evidenceRevision: undefined,
        requestedLens: undefined,
        returnTarget: undefined,
        panel: undefined,
      });

  return (
    <section className="review" aria-labelledby="review-title">
      <div className="review__heading">
        <div>
          <p className="eyebrow">Guided review</p>
          <h2 id="review-title">Case {caseNumber} of {review.episodes.length}</h2>
        </div>
        <Link href={returnHref}>Return to Match Arc</Link>
      </div>
      <nav className="review-index" aria-label="Learning order">
        <ol>
          {learningEpisodes.map((item, index) => (
            <li key={item.id}>
              <Link
                aria-current={item.id === episode.id ? "step" : undefined}
                href={episodeHref(item, resolvedEvidenceRevision, route)}
              >
                Case {index + 1}
              </Link>
            </li>
          ))}
        </ol>
      </nav>
      <nav
        className="review-chronology"
        aria-label="Chronological case rail"
        data-testid="chronological-rail"
      >
        <ol>
          {chronologicalEpisodes.map((item, index) => (
            <li key={item.id}>
              <Link
                aria-current={item.id === episode.id ? "step" : undefined}
                href={episodeHref(item, resolvedEvidenceRevision, route)}
              >
                <span>E{index + 1}</span>
                <time dateTime={`PT${Math.floor(item.interval.startMs / 1_000)}S`}>
                  {clock(item.interval.startMs)}
                </time>
              </Link>
            </li>
          ))}
        </ol>
      </nav>
      <section className="review-rationale" aria-labelledby="review-rationale-title">
        <h3 id="review-rationale-title">Why this case</h3>
        <p>{episode.selectionRationale}</p>
        <Link href={exploreThisMomentHref(episode, activeRoute)}>
          Explore this moment
        </Link>
      </section>
      {linkedActive && showReceipt ? (
        <StateReceipt receipt={linkedActive.receipt} />
      ) : linkedActive ? null : (
        <p className="review-receipt-unavailable">
          Open this case from the Review link to load its timestamped receipt.
        </p>
      )}
      <ReviewEpisode
        episode={episode}
        investigation={
          investigationBeat
            ? {
                beatId: investigationBeat.id,
                href: investigateHref(episode, investigationBeat.id, activeRoute),
              }
            : undefined
        }
      />
      {returnBeat ? (
        <ReviewBeatRestoration beatId={returnBeat.id} enabled={!route.panel} />
      ) : null}
      {previousEpisode || nextEpisode ? (
        <nav className="review-sequence" aria-label="Learning sequence">
          {previousEpisode ? (
            <Link href={episodeHref(previousEpisode, resolvedEvidenceRevision, route)}>
              Previous · {cueLabel(cueForPrevious(review.temporalCues[episode.id]))} ·{" "}
              {clock(previousEpisode.interval.startMs)}
            </Link>
          ) : null}
          {nextEpisode ? (
            <Link href={episodeHref(nextEpisode, resolvedEvidenceRevision, route)}>
              Next · {cueLabel(review.temporalCues[nextEpisode.id] ?? "SAME_TIME")} ·{" "}
              {clock(nextEpisode.interval.startMs)}
            </Link>
          ) : null}
        </nav>
      ) : null}
    </section>
  );
}
