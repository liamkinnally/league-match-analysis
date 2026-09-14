import type { AbilitySlot, GameAsset } from "./types";
import { DATA_DRAGON, descriptionText, safeAssetPath, validAssetVersion } from "./urls";

// The timeline does not identify the active form, weapon, possession, or spell-menu state.
// Keep these champions slot-only instead of presenting an arbitrary base-form icon.
const AMBIGUOUS_ABILITY_CHAMPIONS = new Set([60, 76, 102, 126, 141, 150, 234, 240, 421, 523, 910]);

export function championAbilities(body: unknown, championId: number, version: string): Partial<Record<AbilitySlot, GameAsset>> {
  if (AMBIGUOUS_ABILITY_CHAMPIONS.has(championId)) return {};
  if (!validAssetVersion(version) || !body || typeof body !== "object" || !("data" in body) || !body.data || typeof body.data !== "object") return {};
  const matches = Object.values(body.data).filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === "object" && "key" in entry && entry.key === String(championId));
  if (matches.length !== 1 || !Array.isArray(matches[0].spells) || matches[0].spells.length !== 4) return {};
  const result: Partial<Record<AbilitySlot, GameAsset>> = {};
  matches[0].spells.forEach((spell: unknown, index: number) => {
    if (!spell || typeof spell !== "object" || !("name" in spell) || typeof spell.name !== "string" || !("image" in spell) || !spell.image || typeof spell.image !== "object" || !("full" in spell.image) || typeof spell.image.full !== "string" || !safeAssetPath(spell.image.full) || spell.image.full.includes("/")) return;
    result[(["Q", "W", "E", "R"] as const)[index]] = { name: spell.name, imageUrl: `${DATA_DRAGON}/cdn/${version}/img/spell/${spell.image.full}`, ...("description" in spell && typeof spell.description === "string" ? { description: descriptionText(spell.description) } : {}) };
  });
  return result;
}
