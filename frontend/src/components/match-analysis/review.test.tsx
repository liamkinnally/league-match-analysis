import { render, screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
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
} from "../../lib/analysis/types";
import {
  parseAnalysisRouteContext,
  toAnalysisHref,
  toPanelCloseHref,
} from "../../lib/analysis/route-context";
import { MatchAnalysisShell } from "./match-analysis-shell";
import { Review } from "./review";

function beat(id: string, kind: string, title: string): ReviewBeat {
  return {
    id,
    kind,
    title,
    detail: `${title} detail from the response.`,
    assertionMode: kind === "INTERPRETATION" ? "INTERPRETED" : "OBSERVED",
    sourceClaimId: `claim-${id}`,
    evidenceReferences: [],
    limitations: [],
  };
}

const fixtureReview = fixtureResponse.review as unknown as ReviewValue;

const episodes = fixtureReview.episodes.map((episode, index) => ({
  ...episode,
  interval:
    index === 0 ? { startMs: 500_000, endMs: 560_000 } : episode.interval,
  beats:
    index === 1
      ? [
          beat("beat-1", "CUE", "Exact conversion cue"),
          beat("beat-2", "BEFORE_STATE", "Before state"),
          beat("beat-3", "SEQUENCE", "Exact observed sequence"),
          beat("beat-4", "RECEIPT", "State receipt"),
          beat("beat-5", "INTERPRETATION", "Bounded interpretation"),
          beat("beat-6", "RECOGNITION", "Recognition cue"),
        ]
      : episode.beats,
}));

const richReview: ReviewValue = {
  ...fixtureReview,
  episodes,
  learningOrder: [episodes[1].id, episodes[0].id, episodes[3].id, episodes[2].id],
  chronologicalOrder: [episodes[0].id, episodes[1].id, episodes[2].id, episodes[3].id],
  temporalCues: {
    [episodes[1].id]: "START",
    [episodes[0].id]: "EARLIER",
    [episodes[3].id]: "LATER",
    [episodes[2].id]: "EARLIER",
  },
};

const active = {
  ...selectedResponse.active,
  context: {
    ...selectedResponse.active.context,
    selectedObjectId: episodes[1].id,
  },
  linkedReviewEpisode: episodes[1],
} as ActiveAnalysis;

const reviewRoute: AnalysisRouteContext = {
  matchId: fixtureResponse.match.matchId,
  focalParticipantId: 6,
  mode: "review",
  selectedObjectId: episodes[1].id,
  interval: episodes[1].interval,
  questionId: episodes[1].questionId,
  evidenceRevision: fixtureResponse.evidenceRevision,
  requestedLens: "SEQUENCE",
};

it("restores the current episode's return beat once with native scrolling", () => {
  const scrolledIds: string[] = [];
  Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true,
    value: vi.fn(function (this: Element) { scrolledIds.push(this.id); }),
  });
  const route: AnalysisRouteContext = { ...reviewRoute,
    returnTarget: { mode: "review", objectId: episodes[1].id, beatId: "beat-4" },
  };
  const { rerender } = render(<Review review={richReview} route={route} />);
  expect(scrolledIds).toEqual(["beat-4"]);
  rerender(<Review review={richReview} route={{ ...route }} />);
  expect(scrolledIds).toEqual(["beat-4"]);
});

it.each(["evidence", "ask"] as const)(
  "does not replay a restored Review beat when %s closes to the same URL",
  (panel) => {
    const scroll = vi.fn();
    Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true, value: scroll });
    const underlyingHref = toAnalysisHref({
      ...reviewRoute,
      returnTarget: { mode: "review", objectId: episodes[1].id, beatId: "beat-4" },
    });
    expect(underlyingHref).toContain("&returnBeat=beat-4");
    expect(underlyingHref).not.toContain("#");
    const route = parseAnalysisRouteContext(underlyingHref);
    const { rerender } = render(<Review review={richReview} route={route} />);
    expect(scroll).toHaveBeenCalledTimes(1);
    expect(scroll).toHaveBeenCalledWith({ behavior: "instant", block: "start" });
    expect(scroll.mock.instances[0]).toBe(document.getElementById("beat-4"));

    const panelRoute = parseAnalysisRouteContext(`${underlyingHref}&panel=${panel}`);
    rerender(<Review review={richReview} route={panelRoute} />);
    expect(scroll).toHaveBeenCalledTimes(1);

    const closeHref = toPanelCloseHref(panelRoute);
    expect(closeHref).toBe(underlyingHref);
    rerender(<Review review={richReview} route={parseAnalysisRouteContext(closeHref)} />);
    expect(scroll).toHaveBeenCalledTimes(1);
  },
);

it.each(["evidence", "ask"] as const)(
  "defers initial Review beat restoration until a directly loaded %s panel closes",
  (panel) => {
    const scroll = vi.fn();
    Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true, value: scroll });
    const route: AnalysisRouteContext = {
      ...reviewRoute,
      returnTarget: { mode: "review", objectId: episodes[1].id, beatId: "beat-4" },
      panel,
    };
    const { rerender } = render(<Review review={richReview} route={route} />);
    expect(scroll).not.toHaveBeenCalled();

    rerender(<Review review={richReview} route={parseAnalysisRouteContext(toPanelCloseHref(route))} />);
    expect(scroll).toHaveBeenCalledTimes(1);
    expect(scroll.mock.instances[0]).toBe(document.getElementById("beat-4"));
  },
);

it.each([
  { mode: "review" as const, objectId: episodes[0].id, beatId: "beat-4" },
  { mode: "review" as const, objectId: episodes[1].id, beatId: "foreign-beat" },
])("ignores a return beat outside the active episode: $objectId/$beatId", (returnTarget) => {
  const scroll = vi.fn();
  Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true, value: scroll });
  render(<Review review={richReview} route={{ ...reviewRoute, returnTarget }} />);
  expect(scroll).not.toHaveBeenCalled();
});

it("keeps learning navigation separate from the fixed chronological rail", () => {
  render(
    <Review
      review={richReview}
      route={reviewRoute}
    />,
  );

  expect(screen.getByText("Case 1 of 4")).toBeVisible();
  expect(screen.getByText(/why this case/i)).toBeVisible();
  expect(screen.getAllByTestId("review-beat")).toHaveLength(6);
  expect(
    screen.getByRole("link", { name: /earlier · 08:20/i }),
  ).toBeVisible();
  expect(screen.getByTestId("chronological-rail")).toHaveTextContent(
    /E1.*E2.*E3.*E4/,
  );
  expect(
    within(screen.getByRole("navigation", { name: /learning order/i }))
      .getAllByRole("link")
      .map((link) => link.textContent),
  ).toEqual(["Case 1", "Case 2", "Case 3", "Case 4"]);
});

it("renders only the active response episode", () => {
  render(
    <Review
      review={richReview}
      active={active}
      evidenceRevision={fixtureResponse.evidenceRevision}
      route={reviewRoute}
    />,
  );

  expect(screen.getByText("Exact conversion cue detail from the response.")).toBeVisible();
  expect(screen.queryByText("Evidence-backed detail for case 1.")).toBeNull();
  expect(screen.queryByText("Evidence-backed detail for case 3.")).toBeNull();
  expect(screen.queryByText("Evidence-backed detail for case 4.")).toBeNull();
});

it("offers restrained text navigation into Explore and Investigation", () => {
  render(
    <Review
      review={richReview}
      active={active}
      evidenceRevision={fixtureResponse.evidenceRevision}
      route={reviewRoute}
    />,
  );

  expect(
    parseAnalysisRouteContext(
      screen.getByRole("link", { name: "Explore this moment" }).getAttribute("href")!,
    ),
  ).toEqual({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "explore",
    selectedObjectId: "trn_000000000000000000000002",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000002",
    },
  });
  expect(
    parseAnalysisRouteContext(
      screen.getByRole("link", { name: "Investigate" }).getAttribute("href")!,
    ),
  ).toEqual({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_000000000000000000000002",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000002",
      beatId: "beat-4",
    },
  });
  expect(screen.queryAllByRole("button")).toHaveLength(0);
});

it("keeps Review utility panels exact and maps the mode switch to the transition", () => {
  const response = {
    ...selectedResponse,
    active: {
      ...selectedResponse.active,
      context: {
        ...selectedResponse.active.context,
        selectedObjectId: episodes[1].id,
      },
      linkedReviewEpisode: episodes[1],
    },
    review: richReview,
  } as unknown as MatchAnalysisResponse;

  render(<MatchAnalysisShell response={response} route={reviewRoute} />);

  for (const [name, panel] of [
    ["Evidence", "evidence"],
    ["Ask", "ask"],
  ] as const) {
    expect(
      parseAnalysisRouteContext(
        screen.getByRole("link", { name }).getAttribute("href")!,
      ),
    ).toEqual({ ...reviewRoute, panel });
  }

  const modes = screen.getByRole("navigation", { name: "Analysis mode" });
  expect(
    parseAnalysisRouteContext(
      within(modes).getByRole("link", { name: "Explore" }).getAttribute("href")!,
    ),
  ).toEqual({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "explore",
    selectedObjectId: "trn_000000000000000000000002",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000002",
    },
  });
});

it("restores the exact Review episode and beat from the mode switch", () => {
  const returnEpisode = episodes[2];
  const response = {
    ...selectedResponse,
    review: richReview,
    active: {
      ...selectedResponse.active,
      context: {
        ...selectedResponse.active.context,
        selectedObjectId: returnEpisode.transitionId,
        interval: returnEpisode.interval,
        questionId: returnEpisode.questionId,
      },
      sourceTransitionId: returnEpisode.transitionId,
      linkedReviewEpisode: returnEpisode,
    },
  } as unknown as MatchAnalysisResponse;
  const exploreFromReview: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "explore",
    selectedObjectId: returnEpisode.transitionId,
    interval: returnEpisode.interval,
    questionId: returnEpisode.questionId,
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: returnEpisode.id,
      beatId: "beat-4",
    },
  };

  render(<MatchAnalysisShell response={response} route={exploreFromReview} />);

  const modes = screen.getByRole("navigation", { name: "Analysis mode" });
  expect(
    parseAnalysisRouteContext(
      within(modes).getByRole("link", { name: "Review · 4" }).getAttribute("href")!,
    ),
  ).toEqual({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "review",
    selectedObjectId: "ep_000000000000000000000003",
    interval: { startMs: 1020000, endMs: 1080000 },
    questionId: "advantage-conversion",
    evidenceRevision: fixtureResponse.evidenceRevision,
    requestedLens: "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: "ep_000000000000000000000003",
      beatId: "beat-4",
    },
  });
});
