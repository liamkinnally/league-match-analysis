import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import fixtureManifest from "../../../e2e/support/art/manifest.json";
import { isAllowedAssetUrl } from "./urls";

it("keeps deterministic provider artwork identical to its declared source bytes", () => {
  for (const entry of Object.values(fixtureManifest.assets)) {
    const bytes = readFileSync(`e2e/support/art/${entry.file}`);
    expect(isAllowedAssetUrl(entry.sourceUrl), entry.file).toBe(true);
    expect(createHash("sha256").update(bytes).digest("hex"), entry.file).toBe(entry.sha256);
    expect(bytes.length, entry.file).toBeGreaterThan(100);
    if ("alternateSources" in entry) {
      for (const source of entry.alternateSources) {
        expect(isAllowedAssetUrl(source.sourceUrl), entry.file).toBe(true);
        expect(source.sha256, entry.file).toBe(entry.sha256);
      }
    }
  }
});
