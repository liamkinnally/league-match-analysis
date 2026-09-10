import { beforeEach, expect, it, vi } from "vitest";
import { resolveGameAssetCatalog } from "../../../lib/game-assets/catalog";
import { GET } from "./route";

vi.mock("../../../lib/game-assets/catalog", () => ({ resolveGameAssetCatalog: vi.fn() }));

const catalog = {
  assetVersion: "16.17.2",
  champions: {},
  items: {},
  spells: {},
};

beforeEach(() => vi.mocked(resolveGameAssetCatalog).mockReset());

it("deduplicates validated versions and returns catalogs with public cache headers", async () => {
  vi.mocked(resolveGameAssetCatalog).mockResolvedValue(catalog);

  const response = await GET(new Request("http://localhost/api/game-assets?versions=16.17.1,16.17.1"));

  expect(response.status).toBe(200);
  expect(response.headers.get("Cache-Control")).toBe("public, max-age=3600, stale-while-revalidate=86400");
  expect(await response.json()).toEqual({ "16.17.1": catalog });
  expect(resolveGameAssetCatalog).toHaveBeenCalledOnce();
});

it("keeps optional catalog failure isolated from the core page", async () => {
  vi.mocked(resolveGameAssetCatalog).mockRejectedValueOnce(new Error("ASSET_REQUEST_FAILED"));

  const response = await GET(new Request("http://localhost/api/game-assets?versions=16.17.1"));

  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ "16.17.1": null });
});

it("accepts mixed source game-version formats and resolves each catalog independently", async () => {
  const olderCatalog = { ...catalog, assetVersion: "15.24.1" };
  vi.mocked(resolveGameAssetCatalog)
    .mockResolvedValueOnce(catalog)
    .mockResolvedValueOnce(olderCatalog);

  const response = await GET(new Request(
    "http://localhost/api/game-assets?versions=16.17.810.4348,15.24.1,16.17.810.4348",
  ));

  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({
    "16.17.810.4348": catalog,
    "15.24.1": olderCatalog,
  });
  expect(resolveGameAssetCatalog).toHaveBeenNthCalledWith(1, "16.17.810.4348");
  expect(resolveGameAssetCatalog).toHaveBeenNthCalledWith(2, "15.24.1");
});

it("rejects missing, malformed, or excessive version lists", async () => {
  const missing = await GET(new Request("http://localhost/api/game-assets"));
  const malformed = await GET(new Request("http://localhost/api/game-assets?versions=current"));
  const excessive = await GET(new Request("http://localhost/api/game-assets?versions=1.1,2.2,3.3,4.4,5.5,6.6"));

  expect([missing.status, malformed.status, excessive.status]).toEqual([400, 400, 400]);
  expect(resolveGameAssetCatalog).not.toHaveBeenCalled();
});
