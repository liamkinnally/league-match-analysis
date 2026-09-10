import { render, screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { selectedResponse } from "../../test/match-analysis-fixture";
import type {
  ActiveAnalysis,
  AnalysisRouteContext,
} from "../../lib/analysis/types";
import { Investigation } from "./investigation";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
}));

const questionText = "What changed?";
const activeSequence = {
  ...selectedResponse.active,
  supportedQuestions: [
    {
      id: selectedResponse.active.context.questionId,
      prompt: questionText,
      answerMode: "OBSERVED",
    },
  ],
} as ActiveAnalysis;

const routeFromReview: AnalysisRouteContext = {
  matchId: activeSequence.context.matchId,
  focalParticipantId: activeSequence.context.focalParticipantId,
  mode: "investigate",
  selectedObjectId: activeSequence.context.selectedObjectId,
  interval: activeSequence.context.interval,
  questionId: activeSequence.context.questionId,
  evidenceRevision: activeSequence.context.evidenceRevision,
  requestedLens: "SEQUENCE",
  returnTarget: {
    mode: "review",
    objectId: activeSequence.linkedReviewEpisode?.id,
    beatId: "beat-4",
  },
};

const canonicalOrigin =
  `/matches/NA1_9000000001?focus=6&mode=investigate` +
  `&object=trn_000000000000000000000002&start=780275&end=900291` +
  `&question=advantage-conversion&evidence=${activeSequence.context.evidenceRevision}` +
  `&lens=SEQUENCE&returnMode=review` +
  `&returnObject=ep_000000000000000000000002&returnBeat=beat-4`;

it("keeps the Investigation shell stable for a Review-origin selection", () => {
  render(<Investigation active={activeSequence} route={routeFromReview} />);

  expect(screen.getByRole("heading", { name: questionText })).toBeVisible();
  expect(screen.getByText("13:00–15:00")).toBeVisible();
  expect(screen.getByTestId("analysis-stage")).toHaveAttribute(
    "data-lens",
    "SEQUENCE",
  );
  expect(screen.getByTestId("state-receipt")).toBeVisible();
  expect(screen.getByRole("link", { name: /back to review/i })).toHaveAttribute(
    "href",
    expect.stringContaining("returnBeat=beat-4"),
  );
  expect(
    screen.queryByRole("link", { name: /champion timing/i }),
  ).not.toBeInTheDocument();
});

it("generates only response-authoritative lens controls with preserved context", () => {
  render(<Investigation active={activeSequence} route={routeFromReview} />);

  const navigation = screen.getByRole("navigation", { name: /analysis lenses/i });
  expect(within(navigation).getAllByRole("link")).toHaveLength(3);
  expect(within(navigation).getByRole("link", { name: "Sequence" })).toHaveAttribute(
    "aria-current",
    "page",
  );

  const stateHref = within(navigation)
    .getByRole("link", { name: "State" })
    .getAttribute("href");
  expect(stateHref).toContain(`object=${activeSequence.context.selectedObjectId}`);
  expect(stateHref).toContain("start=780275");
  expect(stateHref).toContain("end=900291");
  expect(stateHref).toContain("question=advantage-conversion");
  expect(stateHref).toContain("evidence=ev_");
  expect(stateHref).toContain("returnMode=review");
  expect(stateHref).toContain("returnBeat=beat-4");
  expect(stateHref).toContain("lens=STATE");
});

it("opens temporary Evidence and Ask links without changing canonical context", () => {
  render(<Investigation active={activeSequence} route={routeFromReview} />);

  const evidenceHref = screen
    .getByRole("link", { name: "Evidence" })
    .getAttribute("href");
  const askHref = screen.getByRole("link", { name: "Ask" }).getAttribute("href");

  expect(evidenceHref).toContain("panel=evidence");
  expect(askHref).toContain("panel=ask");
  for (const href of [evidenceHref, askHref]) {
    expect(href).toContain("lens=SEQUENCE");
    expect(href).toContain("returnMode=review");
    expect(href).toContain("returnBeat=beat-4");
  }
});

it("removes only panel when Evidence closes", () => {
  render(
    <Investigation
      active={activeSequence}
      route={{ ...routeFromReview, panel: "evidence" }}
    />,
  );

  expect(screen.getByRole("dialog", { name: /evidence/i })).toBeVisible();
  expect(screen.getByRole("link", { name: /close evidence/i })).toHaveAttribute(
    "href",
    canonicalOrigin,
  );
});

it("removes only panel when Ask closes", () => {
  render(
    <Investigation
      active={activeSequence}
      route={{ ...routeFromReview, panel: "ask" }}
    />,
  );

  expect(screen.getByRole("dialog", { name: /ask/i })).toBeVisible();
  expect(screen.getByRole("link", { name: /close ask/i })).toHaveAttribute(
    "href",
    canonicalOrigin,
  );
});

it("generates a context-preserving Match Arc back link for an Explore origin", () => {
  render(
    <Investigation
      active={activeSequence}
      route={{
        ...routeFromReview,
        returnTarget: {
          mode: "explore",
          objectId: activeSequence.sourceTransitionId,
        },
      }}
    />,
  );

  const href = screen
    .getByRole("link", { name: /back to match arc/i })
    .getAttribute("href");
  expect(href).toContain(`object=${activeSequence.sourceTransitionId}`);
  expect(href).toContain("start=780275");
  expect(href).toContain("end=900291");
  expect(href).toContain("question=advantage-conversion");
  expect(href).toContain("lens=SEQUENCE");
  expect(href).toContain("returnMode=explore");
  expect(href).not.toContain("panel=");
});

it("clears selection when returning to the Match Arc without a return target", () => {
  render(
    <Investigation
      active={activeSequence}
      route={{
        ...routeFromReview,
        mode: "explore",
        returnTarget: undefined,
      }}
    />,
  );

  expect(
    screen.getByRole("link", { name: /back to match arc/i }),
  ).toHaveAttribute("href", "/matches/NA1_9000000001?focus=6");
});
