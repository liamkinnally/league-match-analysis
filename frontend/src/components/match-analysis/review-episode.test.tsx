import { fireEvent, render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import {
  fixtureResponse,
  selectedResponse,
} from "../../test/match-analysis-fixture";
import type {
  ActiveAnalysis,
  AnalysisRouteContext,
  MatchAnalysisResponse,
  Review as ReviewValue,
  ReviewBeat,
  ReviewEpisode,
} from "../../lib/analysis/types";
import {
  parseAnalysisRouteContext,
  toAnalysisHref,
  withPanel,
} from "../../lib/analysis/route-context";
import * as routeContext from "../../lib/analysis/route-context";
import { MatchAnalysisShell } from "./match-analysis-shell";
import { Review } from "./review";
import { ReviewEpisode as ReviewEpisodeView } from "./review-episode";

type TaskSevenRouteHelpers = {
  exploreThisMomentHref: (
    episode: ReviewEpisode,
    route: AnalysisRouteContext,
  ) => string;
  investigateHref: (
    episode: ReviewEpisode,
    beatId: string,
    route: AnalysisRouteContext,
  ) => string;
};

const { exploreThisMomentHref, investigateHref } =
  routeContext as unknown as TaskSevenRouteHelpers;

const fixtureReview = fixtureResponse.review as unknown as ReviewValue;

function beat(
  id: string,
  kind: string,
  title: string,
  detail: string,
  assertionMode = "OBSERVED",
): ReviewBeat {
  return {
    id,
    kind,
    title,
    detail,
    assertionMode,
    sourceClaimId: `claim-${id}`,
    evidenceReferences: [],
    limitations: [],
  };
}

function renderEpisode(episode: ReviewEpisode) {
  const review: ReviewValue = {
    ...fixtureReview,
    episodes: [episode],
    learningOrder: [episode.id],
    chronologicalOrder: [episode.id],
    temporalCues: { [episode.id]: "START" },
  };
  const active = {
    ...selectedResponse.active,
    context: {
      ...selectedResponse.active.context,
      selectedObjectId: episode.id,
      interval: episode.interval,
      questionId: episode.questionId,
    },
    sourceTransitionId: episode.transitionId,
    linkedReviewEpisode: episode,
  } as ActiveAnalysis;
  const route: AnalysisRouteContext = {
    matchId: fixtureResponse.match.matchId,
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: episode.questionId,
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
  };

  render(
    <Review
      review={review}
      active={active}
      evidenceRevision={fixtureResponse.evidenceRevision}
      route={route}
    />,
  );
}

it("does not pad an episode with fewer supported beats", () => {
  const episode: ReviewEpisode = {
    ...fixtureReview.episodes[1],
    beats: [
      beat("beat-1", "SEQUENCE", "Observed sequence", "Two events overlap."),
      beat("beat-2", "RECEIPT", "State receipt", "The receipt shows a trade."),
      beat(
        "beat-3",
        "INTERPRETATION",
        "Bounded interpretation",
        "The exchange admits multiple readings.",
        "INTERPRETED",
      ),
      beat(
        "beat-4",
        "COMPETING_READING",
        "Competing reading",
        "Another response-supported interpretation remains open.",
        "UNKNOWN",
      ),
    ],
  };

  renderEpisode(episode);

  expect(screen.getAllByTestId("review-beat")).toHaveLength(4);
  const interpretation = screen.getByTestId("review-interpretation");
  expect(within(interpretation).getByText("The exchange admits multiple readings.")).toBeVisible();
  expect(
    within(interpretation).getByText("Competing reading"),
  ).toBeVisible();
});

it("renders the response assertion mode for every review beat", () => {
  const episode: ReviewEpisode = {
    ...fixtureReview.episodes[1],
    beats: [
      beat("mode-1", "SEQUENCE", "Observed sequence", "Observed detail."),
      beat(
        "mode-2",
        "RECEIPT",
        "Reconstructed receipt",
        "Reconstructed detail.",
        "RECONSTRUCTED",
      ),
      beat(
        "mode-3",
        "INTERPRETATION",
        "Interpreted reading",
        "Interpreted detail.",
        "INTERPRETED",
      ),
      beat(
        "mode-4",
        "RECOGNITION_CUE",
        "Expert-backed cue",
        "Expert detail.",
        "EXPERT_MAINTAINED",
      ),
      beat(
        "mode-5",
        "EVIDENCE_LIMIT",
        "Unknown boundary",
        "Unknown detail.",
        "UNKNOWN",
      ),
    ],
  };

  renderEpisode(episode);

  expect(screen.getByText("Assertion: Observed")).toBeVisible();
  expect(screen.getByText("Assertion: Reconstructed")).toBeVisible();
  expect(screen.getByText("Assertion: Interpretation")).toBeVisible();
  expect(screen.getByText("Assertion: Expert knowledge")).toBeVisible();
  expect(screen.getByText("Assertion: Unknown")).toBeVisible();
});

it("does not expose absent decision ontology as headings", () => {
  const episode: ReviewEpisode = {
    ...fixtureReview.episodes[1],
    beats: [
      beat("beat-1", "CUE", "Observed cue", "A response-provided cue."),
      beat("beat-2", "SEQUENCE", "Observed sequence", "A response-provided sequence."),
      beat("beat-3", "RECEIPT", "State receipt", "A response-provided receipt."),
      beat(
        "beat-4",
        "INTERPRETATION",
        "Bounded interpretation",
        "A response-provided interpretation.",
        "INTERPRETED",
      ),
    ],
  };

  renderEpisode(episode);

  for (const absentField of ["Belief", "Goal", "OptionSet"]) {
    expect(screen.queryByRole("heading", { name: absentField })).toBeNull();
  }
});

it("round-trips Explore through the linked transition and exact Review origin", () => {
  const episode = fixtureReview.episodes[1];
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: "advantage-conversion",
    evidenceRevision: "ev_route-review",
    requestedLens: "TRANSFER",
  };

  const href = exploreThisMomentHref(episode, route);

  expect(href).toContain("mode=explore");
  expect(href).toContain(`object=${episode.transitionId}`);
  expect(parseAnalysisRouteContext(href)).toEqual({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "explore",
    selectedObjectId: "trn_000000000000000000000002",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: "ev_route-review",
    requestedLens: "TRANSFER",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000002",
    },
  });
});

it("keeps an existing Review beat as the Explore return target", () => {
  const episode = fixtureReview.episodes[1];
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: "advantage-conversion",
    evidenceRevision: "ev_route-review",
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: episode.id,
      beatId: "beat-4",
    },
  };

  expect(
    parseAnalysisRouteContext(exploreThisMomentHref(episode, route)).returnTarget,
  ).toEqual({
    mode: "review",
    objectId: "ep_000000000000000000000002",
    beatId: "beat-4",
  });
});

it("round-trips Investigation with the exact Review episode and beat", () => {
  const episode = fixtureReview.episodes[1];
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: "advantage-conversion",
    evidenceRevision: "ev_route-review",
    requestedLens: "SEQUENCE",
  };

  const href = investigateHref(episode, "beat-4", route);

  expect(href).toContain("mode=investigate");
  expect(href).toContain("returnMode=review");
  expect(href).toContain("returnBeat=beat-4");
  expect(parseAnalysisRouteContext(href)).toEqual({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_000000000000000000000002",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: "ev_route-review",
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000002",
      beatId: "beat-4",
    },
  });
});

it("preserves supplied cross-mode interval and question context", () => {
  const episode = fixtureReview.episodes[1];
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: episode.id,
    interval: { startMs: 700000, endMs: 710000 },
    questionId: "custom-review-question",
    evidenceRevision: "ev_route-review",
    requestedLens: "SEQUENCE",
  };

  const explore = parseAnalysisRouteContext(exploreThisMomentHref(episode, route));
  const investigate = parseAnalysisRouteContext(
    investigateHref(episode, "beat-4", route),
  );

  expect(explore).toMatchObject({
    interval: { startMs: 700000, endMs: 710000 },
    questionId: "custom-review-question",
  });
  expect(investigate).toMatchObject({
    interval: { startMs: 700000, endMs: 710000 },
    questionId: "custom-review-question",
  });
});

it("changes only panel for Review Evidence and Ask asides", () => {
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: "ep_000000000000000000000002",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: "ev_route-review",
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000002",
      beatId: "beat-4",
    },
  };

  expect(
    parseAnalysisRouteContext(toAnalysisHref(withPanel(route, "evidence"))),
  ).toEqual({ ...route, panel: "evidence" });
  expect(
    parseAnalysisRouteContext(toAnalysisHref(withPanel(route, "ask"))),
  ).toEqual({ ...route, panel: "ask" });
});

it("renders the adverse evidence boundary without authoring blame or causality", () => {
  const episode: ReviewEpisode = {
    ...fixtureReview.episodes[2],
    beats: [
      beat("e3-beat-1", "BEFORE_STATE", "Before state", "The sampled antecedent."),
      beat("e3-beat-2", "FOCAL_DEATH", "Observed focal death", "An observed event."),
      beat("e3-beat-3", "RECEIPT", "State receipt", "A bounded state change."),
      beat(
        "e3-beat-4",
        "EVIDENCE_LIMIT",
        "Evidence boundary",
        "The observed order does not establish cause or contestability.",
        "UNKNOWN",
      ),
    ],
  };

  const { container } = render(<ReviewEpisodeView episode={episode} />);

  expect(
    screen.getByText(/does not establish cause or contestability/i),
  ).toBeVisible();
  expect(container.textContent).not.toMatch(
    /mistake|throw|gave dragon|caused|best action/i,
  );
});

it("keeps parallel bands and expands only the response-backed competing reading", () => {
  const episode: ReviewEpisode = {
    ...fixtureReview.episodes[3],
    beats: [
      beat(
        "e4-beat-1",
        "SEQUENCE",
        "Exact observed sequence",
        "Overlapping anchors remain parallel.",
      ),
      beat("e4-beat-2", "RECEIPT", "State receipt", "The bounded receipt."),
      beat(
        "e4-beat-3",
        "INTERPRETATION",
        "Bounded interpretation",
        "The response supports a bounded reading.",
        "INTERPRETED",
      ),
      beat(
        "e4-beat-4",
        "COMPETING_READING",
        "Competing reading",
        "The response supports a second bounded reading.",
        "UNKNOWN",
      ),
    ],
  };
  const sequence = (selectedResponse.active as unknown as ActiveAnalysis).lens;
  if (sequence.type !== "SEQUENCE") throw new Error("Expected Sequence fixture");
  const response = {
    ...selectedResponse,
    review: {
      ...fixtureReview,
      episodes: [episode],
      learningOrder: [episode.id],
      chronologicalOrder: [episode.id],
      temporalCues: { [episode.id]: "START" },
    },
    active: {
      ...selectedResponse.active,
      context: {
        ...selectedResponse.active.context,
        selectedObjectId: episode.id,
        interval: episode.interval,
        questionId: episode.questionId,
      },
      sourceTransitionId: episode.transitionId,
      linkedReviewEpisode: episode,
      lens: {
        ...sequence,
        bands: [
          {
            ...sequence.bands[0],
            parallel: true,
            anchors: [
              ...sequence.bands[0].anchors,
              {
                ...sequence.bands[0].anchors[0],
                descriptor: "Opponent objective trade",
              },
            ],
          },
        ],
      },
    },
  };
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: episode.questionId,
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
  };

  const { container } = render(
    <MatchAnalysisShell
      response={response as unknown as MatchAnalysisResponse}
      route={route}
    />,
  );

  expect(screen.getByTestId("sequence-band")).toHaveAttribute(
    "data-parallel",
    "true",
  );
  const disclosure = screen
    .getByText("Competing reading", { selector: "summary" })
    .closest("details");
  if (!disclosure) throw new Error("Missing competing-reading disclosure");
  const receipt = screen.getAllByTestId("state-receipt")[0];
  const interpretationClaim = screen.getByText(/claim-e4-beat-3/i);
  expect(disclosure).not.toHaveAttribute("open");

  fireEvent.click(within(disclosure).getByText("Competing reading"));

  expect(disclosure).toHaveAttribute("open");
  expect(
    within(disclosure).getByText("The response supports a second bounded reading."),
  ).toBeVisible();
  expect(within(disclosure).getByText(/claim-e4-beat-4/i)).toBeVisible();
  expect(screen.getAllByTestId("state-receipt")[0]).toBe(receipt);
  expect(screen.getByText(/claim-e4-beat-3/i)).toBe(interpretationClaim);
  expect(container.textContent).not.toMatch(
    /mistake|throw|gave dragon|caused|best action/i,
  );
});
