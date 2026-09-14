import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import { runeCatalog } from "./runes";
import { assetManifest, RUNE_METADATA_SOURCES, RUNE_METADATA_16_18_SOURCES } from "./manifest";
import { communityDragonGameDataUrl, descriptionText, isAllowedAssetUrl } from "./urls";
import { championAbilities } from "./abilities";
import { eventAssetCatalog, rankAsset } from "./event-assets";

it("pins the shipped semantic projection bytes separately from upstream source hashes", () => {
  ["runes-16.17.1.json", "perkstyles-16.17.json", "perks-16.17.json"].forEach((file, index) => {
    const bytes = readFileSync(`src/lib/game-assets/metadata/${file}`);
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(RUNE_METADATA_SOURCES[index].projectionSha256);
    expect(RUNE_METADATA_SOURCES[index].sha256).toMatch(/^[a-f0-9]{64}$/);
  });
});
it("provides all reviewed rune rows and patch-specific shard choices without guessing unknown patches", () => {
  const catalog = runeCatalog("16.17.810.4348");
  expect(catalog.runeTrees?.["8000"].slots[0]).toEqual([8005, 8008, 8021, 8010]);
  expect(catalog.runes?.["8992"]?.treeId).toBe(8200);
  expect(catalog.statShardSlots).toEqual([[5008, 5005, 5007], [5008, 5010, 5001], [5011, 5013, 5001]]);
  expect(catalog.statShards?.["5013"]?.name).toBe("Tenacity and Slow Resist");
  expect(catalog.runes?.["999999"]).toBeUndefined();
  expect(runeCatalog("16.19.1")).toEqual({});
  expect(assetManifest("16.19.1", "16.19.1", true).semanticDatasets).toEqual([]);
  expect(assetManifest("16.19.1", "16.19.1", true).fallbackReason).toContain("not been reviewed");
});
it("resolves the documented virtual prefix and rejects paths escaping the approved hosts", () => {
  expect(communityDragonGameDataUrl("16.17", "/lol-game-data/assets/v1/perk-images/StatMods/StatModsAdaptiveForceIcon.png")).toBe("https://raw.communitydragon.org/16.17/plugins/rcp-be-lol-game-data/global/default/v1/perk-images/statmods/statmodsadaptiveforceicon.png");
  for (const path of ["/lol-game-data/assets/../private.png", "/lol-game-data/assets/%2e%2e/file.png", "https://evil.test/a.png", "/other/assets/a.png", "/lol-game-data/assets/a.png?x=1"]) expect(communityDragonGameDataUrl("16.17", path)).toBeUndefined();
  for (const url of ["http://ddragon.leagueoflegends.com/a.png", "https://ddragon.leagueoflegends.com.evil.test/a.png", "https://user@raw.communitydragon.org/a.png", "https://raw.communitydragon.org/a/../b.png", "https://raw.communitydragon.org/a.png?redirect=https://evil.test"]) expect(isAllowedAssetUrl(url)).toBe(false);
});
it("keeps ability slots distinct from summoner spells and rejects ambiguous form catalogs", () => {
  const spells = ["Q", "W", "E", "R"].map((slot) => ({ name: slot, image: { full: `${slot}.png` } }));
  const body = { data: { Garen: { key: "86", spells } } };
  expect(championAbilities(body, 86, "16.17.1").Q?.imageUrl).toContain("/img/spell/Q.png");
  expect(championAbilities(body, 103, "16.17.1")).toEqual({});
  expect(championAbilities({ data: { Elise: { key: "60", spells } } }, 60, "16.17.1")).toEqual({});
  expect(championAbilities({ data: { Garen: { key: "86", spells: [...spells, spells[0]] } } }, 86, "16.17.1")).toEqual({});
});
it("uses reviewed object paths and leaves unknown identities and Atakhan forms unresolved", () => {
  const objects = eventAssetCatalog("16.17.1");
  expect(objects.TOWER_BUILDING.imageUrl).toContain("icon_ui_tower_minimap.png");
  expect(objects.AIR_DRAGON.name).toBe("Cloud Dragon");
  expect(objects.ATAKHAN).toBeUndefined();
  expect(eventAssetCatalog("1.1.1")).toEqual({});
  expect(rankAsset("GOLD", "16.17")?.imageUrl).not.toContain("latest");
  expect(rankAsset("../GOLD", "16.17")).toBeUndefined();
  expect(descriptionText("<b>Damage</b><br>0 &amp; healing")).toBe("Damage 0 & healing");
});

it("resolves the match patch to separately reviewed 16.17 and 16.18 manifests", () => {
  expect(runeCatalog("16.18.810.4348").runeTrees?.["8000"].slots[0]).toEqual([8005, 8008, 8021, 8010]);
  expect(assetManifest("16.18.810.4348", "16.18.1", true).id).toBe("rune-layout-16.18-v1");
  expect(assetManifest("16.18.810.4348", "16.18.1", true).semanticDatasets[0].sourceUrl).toContain("/16.18.1/");
  expect(assetManifest("16.17.810.4348", "16.17.2", true).id).toBe("rune-layout-16.17-v1");
  expect(eventAssetCatalog("16.18.1").AIR_DRAGON.imageUrl).toContain("/16.18/");
});

it("validates the independent 16.18 projections without changing the preserved 16.17 hashes", () => {
  ["runes-16.18.1.json", "perkstyles-16.18.json", "perks-16.18.json"].forEach((file, index) => {
    expect(createHash("sha256").update(readFileSync(`src/lib/game-assets/metadata/${file}`)).digest("hex")).toBe(RUNE_METADATA_16_18_SOURCES[index].projectionSha256);
    expect(RUNE_METADATA_16_18_SOURCES[index].sourceUrl).toContain("16.18");
    expect(RUNE_METADATA_SOURCES[index].sourceUrl).toContain("16.17");
  });
  expect(runeCatalog("16.18.1").statShards?.["5008"].imageUrl).toContain("/16.18/");
  expect(runeCatalog("16.17.1").statShards?.["5008"].imageUrl).toContain("/16.17/");
  expect(runeCatalog("16.18.evil")).toEqual({});
});
