"use client";

import { useEffect, useState } from "react";
import { parseCurrentRanks } from "./response-guards";
import { queueType } from "./results";
import type { CurrentRanks, PlayerRank } from "./types";

export function useCurrentRanks(
  matchId: string,
  queueId: number,
  participantIds: number[],
  invented: boolean,
) {
  const rosterKey = participantIds.join(",");
  const type = queueType(queueId);
  const [state, setState] = useState<CurrentRanks | null>(null);
  useEffect(() => {
    if (!type || invented) return;
    const ids = rosterKey.split(",").map(Number);
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const deadline = Date.now() + 30000;
    const poll = async () => {
      try {
        const response = await fetch(
          `/api/matches/${encodeURIComponent(matchId)}/ranks`,
          {
            cache: "no-store",
            signal: AbortSignal.any([
              controller.signal,
              AbortSignal.timeout(8000),
            ]),
          },
        );
        if (!response.ok) throw new Error("RANKS_UNAVAILABLE");
        const next = parseCurrentRanks(await response.json());
        if (next.matchId !== matchId || next.queueType !== type)
          throw new Error("MISMATCHED_RANK_QUEUE");
        next.players = ids.map(
          (id) =>
            next.players.find((p) => p.participantId === id) ?? {
              participantId: id,
              status: "unavailable",
            },
        );
        const timedOut = Date.now() >= deadline;
        if (timedOut) {
          next.refreshing = false;
          next.players = next.players.map((p) =>
            p.status === "loading"
              ? { ...p, status: "unavailable", error: "LOOKUP_TIMEOUT" }
              : p,
          );
        }
        if (controller.signal.aborted) return;
        setState(next);
        if (next.refreshing) timer = setTimeout(poll, 1000);
      } catch {
        if (!controller.signal.aborted)
          setState((previous) => ({
            matchId,
            queueType: type,
            refreshing: false,
            players: ids.map((participantId) => {
              const verified =
                previous?.matchId === matchId && previous.queueType === type
                  ? previous.players.find(
                      (p) => p.participantId === participantId,
                    )
                  : undefined;
              return verified &&
                (verified.status === "ranked" || verified.status === "unranked")
                ? { ...verified, stale: true, error: "LOOKUP_FAILED" }
                : {
                    participantId,
                    status: "unavailable",
                    error: "LOOKUP_FAILED",
                  };
            }),
          }));
      }
    };
    void poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [matchId, type, rosterKey, invented]);
  const initial: PlayerRank[] = participantIds.map((participantId) => ({
    participantId,
    status: type && !invented ? "loading" : "unavailable",
  }));
  return state?.matchId === matchId && state.queueType === type && !invented
    ? state
    : {
        matchId,
        queueType: type,
        refreshing: Boolean(type && !invented),
        players: initial,
      };
}
