import { beforeEach, expect, it, vi } from "vitest";
import { backendFetch } from "./backend-transport";

vi.mock("server-only", () => ({}));

beforeEach(() => {
  vi.stubEnv("BACKEND_URL", "https://backend.example.test/base");
  vi.stubEnv("BACKEND_SERVICE_TOKEN", "0123456789abcdef0123456789abcdef");
});

it("constructs a same-origin request with only the configured bearer credential", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ok: true }));
  vi.stubGlobal("fetch", fetcher);

  await backendFetch("/api/v1/demo?view=full", {
    cache: "no-store",
    headers: { Authorization: "Bearer browser-value", "X-Request-Kind": "demo" },
  });

  const [request] = fetcher.mock.calls[0] as [Request];
  expect(request.url).toBe("https://backend.example.test/api/v1/demo?view=full");
  expect(request.cache).toBe("no-store");
  expect(request.headers.get("authorization")).toBe("Bearer 0123456789abcdef0123456789abcdef");
  expect(request.headers.get("x-request-kind")).toBe("demo");
  expect(request.redirect).toBe("manual");
});

it.each(["https://foreign.example/api", "//foreign.example/api", "api/v1/demo", "/../admin"])(
  "refuses a non-local backend path: %s",
  async (path) => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(backendFetch(path)).rejects.toThrow("BACKEND_PATH_INVALID");
    expect(fetcher).not.toHaveBeenCalled();
  },
);

it.each(["/\t/foreign.example/path", "/\n/foreign.example/path", "/\r/foreign.example/path"])(
  "rejects control characters before URL normalization can change the origin: %j",
  async (path) => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(backendFetch(path)).rejects.toThrow("BACKEND_PATH_INVALID");
    expect(fetcher).not.toHaveBeenCalled();
  },
);

it("rejects HTTP backend URLs in the Vercel runtime before attaching credentials", async () => {
  vi.stubEnv("VERCEL", "1");
  vi.stubEnv("BACKEND_URL", "http://backend.example.test");
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  await expect(backendFetch("/api/v1/demo")).rejects.toThrow("BACKEND_URL_INVALID");
  expect(fetcher).not.toHaveBeenCalled();
});

it("retains authenticated HTTP access for private local runtimes", async () => {
  vi.stubEnv("VERCEL", "");
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  const fetcher = vi.fn().mockResolvedValue(new Response());
  vi.stubGlobal("fetch", fetcher);
  await backendFetch("/api/v1/demo");
  const [request] = fetcher.mock.calls[0] as [Request];
  expect(request.url).toBe("http://127.0.0.1:8080/api/v1/demo");
  expect(request.headers.get("authorization")).toBe("Bearer 0123456789abcdef0123456789abcdef");
});

it("does not automatically forward credentials across redirects", async () => {
  const redirect = new Response(null, { status: 302, headers: { Location: "https://foreign.example/private" } });
  const fetcher = vi.fn().mockResolvedValue(redirect);
  vi.stubGlobal("fetch", fetcher);
  await expect(backendFetch("/api/v1/demo")).resolves.toBe(redirect);
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it.each([
  [{ BACKEND_AUTH_REQUIRED: "true", BACKEND_SERVICE_TOKEN: "" }, "BACKEND_SERVICE_TOKEN_NOT_CONFIGURED"],
  [{ VERCEL: "1", BACKEND_SERVICE_TOKEN: "" }, "BACKEND_SERVICE_TOKEN_NOT_CONFIGURED"],
  [{ BACKEND_SERVICE_TOKEN: "too-short" }, "BACKEND_SERVICE_TOKEN_INVALID"],
  [{ BACKEND_SERVICE_TOKEN: "0123456789abcdef0123456789abcde\n" }, "BACKEND_SERVICE_TOKEN_INVALID"],
] as const)("fails closed for invalid server authentication configuration", async (environment, message) => {
  vi.stubEnv("BACKEND_AUTH_REQUIRED", "");
  vi.stubEnv("VERCEL", "");
  vi.stubEnv("BACKEND_SERVICE_TOKEN", "");
  for (const [key, value] of Object.entries(environment)) vi.stubEnv(key, value);
  vi.stubGlobal("fetch", vi.fn());
  await expect(backendFetch("/api/v1/demo")).rejects.toThrow(message);
});

it("keeps private local development working without a token", async () => {
  vi.stubEnv("BACKEND_SERVICE_TOKEN", "");
  const fetcher = vi.fn().mockResolvedValue(new Response());
  vi.stubGlobal("fetch", fetcher);
  await backendFetch("/api/v1/demo");
  const [request] = fetcher.mock.calls[0] as [Request];
  expect(request.headers.has("authorization")).toBe(false);
});
