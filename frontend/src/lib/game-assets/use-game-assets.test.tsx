import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useGameAssetCatalogs } from "./use-game-assets";
const base = { assetVersion: "16.19.1", champions: {}, items: {}, spells: {} };
afterEach(() => vi.useRealTimers());
it("automatically retries unavailable matching-patch metadata and cancels timers on unmount", async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-09-14T12:00:00Z"));
  const fetcher = vi.fn().mockImplementationOnce(async () => Response.json({ "16.19.1": { ...base, runePerformanceState: { status: "unavailable", patch: "16.19", sourceUrl: "https://raw.communitydragon.org/16.19/perks.json", retryAt: "2026-09-14T12:01:00Z" } } })).mockImplementation(async () => Response.json({ "16.19.1": { ...base, runePerformanceState: { status: "available", patch: "16.19", sourceUrl: "https://raw.communitydragon.org/16.19/perks.json", retrievedAt: new Date().toISOString() } } }));
  vi.stubGlobal("fetch", fetcher);
  const hook = renderHook(() => useGameAssetCatalogs(["16.19.1"], { includeRunes: true }));
  await act(async () => {});
  await act(async () => vi.advanceTimersByTime(60_000));
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(hook.result.current["16.19.1"]?.runePerformanceState?.status).toBe("available");
  hook.unmount();
  await act(async () => vi.advanceTimersByTime(86_400_000));
  expect(fetcher).toHaveBeenCalledTimes(2);
});
