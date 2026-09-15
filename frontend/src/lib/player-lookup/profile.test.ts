import { expect, it } from "vitest";
import { parsePlayerProfile } from "./profile";
import { profileFixture } from "./profile.test-fixture";
it("projects the minimized profile and strips nested private fields", () => {
  expect(parsePlayerProfile({ ...profileFixture, puuid: "private", identity: { ...profileFixture.identity, puuid: "private" }, soloRank: { ...profileFixture.soloRank, raw: "private" }, rankHistory: { ...profileFixture.rankHistory, observations: profileFixture.rankHistory.observations.map((row) => ({ ...row, refreshId: "private" })) } })).toEqual(profileFixture);
});
it("rejects false records, malformed timestamps, and oversized observation pages", () => {
  expect(() => parsePlayerProfile({ ...profileFixture, recentSolo: { ...profileFixture.recentSolo, losses: -1 } })).toThrow();
  expect(() => parsePlayerProfile({ ...profileFixture, recentSolo: { ...profileFixture.recentSolo, sampleSize: 20 } })).toThrow();
  expect(() => parsePlayerProfile({ ...profileFixture, summoner: { ...profileFixture.summoner, fetchedAt: "yesterday" } })).toThrow();
  expect(() => parsePlayerProfile({ ...profileFixture, rankHistory: { ...profileFixture.rankHistory, observations: Array(51).fill(profileFixture.rankHistory.observations[0]) } })).toThrow();
});
it("keeps an older backend's missing Flex rank unavailable and validates present Flex values", () => {
  const { flexRank, ...legacy } = profileFixture;
  expect(parsePlayerProfile(legacy).flexRank.status).toBe("unavailable");
  expect(parsePlayerProfile(profileFixture).flexRank.status).toBe("unranked");
  expect(() => parsePlayerProfile({ ...profileFixture, flexRank: { ...flexRank, status: "ranked", tier: "PRIVATE" } })).toThrow();
});
