// Completion tokens from the 16.17/16.18 item catalogs. Runic Compass is the
// final support-quest stage; item 1203 also disappears during ward-slot updates.
const COMPLETION_ROLES: Record<number, string> = {
  1200: "TOP",
  1222: "TOP",
  1201: "MIDDLE",
  1202: "BOTTOM",
  1204: "JUNGLE",
  3866: "UTILITY",
};

export function isRoleQuestCompletionItem(
  itemId: number | null,
  position: string | undefined,
  gameVersion: string | undefined,
): boolean {
  return /^16\.(17|18)(?:\.\d+)*$/.test(gameVersion ?? "")
    && itemId !== null && position !== undefined
    && COMPLETION_ROLES[itemId] === position;
}
