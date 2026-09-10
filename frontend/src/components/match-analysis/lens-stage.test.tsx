import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import {
  lensFixtures,
  selectedResponse,
} from "../../test/match-analysis-fixture";
import type {
  ActiveAnalysis,
  AnalysisLens,
  AnalysisRouteContext,
  LensType,
} from "../../lib/analysis/types";
import { LensStage } from "./lens-stage";

const baseActive = selectedResponse.active as ActiveAnalysis;
const baseRoute: AnalysisRouteContext = {
  matchId: baseActive.context.matchId,
  focalParticipantId: baseActive.context.focalParticipantId,
  mode: "investigate",
  selectedObjectId: baseActive.context.selectedObjectId,
  interval: baseActive.context.interval,
  questionId: baseActive.context.questionId,
  evidenceRevision: baseActive.context.evidenceRevision,
  requestedLens: "SEQUENCE",
  returnTarget: { mode: "explore", objectId: baseActive.sourceTransitionId },
};

function lens(type: LensType): AnalysisLens {
  const value = lensFixtures.find((candidate) => candidate.type === type);
  if (!value) throw new Error(`Missing ${type} lens fixture`);
  return value as AnalysisLens;
}

function activeWith(payload: AnalysisLens): ActiveAnalysis {
  return {
    ...baseActive,
    context: {
      ...baseActive.context,
      selectedLens: payload.type,
    },
    lens: payload,
  };
}

it("renders timestamped Map points without implying an unobserved path", () => {
  render(<LensStage active={activeWith(lens("MAP"))} route={baseRoute} />);

  const stage = screen.getByTestId("analysis-stage");
  expect(stage).toHaveAttribute("data-lens", "MAP");
  expect(within(stage).getByText("13:00")).toBeVisible();
  expect(within(stage).getByText("Focal participant position")).toBeVisible();
  expect(stage.querySelector("path")).toBeNull();
});

it("renders overlapping Sequence anchors as parallel bands", () => {
  const sequence = lens("SEQUENCE");
  if (sequence.type !== "SEQUENCE") throw new Error("Expected sequence fixture");
  const parallelSequence = {
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
    relations: [
      {
        fromBandId: "band-1",
        toBandId: "band-1",
        relation: "CO_OCCURS",
        evidenceReferences: sequence.claims[0].evidenceReferences,
      },
    ],
  } satisfies AnalysisLens;

  render(<LensStage active={activeWith(parallelSequence)} route={baseRoute} />);

  const band = screen.getByTestId("sequence-band");
  expect(band).toHaveAttribute("data-parallel", "true");
  expect(within(band).getAllByRole("listitem")).toHaveLength(2);
  expect(screen.getByText(/parallel observations/i)).toBeVisible();
});

it("renders State sample times and their evidence roles", () => {
  render(<LensStage active={activeWith(lens("STATE"))} route={baseRoute} />);

  const receipt = screen.getByTestId("state-receipt");
  expect(within(receipt).getByText("13:00")).toBeVisible();
  expect(within(receipt).getByText("15:00")).toBeVisible();
  expect(within(receipt).getByText(/antecedent evidence/i)).toBeVisible();
  expect(within(receipt).getByText(/consequence evidence/i)).toBeVisible();
});

it("describes Transfer category deltas as co-occurring and never causal", () => {
  render(<LensStage active={activeWith(lens("TRANSFER"))} route={baseRoute} />);

  const stage = screen.getByTestId("analysis-stage");
  expect(within(stage).getByText(/category delta/i)).toBeVisible();
  expect(within(stage).getByText(/co-occurred/i)).toBeVisible();
  expect(within(stage).queryByText(/caused/i)).not.toBeInTheDocument();
});

it("renders the truthful Receipt fallback for an unavailable requested lens", () => {
  const receiptActive = {
    ...activeWith(lens("RECEIPT")),
    context: {
      ...baseActive.context,
      selectedLens: "RECEIPT" as const,
      availableLenses: ["SEQUENCE", "STATE", "TRANSFER", "RECEIPT"] as LensType[],
    },
    limitations: ["REQUESTED_LENS_UNAVAILABLE"],
  };

  render(
    <LensStage
      active={receiptActive}
      route={{ ...baseRoute, requestedLens: "CHAMPION_TIMING" }}
    />,
  );

  expect(screen.getByTestId("analysis-stage")).toHaveAttribute(
    "data-lens",
    "RECEIPT",
  );
  expect(screen.getByTestId("state-receipt")).toBeVisible();
  expect(screen.getByText(/requested lens is not supported/i)).toBeVisible();
  expect(
    screen.queryByRole("link", { name: /champion timing/i }),
  ).not.toBeInTheDocument();
});
