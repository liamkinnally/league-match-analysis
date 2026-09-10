import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { selectedResponse } from "../../test/match-analysis-fixture";
import type { ActiveAnalysis } from "../../lib/analysis/types";
import { EvidenceSheet } from "./evidence-sheet";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

beforeEach(() => replace.mockClear());

const observed = selectedResponse.active.claims[0];
const activeSequence = {
  ...selectedResponse.active,
  claims: [
    observed,
    {
      ...observed,
      claimId: "claim-reconstructed",
      statement: "The sequence was reconstructed from bounded observations.",
      assertionMode: "RECONSTRUCTED",
    },
    {
      ...observed,
      claimId: "claim-unknown",
      statement: "The missing position predicate remains unknown.",
      assertionMode: "UNKNOWN",
      evidenceReferences: [],
      limitations: ["MISSING_POSITION_PREDICATE"],
    },
    {
      ...observed,
      claimId: "claim-interpreted",
      statement: "This interpretation is bounded by the observed sequence.",
      assertionMode: "INTERPRETED",
    },
    {
      ...observed,
      claimId: "claim-expert-maintained",
      statement: "This timing context comes from maintained expert knowledge.",
      assertionMode: "EXPERT_MAINTAINED",
    },
  ],
  limitations: ["CONFLICTING_RELATION", "SUPPRESSED_RELATION"],
  lens: {
    ...selectedResponse.active.lens,
    relations: [
      {
        fromBandId: "band-1",
        toBandId: "band-2",
        relation: "CONFLICTING_RELATION",
        evidenceReferences: [observed.evidenceReferences[0]],
      },
      {
        fromBandId: "band-2",
        toBandId: "band-3",
        relation: "SUPPRESSED_RELATION",
        evidenceReferences: [],
      },
    ],
  },
  evidence: {
    ...selectedResponse.active.evidence,
    claimIds: [
      "claim-1",
      "claim-reconstructed",
      "claim-unknown",
      "claim-interpreted",
      "claim-expert-maintained",
    ],
    coverage: [
      {
        ...selectedResponse.active.evidence.coverage[0],
        sourceCaptureId: "coverage-capture-unique",
      },
      {
        signal: "POSITION_PREDICATE",
        sourceKind: "MATCH_TIMELINE",
        status: "UNKNOWN",
        representedStartMs: null,
        representedEndMs: null,
        sourceCaptureId: null,
        sourceRecordId: "missing-position-predicate",
        methodVersion: "timeline-v1",
      },
    ],
  },
} as ActiveAnalysis;

const closeHref =
  "/matches/NA1_9000000001?focus=6&mode=investigate&object=trn_000000000000000000000002&start=780275&end=900291&question=advantage-conversion&evidence=ev_111&lens=SEQUENCE&returnMode=review&returnBeat=beat-4";

it("groups evidence claims by assertion mode without dumping payload JSON", () => {
  render(<EvidenceSheet active={activeSequence} closeHref={closeHref} />);

  const sheet = screen.getByRole("dialog", { name: /evidence/i });
  expect(within(sheet).getByRole("heading", { name: "Evidence" })).toBeVisible();
  expect(within(sheet).getByRole("heading", { name: "Observed" })).toBeVisible();
  expect(within(sheet).getByRole("heading", { name: "Reconstructed" })).toBeVisible();
  expect(within(sheet).getByRole("heading", { name: "Interpretation" })).toBeVisible();
  expect(within(sheet).getByRole("heading", { name: "Expert knowledge" })).toBeVisible();
  expect(within(sheet).getByRole("heading", { name: "Unknown" })).toBeVisible();
  expect(
    within(sheet).getByText(
      "This interpretation is bounded by the observed sequence.",
    ),
  ).toBeVisible();
  expect(
    within(sheet).getByText(
      "This timing context comes from maintained expert knowledge.",
    ),
  ).toBeVisible();
  expect(screen.queryByText(/payload_json/i)).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: /close evidence/i })).toHaveAttribute(
    "href",
    closeHref,
  );
});

it("discloses exact provenance identity, represented time, and versions", () => {
  render(<EvidenceSheet active={activeSequence} closeHref={closeHref} />);

  expect(screen.getByText("MATCH_TIMELINE")).toBeVisible();
  expect(screen.getByText("capture-1")).toBeVisible();
  expect(screen.getAllByText("frame-10:event-1").length).toBeGreaterThan(0);
  expect(screen.getByText("13:00")).toBeVisible();
  expect(screen.getAllByText("timeline-v1").length).toBeGreaterThan(0);
  expect(screen.getByText("transition-p3-v1")).toBeVisible();
  expect(screen.getByText("receipt-p3-v1")).toBeVisible();
  expect(screen.getByText("lens-p3-v1")).toBeVisible();
  expect(screen.getByText("episode-p3-v1")).toBeVisible();
  expect(screen.getByText("review-order-p3-v1")).toBeVisible();
  expect(screen.getByText("champion-knowledge-p3-v1")).toBeVisible();
});

it("discloses coverage capture identity and marks a missing capture unavailable", () => {
  render(<EvidenceSheet active={activeSequence} closeHref={closeHref} />);

  const coverage = screen
    .getByRole("heading", { name: "Coverage and missing predicates" })
    .closest("section");
  expect(coverage).not.toBeNull();

  const observedCoverage = within(coverage!).getByText("TEAM_GOLD").closest("div");
  expect(observedCoverage).not.toBeNull();
  expect(
    within(observedCoverage!).getByText("coverage-capture-unique"),
  ).toBeVisible();

  const unknownCoverage = within(coverage!)
    .getByText("POSITION_PREDICATE")
    .closest("div");
  expect(unknownCoverage).not.toBeNull();
  expect(within(unknownCoverage!).getByText("Unavailable")).toBeVisible();
});

it("keeps missing predicates and conflicting or suppressed relations explicit", () => {
  render(<EvidenceSheet active={activeSequence} closeHref={closeHref} />);

  expect(screen.getAllByText("MISSING_POSITION_PREDICATE").length).toBeGreaterThan(0);
  expect(screen.getAllByText("CONFLICTING_RELATION").length).toBeGreaterThan(0);
  expect(screen.getAllByText("SUPPRESSED_RELATION").length).toBeGreaterThan(0);
  expect(screen.getByText("POSITION_PREDICATE")).toBeVisible();
  expect(screen.getByText("missing-position-predicate")).toBeVisible();
});

it("moves focus to the Evidence heading and closes on Escape", () => {
  render(<EvidenceSheet active={activeSequence} closeHref={closeHref} />);

  const heading = screen.getByRole("heading", { name: "Evidence" });
  expect(heading).toHaveAttribute("tabindex", "-1");
  expect(heading).toHaveFocus();

  fireEvent.keyDown(window, { key: "Escape" });
  expect(replace).toHaveBeenCalledWith(closeHref);
});

it("does not falsely claim modal semantics for the Evidence aside", () => {
  render(<EvidenceSheet active={activeSequence} closeHref={closeHref} />);

  expect(screen.getByRole("dialog", { name: /evidence/i })).not.toHaveAttribute(
    "aria-modal",
  );
});
