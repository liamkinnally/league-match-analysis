import { render, screen, within } from "@testing-library/react";
import { beforeEach, expect, it } from "vitest";
import {
  fixtureResponse,
  lensFixtures,
  selectedResponse,
} from "../../test/match-analysis-fixture";
import type {
  AnalysisRouteContext,
  MatchAnalysisResponse,
} from "../../lib/analysis/types";
import { MatchAnalysisShell } from "./match-analysis-shell";

const exploreRoute: AnalysisRouteContext = {
  matchId: fixtureResponse.match.matchId,
  focalParticipantId: 6,
  mode: "explore",
};

const selectedRoute: AnalysisRouteContext = {
  ...exploreRoute,
  selectedObjectId: String(selectedResponse.active.context.selectedObjectId),
  interval: selectedResponse.active.context.interval,
  questionId: selectedResponse.active.context.questionId,
  evidenceRevision: selectedResponse.active.context.evidenceRevision,
  requestedLens: "SEQUENCE",
};

const reviewRoute: AnalysisRouteContext = {
  ...selectedRoute,
  mode: "review",
};

beforeEach(() => localStorage.clear());

it("renders the chronological Explore arc and response-authoritative Review count", () => {
  render(
    <MatchAnalysisShell
      response={fixtureResponse as MatchAnalysisResponse}
      route={exploreRoute}
    />,
  );

  expect(
    screen.getByRole("heading", { name: /how this match changed/i }),
  ).toBeVisible();
  expect(screen.getByRole("link", { name: /review · 4/i })).toHaveAttribute(
    "href",
    expect.stringContaining("mode=review"),
  );
  expect(screen.getAllByRole("link", { name: /inspect/i })).toHaveLength(4);

  const markers = screen.getAllByTestId("transition-marker");
  expect(markers.map((marker) => marker.textContent)).toEqual([
    expect.stringContaining("E1"),
    expect.stringContaining("E2"),
    expect.stringContaining("E3"),
    expect.stringContaining("E4"),
  ]);
});

it("renders observation before optional interpretation without score-axis semantics", () => {
  render(
    <MatchAnalysisShell
      response={fixtureResponse as MatchAnalysisResponse}
      route={exploreRoute}
    />,
  );

  const marker = screen.getAllByTestId("transition-marker")[0];
  const observation = within(marker).getByText("Observed transition E1");
  const interpretation = within(marker).getByText(
    "Interpretation for transition E1.",
  );
  expect(
    observation.compareDocumentPosition(interpretation) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(screen.queryByRole("img", { name: /score|y-axis/i })).toBeNull();
});

it("renders the selected transition receipt with timestamped samples", () => {
  render(
    <MatchAnalysisShell
      response={selectedResponse as MatchAnalysisResponse}
      route={selectedRoute}
    />,
  );

  expect(screen.getByText("Team lead +819 → +3,142")).toBeVisible();
  expect(screen.getByText(/sampled at/i)).toBeVisible();
  expect(screen.getByTestId("state-receipt")).toBeVisible();
  expect(screen.getByRole("heading", { name: "What changed?" })).toBeVisible();
});

it("identifies the antecedent and flagged consequence receipt samples", () => {
  render(
    <MatchAnalysisShell
      response={selectedResponse as MatchAnalysisResponse}
      route={selectedRoute}
    />,
  );

  const receipt = screen.getByTestId("state-receipt");
  expect(within(receipt).getByText("Before · Antecedent evidence")).toBeVisible();
  expect(within(receipt).getByText("After · Consequence evidence")).toBeVisible();
});

it("renders a receipt for the Review case linked by backend data", () => {
  render(
    <MatchAnalysisShell
      response={selectedResponse as MatchAnalysisResponse}
      route={reviewRoute}
    />,
  );

  expect(screen.getByText("Case 2 of 4")).toBeVisible();
  expect(
    screen.getByText(fixtureResponse.review.episodes[1].selectionRationale),
  ).toBeVisible();
  expect(screen.getByTestId("state-receipt")).toBeVisible();
  expect(screen.getByRole("heading", { name: "What changed?" })).toBeVisible();
});

it("renders the shared Investigation for an explicit Review-origin route", () => {
  render(
    <MatchAnalysisShell
      response={selectedResponse as MatchAnalysisResponse}
      route={{
        ...selectedRoute,
        mode: "investigate",
        returnTarget: {
          mode: "review",
          objectId: selectedResponse.active.linkedReviewEpisode!.id,
          beatId: "beat-4",
        },
      }}
    />,
  );

  expect(screen.getByRole("heading", { name: "What changed?" })).toBeVisible();
  expect(screen.getByRole("link", { name: /back to review/i })).toHaveAttribute(
    "href",
    expect.stringContaining("returnBeat=beat-4"),
  );
  expect(screen.queryByRole("heading", { name: /match arc/i })).toBeNull();
});

it("does not duplicate the receipt when Review uses the State lens", () => {
  const stateLens = lensFixtures.find((lens) => lens.type === "STATE");
  if (!stateLens) throw new Error("Missing STATE fixture");
  const response = {
    ...selectedResponse,
    active: {
      ...selectedResponse.active,
      context: {
        ...selectedResponse.active.context,
        selectedLens: "STATE",
      },
      lens: stateLens,
    },
  } as MatchAnalysisResponse;

  render(<MatchAnalysisShell response={response} route={{ ...reviewRoute, requestedLens: "STATE" }} />);

  expect(screen.getByText("Case 2 of 4")).toBeVisible();
  expect(screen.getAllByTestId("state-receipt")).toHaveLength(1);
});

it("does not attach an unlinked active receipt to the fallback Review case", () => {
  const unlinkedResponse = {
    ...selectedResponse,
    active: {
      ...selectedResponse.active,
      linkedReviewEpisode: null,
    },
  } as MatchAnalysisResponse;

  render(<MatchAnalysisShell response={unlinkedResponse} route={reviewRoute} />);

  expect(screen.getByText("Case 1 of 4")).toBeVisible();
  expect(
    screen.getByText(fixtureResponse.review.episodes[0].selectionRationale),
  ).toBeVisible();
  expect(screen.queryByTestId("state-receipt")).toBeNull();
  expect(screen.getByText(/open this case.*timestamped receipt/i)).toBeVisible();
});

it("keeps a directly opened calm Review truthful and navigable", () => {
  render(
    <MatchAnalysisShell
      response={fixtureResponse as MatchAnalysisResponse}
      route={{ ...exploreRoute, mode: "review" }}
    />,
  );

  expect(screen.getByText("Case 1 of 4")).toBeVisible();
  expect(screen.getByText(/open this case.*timestamped receipt/i)).toBeVisible();
  expect(screen.getByRole("link", { name: /return to match arc/i })).toHaveAttribute(
    "href",
    "/matches/NA1_9000000001?focus=6",
  );
});

it("uses the actual response length for a sparse Review", () => {
  const sparse = {
    ...fixtureResponse,
    review: {
      ...fixtureResponse.review,
      episodes: fixtureResponse.review.episodes.slice(0, 2),
      learningOrder: fixtureResponse.review.learningOrder.slice(0, 2),
      chronologicalOrder: fixtureResponse.review.chronologicalOrder.slice(0, 2),
    },
  } as MatchAnalysisResponse;

  render(<MatchAnalysisShell response={sparse} route={exploreRoute} />);

  expect(screen.getByRole("link", { name: "Review · 2" })).toBeVisible();
});

it("renders a truthful empty state when no transitions are selected", () => {
  const empty = {
    ...fixtureResponse,
    arc: {
      ...fixtureResponse.arc,
      transitions: [],
      eligibleUnselectedCount: 0,
      emptyReason: "No material transitions were supported by the available evidence.",
    },
  } as MatchAnalysisResponse;

  render(<MatchAnalysisShell response={empty} route={exploreRoute} />);

  expect(
    screen.getByText(
      "No material transitions were supported by the available evidence.",
    ),
  ).toBeVisible();
  expect(screen.queryByRole("link", { name: /inspect/i })).toBeNull();
});
