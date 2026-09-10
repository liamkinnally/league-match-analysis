import Link from "next/link";
import {
  exploreThisMomentHref,
  toAnalysisHref,
} from "../../lib/analysis/route-context";
import type {
  AnalysisRouteContext,
  MatchAnalysisResponse,
  RequestedLens,
} from "../../lib/analysis/types";

type Props = {
  response: MatchAnalysisResponse;
  route: AnalysisRouteContext;
};

const REQUESTABLE_LENSES = new Set<RequestedLens>([
  "MAP",
  "SEQUENCE",
  "STATE",
  "CHAMPION_TIMING",
  "TRANSFER",
  "RECEIPT",
]);

function reviewEpisode(
  response: MatchAnalysisResponse,
  route: AnalysisRouteContext,
) {
  const returnEpisodeId =
    route.returnTarget?.mode === "review"
      ? route.returnTarget.objectId
      : undefined;
  const currentEpisodeId =
    route.mode === "review"
      ? response.active?.linkedReviewEpisode?.id ?? route.selectedObjectId
      : undefined;
  const restoredEpisode = response.review.episodes.find(
    (episode) =>
      episode.id === returnEpisodeId || episode.id === currentEpisodeId,
  );
  if (restoredEpisode) return restoredEpisode;

  const firstId = response.review.learningOrder[0];
  return (
    response.review.episodes.find((episode) => episode.id === firstId) ??
    response.review.episodes[0]
  );
}

function reviewHref(
  response: MatchAnalysisResponse,
  route: AnalysisRouteContext,
): string {
  const episode = reviewEpisode(response, route);
  if (!episode) return toAnalysisHref({ ...route, mode: "review", panel: undefined });

  const selectedLens = response.active?.context.selectedLens;
  const requestedLens =
    route.requestedLens ??
    (selectedLens && REQUESTABLE_LENSES.has(selectedLens as RequestedLens)
      ? (selectedLens as RequestedLens)
      : "SEQUENCE");
  const restoresReviewTarget =
    route.returnTarget?.mode === "review" &&
    route.returnTarget.objectId === episode.id;

  return toAnalysisHref({
    ...route,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: episode.questionId,
    evidenceRevision: response.evidenceRevision,
    requestedLens,
    returnTarget: restoresReviewTarget ? route.returnTarget : undefined,
    panel: undefined,
  });
}

export function ModeSwitch({ response, route }: Props) {
  const reviewCount = response.review.episodes.length;
  const reviewOriginEpisode =
    route.mode === "review"
      ? response.active?.linkedReviewEpisode ??
        response.review.episodes.find(
          (episode) => episode.id === route.selectedObjectId,
        )
      : undefined;
  const exploreHref = reviewOriginEpisode
    ? exploreThisMomentHref(reviewOriginEpisode, {
        ...route,
        selectedObjectId: reviewOriginEpisode.id,
        interval: reviewOriginEpisode.interval,
        questionId: reviewOriginEpisode.questionId,
        evidenceRevision: response.evidenceRevision,
        requestedLens: route.requestedLens ?? "SEQUENCE",
      })
    : toAnalysisHref({
        ...route,
        mode: "explore",
        panel: undefined,
      });

  return (
    <nav className="mode-switch" aria-label="Analysis mode">
      <Link href={exploreHref} aria-current={route.mode === "explore" ? "page" : undefined}>
        Explore
      </Link>
      {reviewCount > 0 ? (
        <Link
          href={reviewHref(response, route)}
          aria-current={route.mode === "review" ? "page" : undefined}
        >
          Review · {reviewCount}
        </Link>
      ) : (
        <span aria-disabled="true">Review · 0</span>
      )}
    </nav>
  );
}
