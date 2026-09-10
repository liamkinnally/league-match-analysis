import { expect, it } from "vitest";
import { developmentFixture } from "../../test/match-development-fixture";
import { parseMatchDevelopment } from "./response-guards";

it("accepts null metric samples as timeline gaps", () => {
  expect(parseMatchDevelopment(developmentFixture).samples[1]).toMatchObject({
    timestampMs: 540_000,
    goldDifference: null,
    focalTotalGold: 3_700,
  });
});

it("rejects a response with a missing sample field", () => {
  const malformed = structuredClone(developmentFixture) as Record<string, unknown>;
  const samples = malformed.samples as Array<Record<string, unknown>>;
  delete samples[0].goldDifference;
  expect(() => parseMatchDevelopment(malformed)).toThrow("INVALID_MATCH_DEVELOPMENT_RESPONSE");
});
