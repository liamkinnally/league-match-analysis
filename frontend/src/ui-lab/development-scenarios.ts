import "server-only";

import { parseMatchDevelopment } from "../lib/development/response-guards";
import type { MatchDevelopment } from "../lib/development/types";
import { developmentFixture } from "../test/match-development-fixture";

function fixture(matchId: string): MatchDevelopment {
  const data = structuredClone(developmentFixture) as MatchDevelopment;
  data.matchId = matchId;
  return data;
}

function fullTimeline(matchId: string): MatchDevelopment {
  const data = fixture(matchId);
  const base = Array.from({ length: 15 }, (_, index) => {
    const timestampMs = (index + 1) * 120_000;
    return {
      timestampMs,
      goldDifference: index === 6 ? null : -180 + index * 78,
      csDifference: index === 6 ? null : -2 + index,
      xpDifference: index === 6 ? null : -90 + index * 42,
      focalLevel: index === 6 ? null : Math.min(18, 1 + index),
      compareLevel: index === 6 ? null : Math.min(18, 1 + index),
      focalTotalGold: index === 6 ? null : 500 + index * 825,
      focalCs: index === 6 ? null : index * 15,
      focalXp: index === 6 ? null : index * 520,
    };
  });
  const fixtureEndpoints = data.samples.filter((sample) => sample.timestampMs !== 540_000);
  data.samples = [...base.filter((sample) => sample.timestampMs !== 480_000 && sample.timestampMs !== 600_000), ...fixtureEndpoints]
    .sort((left, right) => left.timestampMs - right.timestampMs);
  return parseMatchDevelopment(data);
}

function sparseTimeline(matchId: string): MatchDevelopment {
  const data = fixture(matchId);
  data.roster[1] = {
    ...data.roster[1],
    championId: 136,
    championName: "AurelionSol",
    endItemIds: [6655, 3118, 3089, 3135, 0, 0, 3340],
  };
  data.samples = [
    data.samples[0],
    { ...data.samples[1], timestampMs: 900_000 },
    { ...data.samples[2], timestampMs: 1_800_000 },
  ];
  data.windows = [{
    id: "480000-1800000",
    startMs: 480_000,
    endMs: 1_800_000,
    before: data.samples[0],
    after: data.samples[2],
    summary: "Garen's recorded comparison changed across the available endpoints.",
  }];
  data.suggestedWindows = [];
  data.events = [];
  return parseMatchDevelopment(data);
}

function longLabelTimeline(matchId: string): MatchDevelopment {
  const data = fixture(matchId);
  data.roster[1] = { ...data.roster[1], championId: 136, championName: "AurelionSol" };
  data.events[0] = { ...data.events[0], label: "Purchased item 6655", itemId: 6655 };
  return parseMatchDevelopment(data);
}

export function resolveDevelopmentScenario(matchId: string): MatchDevelopment | undefined {
  if (matchId === "__lab_development") return fullTimeline(matchId);
  if (matchId === "__lab_development_sparse") return sparseTimeline(matchId);
  if (matchId === "__lab_development_long_labels") return longLabelTimeline(matchId);
  return undefined;
}
