import { renderHook, waitFor, act } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useCurrentRanks } from "./use-current-ranks";
import { parseCurrentRanks, parseMatchDevelopment } from "./response-guards";
import { developmentFixture } from "../../test/match-development-fixture";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const body = {
  matchId: "NA1_test",
  queueType: "RANKED_SOLO_5x5",
  refreshing: false,
  players: [
    {
      participantId: 1,
      status: "ranked",
      tier: "GOLD",
      division: "II",
      cached: true,
      stale: false,
    },
  ],
};
it("loads cached verified ranks for the correct queue and roster", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(body)));
  const { result } = renderHook(() =>
    useCurrentRanks("NA1_test", 420, [1, 2], false),
  );
  expect(result.current.players[0].status).toBe("loading");
  await waitFor(() => expect(result.current.players[0].status).toBe("ranked"));
  expect(result.current.players[0].cached).toBe(true);
  expect(result.current.players[1].status).toBe("unavailable");
});
it("never treats a failed lookup or different queue as unranked", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        Response.json({ ...body, queueType: "RANKED_FLEX_SR" }),
      ),
  );
  const { result } = renderHook(() =>
    useCurrentRanks("NA1_test", 420, [1], false),
  );
  await waitFor(() =>
    expect(result.current.players[0].status).toBe("unavailable"),
  );
});
it("does not query synthetic matches or unsupported queues", () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const one = renderHook(() => useCurrentRanks("NA1_test", 420, [1], true));
  const two = renderHook(() => useCurrentRanks("NA1_test", 450, [1], false));
  expect(one.result.current.players[0].status).toBe("unavailable");
  expect(two.result.current.refreshing).toBe(false);
  expect(fetcher).not.toHaveBeenCalled();
});
it("polls loading responses and preserves confirmed unranked as distinct from unavailable", async () => {
  vi.useFakeTimers();
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          ...body,
          refreshing: true,
          players: [{ participantId: 1, status: "loading" }],
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          ...body,
          players: [{ participantId: 1, status: "unranked", cached: true }],
        }),
      ),
  );
  const { result } = renderHook(() =>
    useCurrentRanks("NA1_test", 420, [1], false),
  );
  await act(() => vi.advanceTimersByTimeAsync(1001));
  expect(result.current.players[0].status).toBe("unranked");
  expect(result.current.refreshing).toBe(false);
});
it("rejects duplicate rank identities and malformed rich development fields", () => {
  expect(() =>
    parseCurrentRanks({ ...body, players: [body.players[0], body.players[0]] }),
  ).toThrow("INVALID_CURRENT_RANKS_RESPONSE");
  expect(() =>
    parseMatchDevelopment({
      ...developmentFixture,
      teams: [
        {
          teamId: 100,
          win: false,
          kills: null,
          deaths: null,
          assists: null,
          goldEarned: null,
          objectives: { dragon: "0" },
        },
      ],
    }),
  ).toThrow("INVALID_MATCH_DEVELOPMENT_RESPONSE");
  const data = parseMatchDevelopment({
    ...developmentFixture,
    teams: [
      {
        teamId: 100,
        win: false,
        kills: null,
        deaths: null,
        assists: null,
        goldEarned: null,
        objectives: { dragon: null, baron: 0 },
      },
    ],
  });
  expect(data.teams?.[0].objectives).toEqual({ dragon: null, baron: 0 });
});
it("retains verified ranks and their timestamps if a later poll fails", async () => {
  vi.useFakeTimers();
  const fetchedAt = "2026-09-10T12:00:00Z";
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          ...body,
          refreshing: true,
          players: [
            { ...body.players[0], fetchedAt },
            { participantId: 2, status: "loading" },
          ],
        }),
      )
      .mockRejectedValueOnce(new Error("Offline")),
  );
  const { result } = renderHook(() =>
    useCurrentRanks("NA1_test", 420, [1, 2], false),
  );
  await act(() => vi.advanceTimersByTimeAsync(1001));
  expect(result.current.players[0]).toMatchObject({
    status: "ranked",
    fetchedAt,
    stale: true,
    error: "LOOKUP_FAILED",
  });
  expect(result.current.players[1].status).toBe("unavailable");
});
