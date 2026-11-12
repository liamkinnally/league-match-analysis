export function matchResult(win: unknown, remake?: boolean | null) {
  if (remake === true) return { label: "Remake", className: "match-result--unknown" };
  if (win === true) return { label: "Victory", className: "match-result--victory" };
  if (win === false) return { label: "Defeat", className: "match-result--defeat" };
  return { label: "Result unavailable", className: "match-result--unknown" };
}
