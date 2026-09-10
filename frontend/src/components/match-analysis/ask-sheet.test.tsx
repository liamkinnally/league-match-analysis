import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { selectedResponse } from "../../test/match-analysis-fixture";
import type { ActiveAnalysis } from "../../lib/analysis/types";
import { AskSheet } from "./ask-sheet";
import { StateReceipt } from "./state-receipt";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

beforeEach(() => replace.mockClear());

const observed = selectedResponse.active.claims[0];
const unknown = {
  ...observed,
  claimId: "claim-unknown",
  statement: "Opponent position remains unknown in this interval.",
  assertionMode: "UNKNOWN",
  evidenceReferences: [],
  limitations: ["OPPONENT_POSITION_UNAVAILABLE"],
};
const activeSequence = {
  ...selectedResponse.active,
  claims: [observed, unknown],
  limitations: ["POSITION_COVERAGE_INCOMPLETE"],
  supportedQuestions: [
    { id: "what-changed", prompt: "What changed?", answerMode: "RECEIPT" },
    {
      id: "why-selected",
      prompt: "Why was this selected?",
      answerMode: "SELECTION_RATIONALE",
    },
    {
      id: "what-unknown",
      prompt: "What remains unknown?",
      answerMode: "UNKNOWN",
    },
  ],
} as ActiveAnalysis;

const closeHref =
  "/matches/NA1_9000000001?focus=6&mode=investigate&object=trn_000000000000000000000002&start=780275&end=900291&question=advantage-conversion&evidence=ev_111&lens=SEQUENCE&returnMode=review&returnBeat=beat-4";

it("offers only response-supported contextual questions without a composer", () => {
  render(<AskSheet active={activeSequence} closeHref={closeHref} />);

  const sheet = screen.getByRole("dialog", { name: /ask/i });
  expect(within(sheet).getByRole("heading", { name: "Ask" })).toBeVisible();
  const currentQuestion = within(sheet).getByText("Current question").closest("p");
  expect(currentQuestion).not.toBeNull();
  expect(within(currentQuestion!).getByText("What changed?")).toBeVisible();
  expect(within(sheet).getByText("13:00–15:00")).toBeVisible();
  expect(
    within(sheet).getByRole("button", { name: "What changed?" }),
  ).toBeEnabled();
  expect(
    within(sheet).getByRole("button", { name: "Why was this selected?" }),
  ).toBeEnabled();
  expect(
    within(sheet).getByRole("button", { name: "What remains unknown?" }),
  ).toBeEnabled();
  expect(within(sheet).queryByRole("textbox")).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: /close ask/i })).toHaveAttribute(
    "href",
    closeHref,
  );
});

it("selects only the response field bound to each supported answer mode", () => {
  render(<AskSheet active={activeSequence} closeHref={closeHref} />);

  fireEvent.click(screen.getByRole("button", { name: "What changed?" }));
  expect(screen.getByText("Team lead +819 → +3,142")).toBeVisible();
  expect(
    screen.queryByText(activeSequence.linkedReviewEpisode!.selectionRationale),
  ).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "Why was this selected?" }));
  expect(
    screen.getByText(activeSequence.linkedReviewEpisode!.selectionRationale),
  ).toBeVisible();
  expect(screen.queryByText("Team lead +819 → +3,142")).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "What remains unknown?" }));
  expect(screen.getByText(unknown.statement)).toBeVisible();
  expect(screen.getByText("POSITION_COVERAGE_INCOMPLETE")).toBeVisible();
  expect(
    screen.queryByText(activeSequence.linkedReviewEpisode!.selectionRationale),
  ).not.toBeInTheDocument();
});

it("never requests generated prose when a supported question is selected", () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  render(<AskSheet active={activeSequence} closeHref={closeHref} />);

  for (const prompt of activeSequence.supportedQuestions.map(
    (question) => question.prompt,
  )) {
    fireEvent.click(screen.getByRole("button", { name: prompt }));
  }

  expect(fetch).not.toHaveBeenCalled();
  expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
});

it("states the evidence boundary when no additional questions are supported", () => {
  render(
    <AskSheet
      active={{ ...activeSequence, supportedQuestions: [] }}
      closeHref={closeHref}
    />,
  );

  expect(
    screen.getByText(
      "No additional evidence-backed questions are available for this transition",
    ),
  ).toBeVisible();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
});

it("moves focus to the Ask heading and closes on Escape", () => {
  render(<AskSheet active={activeSequence} closeHref={closeHref} />);

  const heading = screen.getByRole("heading", { name: "Ask" });
  expect(heading).toHaveAttribute("tabindex", "-1");
  expect(heading).toHaveFocus();

  fireEvent.keyDown(window, { key: "Escape" });
  expect(replace).toHaveBeenCalledWith(closeHref);
});

it("uses a distinct receipt heading identity inside the sheet", () => {
  render(
    <>
      <StateReceipt receipt={activeSequence.receipt} />
      <AskSheet active={activeSequence} closeHref={closeHref} />
    </>,
  );

  fireEvent.click(screen.getByRole("button", { name: "What changed?" }));

  expect(document.querySelectorAll("#state-receipt-title")).toHaveLength(1);
  expect(document.querySelectorAll("#ask-state-receipt-title")).toHaveLength(1);
});

it("does not falsely claim modal semantics for the Ask aside", () => {
  render(<AskSheet active={activeSequence} closeHref={closeHref} />);

  expect(screen.getByRole("dialog", { name: /ask/i })).not.toHaveAttribute(
    "aria-modal",
  );
});
