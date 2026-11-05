import { beforeEach, expect, it, vi } from "vitest";
import { POST as create } from "./player-matches/route";
import { POST as older } from "./player-matches/[runId]/older/route";
import { POST as refresh } from "./player-matches/[runId]/refresh/route";
import { POST as timeline } from "./matches/[matchId]/timeline/route";

vi.mock("server-only", () => ({}));
const run = { params: Promise.resolve({ runId: "00000000-0000-0000-0000-000000000001" }) };
const match = { params: Promise.resolve({ matchId: "NA1_1" }) };
const fetcher = vi.fn();
beforeEach(() => {
  vi.stubEnv("BACKEND_URL", "http://localhost:8080");
  fetcher.mockReset().mockImplementation(async () => Response.json({}, { status: 404 }));
  vi.stubGlobal("fetch", fetcher);
});

function chunked(bytes: Uint8Array, contentLength?: string) {
  const cancelled = vi.fn();
  let offset = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset === bytes.length) return controller.close();
      const chunk = bytes.slice(offset, offset + 11);
      offset += chunk.byteLength;
      controller.enqueue(chunk);
    },
    cancel: cancelled,
  }, { highWaterMark: 0 });
  const options = { method: "POST", body, duplex: "half", headers: contentLength ? { "Content-Length": contentLength } : undefined };
  return { request: new Request("http://localhost", options), cancelled };
}

const operations = [
  ["older", (request: Request) => older(request, run)],
  ["refresh", (request: Request) => refresh(request, run)],
  ["timeline", (request: Request) => timeline(request, match)],
] as const;
it.each(operations)("enforces the 64-byte %s limit even without a reliable declared length", async (_name, post) => {
  for (const declared of [undefined, "1"]) {
    const { request, cancelled } = chunked(new TextEncoder().encode("\u3000".repeat(60)), declared);
    expect((await post(request)).status).toBe(400);
    expect(cancelled).toHaveBeenCalledOnce();
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it("rejects a lookup crossing 4096 UTF-8 bytes before reading the rest", async () => {
  // JSON permits a literal UTF-8 player name. The ASCII padding puts the byte count
  // above the limit while keeping the UTF-16 string length inside it.
  const json = JSON.stringify({ gameName: "가".repeat(14), tagLine: "KR1" });
  const padded = json.padEnd(4096);
  for (const declared of [undefined, "1"]) {
    const { request, cancelled } = chunked(new TextEncoder().encode(padded), declared);
    expect((await create(request)).status).toBe(400);
    expect(cancelled).toHaveBeenCalledOnce();
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it("accepts a lookup of exactly 4096 UTF-8 bytes, including split multibyte characters", async () => {
  const json = JSON.stringify({ gameName: "다른 이름", tagLine: "KR1", platform: "KR" });
  const bytes = new TextEncoder().encode(json + " ".repeat(4096 - new TextEncoder().encode(json).length));
  const { request } = chunked(bytes);
  expect((await create(request)).status).toBe(404);
  expect(await (fetcher.mock.calls[0][0] as Request).json()).toMatchObject({ gameName: "다른 이름", platform: "KR" });
});

it.each([undefined, "1024"])("rejects an oversized cloned request before its sibling is released (length %s)", async declared => {
  const { request } = chunked(new Uint8Array(1024), declared);
  const sibling = request.clone();
  let status: number | undefined;
  const response = refresh(request, run).then(result => { status = result.status; });
  try {
    // In-memory reads are ready this turn. A tee's cancel promise stays pending
    // until its sibling finishes, which follows this assertion.
    await new Promise<void>(resolve => setImmediate(resolve));
    expect(status).toBe(400);
    expect(fetcher).not.toHaveBeenCalled();
  } finally {
    await sibling.body!.cancel();
    await response;
  }
});
