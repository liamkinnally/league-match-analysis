import "server-only";
import { createHash } from "node:crypto";
import { isAllowedAssetUrl } from "./urls";

export type AssetSource = { body: unknown; sourceUrl: string; sha256: string; retrievedAt: string; status: "available" | "stale"; retryAt?: string };
type Entry = { source?: AssetSource; expiresAt: number; retryAt: number };
export class AssetSourceUnavailable extends Error {
  constructor(readonly retryAt: string) { super("ASSET_SOURCE_UNAVAILABLE"); }
}
const DAY = 86_400_000, RETRY = 60_000, MAX_BYTES = 4_000_000;

/** One bounded cache for the resolver. Failed reads retry after one minute, never after the success TTL. */
export function createAssetSourceReader() {
  const cache = new Map<string, Entry>(), pending = new Map<string, Promise<AssetSource>>();
  return async function read(url: string, validate: (body: unknown) => boolean = () => true): Promise<AssetSource> {
    if (!isAllowedAssetUrl(url)) throw new Error("INVALID_ASSET_SOURCE");
    const now = Date.now(), old = cache.get(url);
    if (old?.source && old.expiresAt > now) return old.source;
    if (old && old.retryAt > now) {
      if (old.source) return { ...old.source, status: "stale", retryAt: new Date(old.retryAt).toISOString() };
      throw new AssetSourceUnavailable(new Date(old.retryAt).toISOString());
    }
    const existing = pending.get(url);
    if (existing) return existing;
    const request = (async (): Promise<AssetSource> => {
      try {
        // Cache ownership stays here so framework caching cannot prolong an unavailable response.
        const response = await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(2_500), redirect: "error" });
        if (!response.ok || (response.url && !isAllowedAssetUrl(response.url))) throw new Error("ASSET_REQUEST_FAILED");
        if (Number(response.headers.get("content-length")) > MAX_BYTES) throw new Error("ASSET_PAYLOAD_TOO_LARGE");
        const reader = response.body?.getReader();
        if (!reader) throw new Error("INVALID_ASSET_CATALOG");
        const chunks: Uint8Array[] = []; let length = 0;
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          length += value.byteLength;
          if (length > MAX_BYTES) { await reader.cancel(); throw new Error("ASSET_PAYLOAD_TOO_LARGE"); }
          chunks.push(value);
        }
        const bytes = new Uint8Array(length); let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
        const body: unknown = JSON.parse(new TextDecoder().decode(bytes));
        if (!validate(body)) throw new Error("INVALID_ASSET_CATALOG");
        const source: AssetSource = { body, sourceUrl: url, sha256: createHash("sha256").update(bytes).digest("hex"), retrievedAt: new Date().toISOString(), status: "available" };
        cache.set(url, { source, expiresAt: Date.now() + DAY, retryAt: 0 });
        return source;
      } catch {
        const retryAt = Date.now() + RETRY;
        cache.set(url, { source: old?.source, expiresAt: old?.expiresAt ?? 0, retryAt });
        if (old?.source) return { ...old.source, status: "stale", retryAt: new Date(retryAt).toISOString() };
        throw new AssetSourceUnavailable(new Date(retryAt).toISOString());
      } finally {
        while (cache.size > 256) cache.delete(cache.keys().next().value!);
      }
    })();
    pending.set(url, request);
    try { return await request; } finally { pending.delete(url); }
  };
}
export const readAssetSource = createAssetSourceReader();
