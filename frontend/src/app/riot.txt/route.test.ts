import { expect, it, vi } from "vitest";
import { GET } from "./route";

it("does not advertise site verification until Riot supplies a value", async () => {
  vi.stubEnv("RIOT_VERIFICATION_TOKEN", "");
  vi.stubEnv("RIOT_API_KEY", "RGAPI-never-a-verification-code");
  const response = await GET();
  expect(response.status).toBe(404);
  expect(await response.text()).not.toContain("RGAPI");
  expect(response.headers.get("cache-control")).toBe("no-store");
});

it("serves the explicitly configured verification string as plain text", async () => {
  vi.stubEnv("RIOT_VERIFICATION_TOKEN", "471a5678-review-example-9012");
  const response = await GET();
  expect(response.status).toBe(200);
  expect(await response.text()).toBe("471a5678-review-example-9012");
  expect(response.headers.get("content-type")).toBe("text/plain; charset=utf-8");
  expect(response.headers.get("cache-control")).toBe("no-store");
});

it.each(["RGAPI-secret", "rgapi-secret", "verification\nextra", "<html>value</html>", "x".repeat(513)])(
  "refuses invalid or API-key-shaped verification configuration", async (value) => {
    vi.stubEnv("RIOT_VERIFICATION_TOKEN", value);
    const response = await GET();
    expect(response.status).toBe(404);
    expect(await response.text()).not.toContain(value);
  },
);
