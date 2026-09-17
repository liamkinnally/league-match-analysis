import { isPlatform, validRiotIdentity, type PlayerIdentity } from "./regions";
export type PlayerSuggestion = PlayerIdentity & { profileIconId: number | null; summonerLevel: number | null };
export function parseSuggestions(value: unknown): PlayerSuggestion[] {
  if (!value || typeof value !== "object" || !("suggestions" in value) || !Array.isArray(value.suggestions) || value.suggestions.length > 5) throw new Error("INVALID_SUGGESTIONS");
  return value.suggestions.map((item: unknown) => {
    if (!item || typeof item !== "object") throw new Error("INVALID_SUGGESTIONS");
    const row = item as Record<string, unknown>;
    if (typeof row.gameName !== "string" || typeof row.tagLine !== "string" || !validRiotIdentity(row.gameName, row.tagLine) || !isPlatform(row.platform)) throw new Error("INVALID_SUGGESTIONS");
    const integer = (value: unknown) => value === null ? null : typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : (() => { throw new Error("INVALID_SUGGESTIONS"); })();
    return { gameName: row.gameName, tagLine: row.tagLine, platform: row.platform, profileIconId: integer(row.profileIconId), summonerLevel: integer(row.summonerLevel) };
  });
}
