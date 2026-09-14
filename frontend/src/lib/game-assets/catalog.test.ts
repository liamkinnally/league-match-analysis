import { beforeEach, expect, it, vi } from "vitest";
let resolveGameAssetCatalog: typeof import("./catalog").resolveGameAssetCatalog;
let resolveCurrentProfileAssetCatalog: typeof import("./catalog").resolveCurrentProfileAssetCatalog;

vi.mock("server-only", () => ({}));

beforeEach(async () => { vi.restoreAllMocks(); vi.resetModules(); ({ resolveGameAssetCatalog, resolveCurrentProfileAssetCatalog } = await import("./catalog")); });

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

it("preserves working catalogs when one source is unavailable", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.endsWith("versions.json")) return Response.json(["16.17.1"]);
    if (url.endsWith("item.json")) return new Response("down", { status: 503 });
    if (url.endsWith("champion.json")) return Response.json({ data: { Garen: { key: "86", name: "Garen", image: { full: "Garen.png" } } } });
    return Response.json({ data: {} });
  }));
  const result = await resolveGameAssetCatalog("16.17.1");
  expect(result.champions["86"].name).toBe("Garen");
  expect(result.items).toEqual({});
});

it("coalesces simultaneous requests for the same static sources", async () => {
  const fetchMock = vi.fn(async (url: string) => url.endsWith("versions.json") ? Response.json(["16.17.1"]) : Response.json({ data: {} }));
  vi.stubGlobal("fetch", fetchMock);
  await Promise.all([resolveGameAssetCatalog("16.17.1"), resolveGameAssetCatalog("16.17.810.4348")]);
  expect(fetchMock).toHaveBeenCalledTimes(4);
});

it("only loads requested champion details and preserves core art on missing ability data", async () => {
  const fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith("versions.json")) return Response.json(["16.17.1"]);
    if (url.endsWith("champion.json")) return Response.json({ data: { Garen: { key: "86", name: "Garen", image: { full: "Garen.png" } }, Ahri: { key: "103", name: "Ahri", image: { full: "Ahri.png" } } } });
    if (url.endsWith("Garen.json")) return new Response("unavailable", { status: 503 });
    return Response.json({ data: {} });
  });
  vi.stubGlobal("fetch", fetchMock);
  const catalog = await resolveGameAssetCatalog("16.17.1", { championIds: [86, 86], includeRunes: true });
  expect(catalog.champions["103"].name).toBe("Ahri");
  expect(catalog.abilities?.["86"]).toEqual({});
  expect(fetchMock.mock.calls.filter(([url]) => url.includes("/champion/"))).toHaveLength(1);
  expect(catalog.manifest?.id).toBe("rune-layout-16.17-v1");
});

it("rejects overlarge metadata and unsafe filenames without discarding other catalogs", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.endsWith("versions.json")) return Response.json(["16.17.1"]);
    if (url.endsWith("item.json")) return new Response("{}", { headers: { "content-length": "4000001" } });
    if (url.endsWith("champion.json")) return Response.json({ data: { Garen: { key: "86", name: "Garen", image: { full: "../../evil.png" } } } });
    return Response.json({ data: {} });
  }));
  const catalog = await resolveGameAssetCatalog("16.17.1");
  expect(catalog.champions).toEqual({});
  expect(catalog.items).toEqual({});
});

it("records the newest current profile version separately from the reviewed rank artwork version", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => url.endsWith("versions.json") ? Response.json(["16.17.2", "16.18.1"]) : Response.json({ data: { "29": { id: 29, image: { full: "29.png" } } } })));
  const catalog = await resolveCurrentProfileAssetCatalog();
  expect(catalog.profileIcons?.["29"].imageUrl).toBe("https://ddragon.leagueoflegends.com/cdn/16.18.1/img/profileicon/29.png");
  expect(catalog.manifest?.scope).toBe("current-profile");
  expect(catalog.manifest?.dataDragonVersion).toBe("16.18.1");
  expect(catalog.manifest?.communityDragonVersion).toBe("16.18");
  expect(catalog.ranks?.GOLD.imageUrl).toContain("/16.18/");
});
