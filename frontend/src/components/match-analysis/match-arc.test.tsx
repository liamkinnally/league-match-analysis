import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { fixtureResponse } from "../../test/match-analysis-fixture";
import type {
  AnalysisRouteContext,
  MatchAnalysisResponse,
} from "../../lib/analysis/types";
import { MatchArc } from "./match-arc";
import { exploreThisMomentHref, parseAnalysisRouteContext } from "../../lib/analysis/route-context";

const route: AnalysisRouteContext = {
  matchId: fixtureResponse.match.matchId,
  focalParticipantId: 6,
  mode: "explore",
};

const props = {
  arc: fixtureResponse.arc as MatchAnalysisResponse["arc"],
  evidenceRevision: fixtureResponse.evidenceRevision,
  focalTeamId: 200,
  route,
};

beforeEach(() => localStorage.clear());

it("clears a different transition's Review origin while preserving the same transition return", () => {
  const episode = fixtureResponse.review.episodes[1] as unknown as MatchAnalysisResponse["review"]["episodes"][number];
  const fromReview = parseAnalysisRouteContext(exploreThisMomentHref(episode, {
    ...route,
    mode: "review",
    selectedObjectId: episode.id,
    interval: episode.interval,
    questionId: episode.questionId,
    evidenceRevision: props.evidenceRevision,
    requestedLens: "STATE",
    returnTarget: { mode: "review", objectId: episode.id, beatId: "beat-2" },
  }));
  render(<MatchArc {...props} route={fromReview} />);
  const href = (label: string) => screen.getByRole("link", { name: new RegExp(label) }).getAttribute("href")!;
  const same = parseAnalysisRouteContext(href("Inspect E2:"));
  const different = parseAnalysisRouteContext(href("Inspect E1:"));

  expect(same.returnTarget).toEqual({ mode: "review", objectId: episode.id, beatId: "beat-2" });
  expect(different.returnTarget).toBeUndefined();
  expect(different).toMatchObject({
    matchId: route.matchId, focalParticipantId: 6, mode: "explore",
    selectedObjectId: "trn_000000000000000000000001",
    interval: { startMs: 480_000, endMs: 540_000 },
    evidenceRevision: props.evidenceRevision,
  });
});

it.each(["MAP", "STATE", "RECEIPT"])("requests the server primary %s on initial selection", (primaryLens) => {
  const arc = structuredClone(props.arc);
  Object.assign(arc.transitions[0], { primaryLens });
  render(<MatchArc {...props} arc={arc} />);
  const href = screen.getByRole("link", { name: /Inspect E1:/ }).getAttribute("href")!;
  expect(parseAnalysisRouteContext(href).requestedLens).toBe(primaryLens);
});

it("preserves an explicit lens while inspecting the same selected transition", () => {
  render(<MatchArc {...props} route={{ ...route,
    selectedObjectId: props.arc.transitions[0].transitionId,
    interval: props.arc.transitions[0].interval,
    questionId: props.arc.transitions[0].questionId,
    evidenceRevision: props.evidenceRevision,
    requestedLens: "RECEIPT",
  }} />);
  const href = screen.getByRole("link", { name: /Inspect E1:/ }).getAttribute("href")!;
  expect(parseAnalysisRouteContext(href).requestedLens).toBe("RECEIPT");
});

it("includes the visible episode label and transition title in the inspect link name", () => {
  render(<MatchArc {...props} />);
  const link = screen.getByRole("link", { name: /A team fight widened the lead/ });
  expect(link).toHaveTextContent("Inspect E2");
  expect(link).toHaveAccessibleName("Inspect E2: A team fight widened the lead");
});

it("labels a descriptor-free observed anchor by its recorded kind", () => {
  const arc = structuredClone(props.arc);
  Object.assign(arc.transitions[0].anchors[0], { kind: "CHAMPION_KILL", descriptor: null });
  render(<MatchArc {...props} arc={arc} />);
  expect(screen.getByText("CHAMPION KILL")).toBeVisible();
});

it("explains the first real marker and remembers dismissal only by versioned key", () => {
  render(<MatchArc {...props} />);

  expect(
    screen.getByText("An evidence-backed transition, not a score"),
  ).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Got it" }));

  expect(
    screen.queryByText("An evidence-backed transition, not a score"),
  ).toBeNull();
  expect(localStorage).toHaveLength(1);
  expect(localStorage.getItem("league-analysis:arc-primer:v1")).toBe("1");
});

it("keeps the explanation dismissible when storage is unavailable", () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("storage unavailable");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("storage unavailable");
  });

  render(<MatchArc {...props} />);
  fireEvent.click(screen.getByRole("button", { name: "Got it" }));

  expect(
    screen.queryByText("An evidence-backed transition, not a score"),
  ).toBeNull();
});
