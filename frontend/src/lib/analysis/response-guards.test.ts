import { expect, it } from "vitest";
import {
  fixtureResponse,
  lensFixtures,
  selectedResponse,
} from "../../test/match-analysis-fixture";
import { parseMatchAnalysisResponse } from "./response-guards";

it.each(lensFixtures)("parses a valid $type lens payload", (lens) => {
  const response = {
    ...selectedResponse,
    active: { ...selectedResponse.active, lens },
  };

  expect(parseMatchAnalysisResponse(response).active?.lens.type).toBe(lens.type);
});

it("parses the valid calm response", () => {
  expect(parseMatchAnalysisResponse(fixtureResponse)).toEqual(fixtureResponse);
});

it.each([undefined, null, "GRAPH"])("rejects invalid server primary lens %s", (primaryLens) => {
  const response = structuredClone(fixtureResponse);
  Object.assign(response.arc.transitions[0], { primaryLens });
  expect(() => parseMatchAnalysisResponse(response)).toThrow("INVALID_MATCH_ANALYSIS_RESPONSE");
});

it("accepts a recorded kill without an optional descriptor", () => {
  const response = structuredClone(fixtureResponse);
  Object.assign(response.arc.transitions[0].anchors[0], { kind: "CHAMPION_KILL", descriptor: null });
  expect(parseMatchAnalysisResponse(response).arc.transitions[0].anchors[0].descriptor).toBeNull();
});

it("accepts a Map kill point without an optional descriptor", () => {
  const lens = structuredClone(lensFixtures.find((value) => value.type === "MAP")!);
  if (!("points" in lens) || !lens.points) throw new Error("Map fixture required");
  Object.assign(lens.points[0], { anchorKind: "CHAMPION_KILL", descriptor: null });
  expect(parseMatchAnalysisResponse({ ...selectedResponse, active: { ...selectedResponse.active, lens } }).active?.lens.type).toBe("MAP");
});

it("rejects malformed response data instead of casting unknown", () => {
  expect(() =>
    parseMatchAnalysisResponse({
      ...fixtureResponse,
      match: { ...fixtureResponse.match, durationMs: "thirty minutes" },
    }),
  ).toThrow("INVALID_MATCH_ANALYSIS_RESPONSE");
  expect(() =>
    parseMatchAnalysisResponse({
      ...selectedResponse,
      active: {
        ...selectedResponse.active,
        lens: { ...lensFixtures[0], type: "GRAPH" },
      },
    }),
  ).toThrow("INVALID_MATCH_ANALYSIS_RESPONSE");
});

it("accepts an absent champion knowledge revision", () => {
  const response = {
    ...selectedResponse,
    active: {
      ...selectedResponse.active,
      evidence: {
        ...selectedResponse.active.evidence,
        revision: {
          ...selectedResponse.active.evidence.revision,
          championKnowledgeVersion: null,
        },
      },
    },
  };

  expect(parseMatchAnalysisResponse(response).active?.evidence.revision)
    .toMatchObject({ championKnowledgeVersion: null });
});

it("rejects a missing source record identity", () => {
  const response = {
    ...selectedResponse,
    active: {
      ...selectedResponse.active,
      evidence: {
        ...selectedResponse.active.evidence,
        coverage: [
          {
            ...selectedResponse.active.evidence.coverage[0],
            sourceRecordId: null,
          },
        ],
      },
    },
  };

  expect(() => parseMatchAnalysisResponse(response)).toThrow(
    "INVALID_MATCH_ANALYSIS_RESPONSE",
  );
});

it("rejects fractional values for Java integer fields", () => {
  const response = {
    ...fixtureResponse,
    match: {
      ...fixtureResponse.match,
      focalParticipant: {
        ...fixtureResponse.match.focalParticipant,
        participantId: 6.5,
      },
    },
  };

  expect(() => parseMatchAnalysisResponse(response)).toThrow(
    "INVALID_MATCH_ANALYSIS_RESPONSE",
  );
});

it.each([
  ["fractional", 780_275.5],
  ["negative", -1],
])("rejects a %s represented timestamp", (_label, representedAtMs) => {
  const response = {
    ...selectedResponse,
    active: {
      ...selectedResponse.active,
      receipt: {
        ...selectedResponse.active.receipt,
        before: {
          ...selectedResponse.active.receipt.before,
          representedAtMs,
        },
      },
    },
  };

  expect(() => parseMatchAnalysisResponse(response)).toThrow(
    "INVALID_MATCH_ANALYSIS_RESPONSE",
  );
});

it("rejects an interval whose end precedes its start", () => {
  const [first, ...remaining] = fixtureResponse.arc.transitions;
  const response = {
    ...fixtureResponse,
    arc: {
      ...fixtureResponse.arc,
      transitions: [
        {
          ...first,
          interval: { startMs: 540_001, endMs: 540_000 },
        },
        ...remaining,
      ],
    },
  };

  expect(() => parseMatchAnalysisResponse(response)).toThrow(
    "INVALID_MATCH_ANALYSIS_RESPONSE",
  );
});
