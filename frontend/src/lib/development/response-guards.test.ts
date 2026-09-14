import { expect, it } from "vitest";
import { developmentFixture } from "../../test/match-development-fixture";
import { parseMatchDevelopment } from "./response-guards";

const runeSelection = {
  availability: "available", matchPatch: "16.17", normalizationVersion: "test-v1", layoutManifestId: "rune-layout-16.17-v1",
  performanceStatus: "unverified", styles: [{ styleId: 8000, role: "primaryStyle", selections: [{ runeId: 8005, metrics: [{ id: "test-damage", label: "Synthetic damage", availability: "unverified", value: null, unit: "damage", targetScope: "champions", timeScope: "end-of-game", valueBasis: "source-reported", mappingVersion: "synthetic-test" }] }] }],
  shards: { offense: 5008, flex: 5008, defense: 5011 },
};
it("accepts optional rune selections but rejects malformed metric contracts", () => {
  const data = structuredClone(developmentFixture);
  const withRunes = { ...data, roster: data.roster.map(person => ({ ...person, runes: runeSelection })) };
  expect(parseMatchDevelopment(withRunes).roster[0]).toHaveProperty("runes", runeSelection);
  const invalid = structuredClone(withRunes);
  invalid.roster[0].runes.styles[0].selections[0].metrics[0].timeScope = "interval";
  expect(() => parseMatchDevelopment(invalid)).toThrow("INVALID_MATCH_DEVELOPMENT_RESPONSE");
});

it("accepts null metric samples as timeline gaps", () => {
  expect(parseMatchDevelopment(developmentFixture).samples[1]).toMatchObject({
    timestampMs: 540_000,
    goldDifference: null,
    focalTotalGold: 3_700,
  });
});

it("retains a legacy match when optional rune data is absent or null", () => {
  expect(parseMatchDevelopment(developmentFixture).roster).toHaveLength(4);
  expect(parseMatchDevelopment({ ...developmentFixture, roster: developmentFixture.roster.map(person => ({ ...person, runes: null })) }).roster).toHaveLength(4);
});

it("rejects a response with a missing sample field", () => {
  const malformed = structuredClone(developmentFixture) as Record<string, unknown>;
  const samples = malformed.samples as Array<Record<string, unknown>>;
  delete samples[0].goldDifference;
  expect(() => parseMatchDevelopment(malformed)).toThrow("INVALID_MATCH_DEVELOPMENT_RESPONSE");
});

it("accepts absent or null event presentation but rejects conflicting known teams", () => {
  const data = structuredClone(developmentFixture);
  expect(parseMatchDevelopment({ ...data, events: data.events.map(event => ({ ...event, presentation: null })) }).events).toHaveLength(data.events.length);
  expect(() => parseMatchDevelopment({ ...data, events: data.events.map(event => ({ ...event, presentation: { actorTeam: { basis: "known", teamId: null }, objectTeam: { basis: "missing", teamId: null } } })) })).toThrow("INVALID_MATCH_DEVELOPMENT_RESPONSE");
});

it("accepts nullable integer source rune counters and rejects partial or non-integer shapes", () => {
  const selection = { runeId: 8437, counters: { var1: 0, var2: null, var3: -1 }, metrics: [] };
  const snapshot = { ...runeSelection, styles: [{ styleId: 8400, role: "primaryStyle", selections: [selection] }] };
  const response = { ...developmentFixture, roster: developmentFixture.roster.map(person => ({ ...person, runes: snapshot })) };
  expect(parseMatchDevelopment(response).roster[0].runes?.styles[0].selections[0].counters).toEqual({ var1: 0, var2: null, var3: -1 });
  for (const counters of [{ var1: 1, var2: 2 }, { var1: 1.5, var2: null, var3: 0 }, { var1: "1", var2: null, var3: 0 }]) {
    const malformed = { ...response, roster: response.roster.map(person => ({ ...person, runes: { ...snapshot, styles: [{ ...snapshot.styles[0], selections: [{ ...selection, counters }] }] } })) };
    expect(() => parseMatchDevelopment(malformed)).toThrow("INVALID_MATCH_DEVELOPMENT_RESPONSE");
  }
});
it("accepts an explicitly unitless metric without rejecting the match", () => {
  const snapshot = structuredClone(runeSelection);
  snapshot.styles[0].selections[0].metrics[0].unit = "";
  expect(parseMatchDevelopment({ ...developmentFixture, roster: developmentFixture.roster.map(person => ({ ...person, runes: snapshot })) }).roster).toHaveLength(4);
});
