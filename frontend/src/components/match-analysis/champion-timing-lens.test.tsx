import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { parseMatchAnalysisResponse } from "../../lib/analysis/response-guards";
import type { ActiveAnalysis, AnalysisRouteContext } from "../../lib/analysis/types";
import { selectedResponse } from "../../test/match-analysis-fixture";
import { ChampionTimingLens } from "./champion-timing-lens";
import { LensStage } from "./lens-stage";

const championPayload = {
  type: "CHAMPION_TIMING" as const,
  knowledgeVersion: "champion-capabilities-16.17-p3-v1",
  capabilities: [{
    category: "ITEM",
    statement: "Its active can slow nearby enemies and grant decaying movement speed per champion hit.",
    assertion: {
      assertionId: "item-6631-active-slow-movement-v1",
      patch: "16.17",
      applicableBuild: "16.17.810.4348",
      entityType: "ITEM",
      entityId: 6631,
      capabilities: ["SLOW", "MOVEMENT_SPEED"],
      prerequisites: ["OWNED", "ACTIVE_USE_REQUIRED", "CHAMPION_HIT_FOR_MOVEMENT"],
      sourceUri: "https://ddragon.leagueoflegends.com/cdn/16.17.1/data/en_US/item.json",
      sourceRevision: "16.17.1",
      reviewerId: "league-analysis-product-owner",
      reviewStatus: "APPROVED",
      reviewRevision: 1,
      assertionMode: "EXPERT_MAINTAINED",
      missingPrerequisites: ["ACTIVE_USE_REQUIRED", "CHAMPION_HIT_FOR_MOVEMENT"],
    },
  }],
  claims: [
    { claimId: "ownership", statement: "Stridebreaker became owned at 13:34.821 (item 6631).",
      assertionMode: "RECONSTRUCTED", evidenceReferences: [selectedResponse.active.claims[0].evidenceReferences[0]], limitations: [] },
    { claimId: "capability", statement: "Its active can slow nearby enemies and grant decaying movement speed per champion hit.",
      assertionMode: "EXPERT_MAINTAINED", evidenceReferences: [], limitations: ["ACTIVE_USE_REQUIRED", "CHAMPION_HIT_FOR_MOVEMENT"] },
    { claimId: "unknown", statement: "Active use, readiness, and causal impact are unknown.",
      assertionMode: "UNKNOWN", evidenceReferences: [], limitations: ["ACTIVE_USE_UNKNOWN", "READINESS_UNKNOWN", "CAUSAL_IMPACT_UNKNOWN"] },
  ],
};

// Missing behavior: distinguish reconstructed ownership, expert knowledge and unknown use.
it("renders a qualified capability receipt with separate assertion labels", () => {
  render(<ChampionTimingLens payload={championPayload} />);
  expect(screen.getByText(/Stridebreaker became owned at 13:34.821/)).toBeVisible();
  expect(screen.getByText(/active use.*unknown/i)).toBeVisible();
  expect(screen.getByText("Reconstructed")).toBeVisible();
  expect(screen.getByText("Expert knowledge")).toBeVisible();
  expect(screen.getByText("Unknown")).toBeVisible();
  expect(screen.queryByText(/caused|wins the fight|best action/i)).not.toBeInTheDocument();
});

// Missing behavior: show maintained provenance and unmet prerequisites with the capability.
it("shows patch, review provenance and explicit active-use conditions", () => {
  render(<ChampionTimingLens payload={championPayload} />);
  expect(screen.getByRole("link", { name: /source revision 16.17.1/i }))
    .toHaveAttribute("href", "https://ddragon.leagueoflegends.com/cdn/16.17.1/data/en_US/item.json");
  expect(screen.getByText(/Patch 16.17.*16.17.810.4348/)).toBeVisible();
  expect(screen.getByText(/league-analysis-product-owner.*revision 1/)).toBeVisible();
  expect(screen.getByText(/active use required/i)).toBeVisible();
  expect(screen.getByText(/champion hits required for movement speed/i)).toBeVisible();
});

// Missing behavior: use this receipt only for the selected Champion/Timing stage.
it("renders inside the selected stage and disappears when Receipt is selected", () => {
  const base = selectedResponse.active as ActiveAnalysis;
  const active = { ...base, lens: championPayload };
  const route: AnalysisRouteContext = {
    matchId: base.context.matchId, focalParticipantId: 6, mode: "investigate",
    selectedObjectId: base.context.selectedObjectId, interval: base.context.interval,
    questionId: base.context.questionId, evidenceRevision: base.context.evidenceRevision,
    requestedLens: "CHAMPION_TIMING",
  };
  const { rerender } = render(<LensStage active={active} route={route} />);
  expect(within(screen.getByTestId("analysis-stage")).getByText(/Stridebreaker became owned/)).toBeVisible();
  rerender(<LensStage active={{ ...base, lens: { type: "RECEIPT", receipt: base.receipt, claims: base.claims } }} route={route} />);
  expect(screen.queryByText(/Stridebreaker became owned/)).not.toBeInTheDocument();
});

// Missing behavior: response validation must reject malformed or unapproved knowledge metadata.
it.each([
  { reviewStatus: "WITHDRAWN" }, { reviewRevision: 0 }, { sourceUri: "javascript:alert(1)" },
  { missingPrerequisites: "ACTIVE_USE_REQUIRED" }, { assertionMode: "OBSERVED" },
])("rejects malformed capability metadata %j", (invalid) => {
  expect(() => parseMatchAnalysisResponse({
    ...selectedResponse,
    active: { ...selectedResponse.active, lens: {
      ...championPayload,
      capabilities: [{ ...championPayload.capabilities[0], assertion: {
        ...championPayload.capabilities[0].assertion, ...invalid,
      } }],
    } },
  })).toThrow("INVALID_MATCH_ANALYSIS_RESPONSE");
});
