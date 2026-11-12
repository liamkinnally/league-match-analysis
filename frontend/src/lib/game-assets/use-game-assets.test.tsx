import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useGameAssetCatalogs } from "./use-game-assets";
const base = { assetVersion: "16.19.1", champions: {}, items: {}, spells: {} };
afterEach(() => vi.useRealTimers());

it("loads every history patch in requests of at most five versions with bounded concurrency", async () => {
  const versions = Array.from({ length: 16 }, (_, index) => `16.${index + 1}.1`);
  const pending: Array<{ versions: string[]; resolve: (response: Response) => void }> = [];
  let active = 0, peak = 0;
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    const requested = new URL(url, "https://app.test").searchParams.get("versions")!.split(",");
    active++; peak = Math.max(peak, active);
    return new Promise<Response>(resolve => pending.push({ versions: requested, resolve: response => { active--; resolve(response); } }));
  }));
  const hook = renderHook(() => useGameAssetCatalogs([...versions, versions[0]]));
  await act(async () => {});
  for (let index = 0; index < pending.length; index++) {
    const request = pending[index];
    expect(request.versions.length).toBeLessThanOrEqual(5);
    await act(async () => request.resolve(Response.json(Object.fromEntries(request.versions.map(version => [version, { ...base, assetVersion: version }])))));
  }
  expect(peak).toBeLessThanOrEqual(2);
  expect(Object.keys(hook.result.current).sort()).toEqual([...versions].sort());
  expect(pending.flatMap(request => request.versions).sort()).toEqual([...versions].sort());
  for (const version of versions) expect(hook.result.current[version]?.assetVersion).toBe(version);
});

it("retains all five loaded catalogs when older history adds a sixth patch", async () => {
  const loadedVersions = ["16.19.1", "16.20.1", "16.21.1", "16.22.1", "16.23.1"];
  const loaded = Object.fromEntries(loadedVersions.map(version => [version, { ...base, assetVersion: version }]));
  const requests: string[][] = [];
  let completeOlder!: (response: Response) => void;
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    const versions = new URL(url, "https://app.test").searchParams.get("versions")!.split(",");
    requests.push(versions);
    return versions.includes("16.18.1") ? new Promise<Response>(resolve => { completeOlder = resolve; })
      : Promise.resolve(Response.json(loaded));
  }));
  const hook = renderHook(({ versions }) => useGameAssetCatalogs(versions), { initialProps: { versions: loadedVersions } });
  await act(async () => {});
  expect(hook.result.current).toEqual(loaded);
  const existing = hook.result.current;
  await act(async () => hook.rerender({ versions: ["16.18.1", ...loadedVersions] }));
  for (const version of loadedVersions) expect(hook.result.current[version]).toBe(existing[version]);
  await act(async () => completeOlder(Response.json({ "16.18.1": { ...base, assetVersion: "16.18.1" } })));
  for (const version of loadedVersions) expect(hook.result.current[version]).toBe(existing[version]);
  expect(hook.result.current["16.18.1"]?.assetVersion).toBe("16.18.1");
  expect(requests).toEqual([loadedVersions, ["16.18.1"]]);
});

it("cancels superseded artwork batches and ignores late responses", async () => {
  const pending: Array<{ versions: string[]; signal: AbortSignal; resolve: (response: Response) => void }> = [];
  vi.stubGlobal("fetch", vi.fn((url: string, options: RequestInit) => new Promise<Response>(resolve => {
    pending.push({ versions: new URL(url, "https://app.test").searchParams.get("versions")!.split(","), signal: options.signal!, resolve });
  })));
  const hook = renderHook(({ versions }) => useGameAssetCatalogs(versions), { initialProps: { versions: ["16.17.1", "16.18.1"] } });
  await act(async () => {});
  const obsolete = [...pending];
  await act(async () => hook.rerender({ versions: ["16.19.1"] }));
  expect(obsolete.every(request => request.signal.aborted)).toBe(true);
  await act(async () => pending.at(-1)!.resolve(Response.json({ "16.19.1": base })));
  for (const request of obsolete) await act(async () => request.resolve(Response.json({ "16.17.1": base })));
  expect(Object.keys(hook.result.current)).toEqual(["16.19.1"]);
  await act(async () => hook.rerender({ versions: ["16.20.1"] }));
  const unfinished = pending.at(-1)!;
  hook.unmount();
  expect(unfinished.signal.aborted).toBe(true);
});

it("retries a failed artwork batch when history grows without refetching successful patches", async () => {
  const loadedVersions = ["16.19.1", "16.20.1", "16.21.1", "16.22.1", "16.23.1"];
  const requests: string[][] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    const versions = new URL(url, "https://app.test").searchParams.get("versions")!.split(",");
    requests.push(versions);
    if (versions.length === 1 && versions[0] === "16.24.1") return Response.json({}, { status: 503 });
    return Response.json(Object.fromEntries(versions.map(version => [version, { ...base, assetVersion: version }])));
  }));
  const hook = renderHook(({ versions }) => useGameAssetCatalogs(versions), {
    initialProps: { versions: [...loadedVersions, "16.24.1"] },
  });
  await act(async () => {});
  const saved = hook.result.current;
  for (const version of loadedVersions) expect(saved[version]?.assetVersion).toBe(version);
  expect(saved["16.24.1"]).toBeNull();
  await act(async () => hook.rerender({ versions: ["16.18.1", ...loadedVersions, "16.24.1"] }));
  expect(hook.result.current["16.24.1"]?.assetVersion).toBe("16.24.1");
  expect(hook.result.current["16.18.1"]?.assetVersion).toBe("16.18.1");
  for (const version of loadedVersions) expect(hook.result.current[version]).toBe(saved[version]);
  expect(requests).toEqual([loadedVersions, ["16.24.1"], ["16.18.1", "16.24.1"]]);
});

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
