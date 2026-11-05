import "server-only";

import source from "./sample.json";
import { projectSampleDevelopment, type SampleSource } from "./projection";
import type { DemoMatch, MatchDevelopment } from "../lib/development/types";

export const previewDemo: DemoMatch = {
  matchId: "NA1_7000000001", focusParticipantId: 6, compareParticipantId: 1, invented: true,
};

export function sampleDevelopment(matchId: string, focus: number, compare?: number): MatchDevelopment | undefined {
  if (matchId !== previewDemo.matchId) return undefined;
  // JSON imports widen literal fields such as rune availability; the projector validates its result.
  return projectSampleDevelopment(source as SampleSource, matchId, focus, compare);
}
