export const regions = [
  { platform: "NA1", slug: "na", label: "NA", name: "North America" },
  { platform: "EUW1", slug: "euw", label: "EUW", name: "Europe West" },
  { platform: "EUN1", slug: "eune", label: "EUNE", name: "Europe Nordic & East" },
  { platform: "KR", slug: "kr", label: "KR", name: "Korea" },
] as const;
export type Platform = typeof regions[number]["platform"];
export type PlayerIdentity = { platform: Platform; gameName: string; tagLine: string };
export const isPlatform = (value: unknown): value is Platform => regions.some(region => region.platform === value);
export const regionFor = (platform: Platform = "NA1") => regions.find(region => region.platform === platform)!;
export const matchIdPattern = /^(?:NA1|EUW1|EUN1|KR)_\d{1,30}$/;

export function validRiotIdentity(gameName: string, tagLine: string): boolean {
  return gameName.trim().length > 0 && gameName.length <= 64 && tagLine.trim().length > 0 && tagLine.length <= 16
    && !/[#\/\\\u0000-\u001f\u007f]/u.test(gameName) && !/[#\/\\\u0000-\u001f\u007f-]/u.test(tagLine);
}
export function parseRiotId(value: string): Pick<PlayerIdentity, "gameName" | "tagLine"> | null {
  const separator = value.lastIndexOf("#");
  if (separator < 1) return null;
  const gameName = value.slice(0, separator).trim(), tagLine = value.slice(separator + 1).trim();
  return validRiotIdentity(gameName, tagLine) ? { gameName, tagLine } : null;
}
export function profileHref(identity: PlayerIdentity, runId?: string): string {
  const path = `/summoners/${regionFor(identity.platform).slug}/${encodeURIComponent(identity.gameName)}-${encodeURIComponent(identity.tagLine)}`;
  return runId ? `${path}?runId=${encodeURIComponent(runId)}` : path;
}
// Decode the route segment once, then split only the final hyphen so names retain their exact characters.
export function parseProfileRoute(regionSlug: string, riotId: string, { encoded = true }: { encoded?: boolean } = {}): PlayerIdentity | null {
  const region = regions.find(region => region.slug === regionSlug);
  if (encoded) {
    try { riotId = decodeURIComponent(riotId); } catch { return null; }
  }
  const separator = riotId.lastIndexOf("-");
  if (!region || separator < 1) return null;
  const gameName = riotId.slice(0, separator), tagLine = riotId.slice(separator + 1);
  return validRiotIdentity(gameName, tagLine) ? { platform: region.platform, gameName, tagLine } : null;
}
