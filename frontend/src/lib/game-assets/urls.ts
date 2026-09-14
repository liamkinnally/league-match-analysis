export const DATA_DRAGON = "https://ddragon.leagueoflegends.com";
export const COMMUNITY_DRAGON = "https://raw.communitydragon.org";
export const validAssetVersion = (version: string) => /^\d+\.\d+\.\d+$/.test(version);
export const validPatch = (version: string) => /^\d+\.\d+$/.test(version);

export function safeAssetPath(path: string): boolean {
  return path.length <= 512 && /^[a-zA-Z0-9_./-]+$/.test(path) && !path.startsWith("/") && !path.split("/").some((part) => !part || part === "." || part === "..");
}
export function isAllowedAssetUrl(value: string): boolean {
  if (/^\/__e2e-assets\/[a-zA-Z0-9_.-]+$/.test(value)) return true;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && [DATA_DRAGON, COMMUNITY_DRAGON].includes(url.origin) && !url.username && !url.password && !url.search && !url.hash && safeAssetPath(url.pathname.slice(1)) && !value.includes("..");
  } catch { return false; }
}

/** Metadata virtual prefix observed in the pinned perks resource; suffix verified against provider PNG. */
export function communityDragonGameDataUrl(version: string, virtualPath: string): string | undefined {
  const prefix = "/lol-game-data/assets/";
  if (!validPatch(version) || !virtualPath.startsWith(prefix)) return undefined;
  const suffix = virtualPath.slice(prefix.length);
  if (!safeAssetPath(suffix)) return undefined;
  return `${COMMUNITY_DRAGON}/${version}/plugins/rcp-be-lol-game-data/global/default/${suffix.toLowerCase()}`;
}
export function descriptionText(value: string): string {
  const entities: Record<string,string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
  return value.replace(/<(?:br\b[^>]*|\/?(?:p|li|ul|ol|div)\b[^>]*)>/gi, " ").replace(/<[^>]*>/g, "").replace(/&([a-z]+);/g, (match, key: string) => entities[key] ?? match).replace(/\s+/g, " ").trim();
}

export function runeDescription(value: unknown): string | undefined {
  if (typeof value !== "string" || /@[^@]+@|\{\{/.test(value)) return undefined;
  return descriptionText(value).slice(0, 4000) || undefined;
}
