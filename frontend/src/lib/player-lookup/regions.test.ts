import { expect, it } from "vitest";
import { parseProfileRoute, profileHref } from "./regions";

it("creates distinct profile paths and round-trips spaces, hyphens, and Korean names", () => {
  expect(profileHref({ platform: "NA1", gameName: "kitinginmylane", tagLine: "000" })).toBe("/summoners/na/kitinginmylane-000");
  for (const gameName of ["prblm-answr", "Hide on bush", "다른 이름", "100% real", "Literal%20name"]) {
    const path = profileHref({ platform: "KR", gameName, tagLine: "KR1" });
    expect(parseProfileRoute("kr", path.split("/").at(-1)!)).toEqual({ platform: "KR", gameName, tagLine: "KR1" });
  }
  expect(parseProfileRoute("eune", "a-b-000")).toEqual({ platform: "EUN1", gameName: "a-b", tagLine: "000" });
});

it("rejects unsupported regions and malformed profile identities", () => {
  for (const [region, id] of [["unknown", "player-tag"], ["na", "player"], ["na", "-tag"], ["na", "player-"], ["na", "player-abc#def"], ["na", "a/b-tag"]]) {
    expect(parseProfileRoute(region, id)).toBeNull();
  }
});


it("preserves literal percent escapes in metadata params that Next has already decoded", () => {
  const identity = parseProfileRoute("na", "Literal%20name-000", { encoded: false });
  expect(identity?.gameName).toBe("Literal%20name");
  expect(profileHref(identity!)).toBe("/summoners/na/Literal%2520name-000");
});
