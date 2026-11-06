import { expect, it } from "vitest";
import { parseLeaderboard } from "./types";
import { leaderboardFixture } from "./leaderboard.test-fixture";
it("accepts all 300 Challenger members and allows only consistent bounded cutoff estimates", () => {
  const entries = Array.from({ length: 300 }, (_, i) => ({ ...leaderboardFixture.entries[0], position: i + 1 }));
  expect(parseLeaderboard({ ...leaderboardFixture, entries })).toEqual({ ...leaderboardFixture, entries });
  for (const value of [
    { ...leaderboardFixture, entries: [...entries, { ...entries[0], position: 301 }] },
    { ...leaderboardFixture, entries: [{ ...entries[0], tier: "GRANDMASTER" }] },
    { ...leaderboardFixture, cutoffs: undefined },
    { ...leaderboardFixture, cutoffs: { ...leaderboardFixture.cutoffs, fetchedAt: null } },
    { ...leaderboardFixture, cutoffs: { ...leaderboardFixture.cutoffs, status: "pending" } },
    ...[{ slots: 301 }, { minimumLp: 0 }, { boundaryLp: -1 }, { boundaryLp: 100, promotionLp: 800 }, { promotionLp: 1900 }, { boundaryLp: 2147483647, promotionLp: 2147483648 }].map(change => ({ ...leaderboardFixture, cutoffs: { ...leaderboardFixture.cutoffs, challenger: { ...leaderboardFixture.cutoffs.challenger, ...change } } })),
  ]) expect(() => parseLeaderboard(value)).toThrow("INVALID_LEADERBOARD");
  const floor = { ...leaderboardFixture, cutoffs: { ...leaderboardFixture.cutoffs, challenger: { slots: 300, minimumLp: 800, boundaryLp: null, promotionLp: 800 } } };
  expect(parseLeaderboard(floor).cutoffs.challenger?.promotionLp).toBe(800);
});
