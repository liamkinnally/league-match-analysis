import { beforeEach, expect, it, vi } from "vitest";
import { resolveGameAssetCatalog } from "./catalog";

vi.mock("server-only", () => ({}));

beforeEach(() => vi.restoreAllMocks());

it("carries recorded patch prices and spell metadata without turning missing prices into zero", async () => {
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(Response.json(["16.17.1"]))
    .mockResolvedValueOnce(Response.json({ data: {} }))
    .mockResolvedValueOnce(Response.json({ data: { "2055": { name: "Control Ward", image: { full: "2055.png" }, gold: { base: 75, total: 75, sell: 30, purchasable: true } }, "999": { name: "Unknown", image: { full: "999.png" } } } }))
    .mockResolvedValueOnce(Response.json({ data: { SummonerFlash: { key: "4", name: "Flash", description: "<b>Teleports</b> a short distance.", cooldown: [300], image: { full: "SummonerFlash.png" } } } })));
  const result = await resolveGameAssetCatalog("16.17.1");
  expect(result.items["2055"].gold).toEqual({ base: 75, total: 75, sell: 30, purchasable: true });
  expect(result.items["999"].gold).toBeUndefined();
  expect(result.spells["4"].description).toBe("Teleports a short distance.");
  expect(result.spells["4"].cooldown).toEqual([300]);
});

it("uses the newest Data Dragon build matching a four-component source game version", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json(["16.18.1", "16.17.2", "16.17.1", "15.24.1"]))
    .mockResolvedValueOnce(Response.json({ data: { Garen: { key: "86", name: "Garen", image: { full: "Garen.png" } } } }))
    .mockResolvedValueOnce(Response.json({ data: { "3071": { name: "Black Cleaver", image: { full: "3071.png" } } } }))
    .mockResolvedValueOnce(Response.json({ data: { SummonerFlash: { key: "4", name: "Flash", image: { full: "SummonerFlash.png" } } } }));
  vi.stubGlobal("fetch", fetchMock);

  const result = await resolveGameAssetCatalog("16.17.810.4348");

  expect(result.assetVersion).toBe("16.17.2");
  expect(result.champions["86"]).toEqual({ name: "Garen", imageUrl: "https://ddragon.leagueoflegends.com/cdn/16.17.2/img/champion/Garen.png" });
  expect(result.items["3071"]?.name).toBe("Black Cleaver");
  expect(result.spells["4"]?.name).toBe("Flash");
});

it("does not treat malformed Data Dragon builds as matching catalog versions", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(["16.17.810.4348", "16.17.beta", "16.18.1"])));

  await expect(resolveGameAssetCatalog("16.17.810.4348")).rejects.toThrow("MATCHING_ASSET_VERSION_NOT_FOUND");
});

it("rejects versions without an exact major and minor Data Dragon match", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(["16.18.1", "15.17.3"])));

  await expect(resolveGameAssetCatalog("16.17.9")).rejects.toThrow("MATCHING_ASSET_VERSION_NOT_FOUND");
});
