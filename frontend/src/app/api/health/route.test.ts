import { beforeEach, expect, it, vi } from "vitest";
import { GET } from "./route";

vi.mock("server-only", () => ({}));

beforeEach(() => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
});

it("returns only a healthy status and disables caching", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
    Response.json({ status: "UP", details: { private: "not for the browser" } }),
  ));
  const response = await GET();
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ status: "UP" });
  expect(response.headers.get("cache-control")).toBe("no-store");
});

it.each<[string, () => Promise<Response>]>([
  ["unhealthy HTTP response", () => Promise.resolve(
    Response.json({ status: "DOWN" }, { status: 503 }))],
  ["unexpected body", () => Promise.resolve(Response.json({ message: "hello" }))],
  ["invalid JSON", () => Promise.resolve(new Response("not json"))],
  ["connection failure", () => Promise.reject(new Error("connection refused"))],
  ["timeout", () => Promise.reject(new DOMException("expired", "TimeoutError"))],
])("returns unavailable for %s", async (_label, fetchResult) => {
  vi.stubGlobal("fetch", vi.fn(fetchResult));
  const response = await GET();
  expect(response.status).toBe(503);
  expect(await response.json()).toEqual({ status: "DOWN" });
  expect(response.headers.get("cache-control")).toBe("no-store");
});

it("fails closed without server configuration", async () => {
  vi.stubEnv("BACKEND_URL", "");
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  const response = await GET();
  expect(response.status).toBe(503);
  expect(await response.json()).toEqual({ status: "DOWN" });
  expect(fetchMock).not.toHaveBeenCalled();
});
