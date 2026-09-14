import { runeDescription } from "./urls";
import { expect, it } from "vitest";
import { runePerformanceMetrics } from "./rune-performance";
it("accepts compatible direct provider descriptors without numeric calculations", () => {
  expect(runePerformanceMetrics(["Total Healing: @eogvar3@", "Percent of game active: @eogvar1@%", "Ultimate Cooldown Reduced: @eogvar2@ Seconds"])).toEqual([
    { id: "descriptor-1", label: "Total Healing", variable: 3, unit: "", availability: "available" },
    { id: "descriptor-2", label: "Percent of game active", variable: 1, unit: "percent", availability: "available" },
    { id: "descriptor-3", label: "Ultimate Cooldown Reduced", variable: 2, unit: "seconds", availability: "available" },
  ]);
});
it("rejects reused variables, ambiguous durations and unknown expression syntax", () => {
  const reused = runePerformanceMetrics(["Max Attack Speed Uptime: @eogvar1@:@eogvar2@<br>Damage Dealt: @eogvar2@"]);
  expect(reused.every(row => row.availability === "unsupported")).toBe(true);
  expect(reused[1].reason).toContain("reuses");
  for (const descriptor of ["Time Completed: @eogvar1@:@eogvar2@", "Total time active: @eogvar1@", "Damage: @eogvar1*100@", "Damage: @eogvar4@", "Bonus stats: @eogvar1@ - @eogvar2@", "--"]) expect(runePerformanceMetrics([descriptor])[0].availability).toBe("unsupported");
});
it("keeps direct styled metadata readable while missing descriptors stay explicitly unsupported", () => {
  expect(runePerformanceMetrics(["Move Speed Increase: <speed>@eogvar1@</speed>"])[0]).toMatchObject({ label: "Move Speed Increase", variable: 1, availability: "available" });
  expect(runePerformanceMetrics([])[0].availability).toBe("unsupported");
  expect(runePerformanceMetrics(null)[0].availability).toBe("unsupported");
});

it("keeps description list boundaries readable and refuses unresolved game placeholders", () => {
  expect(runeDescription("Next attack:<ul><li>Deal damage</li><li>Heal yourself</li></ul>")).toBe("Next attack: Deal damage Heal yourself");
  expect(runeDescription("Heal @BaseHeal@ Health")).toBeUndefined();
});
