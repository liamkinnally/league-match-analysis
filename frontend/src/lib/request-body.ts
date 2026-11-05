/** Returns null when the request cannot be read within the route's byte limit. */
export async function readBoundedText(request: Request, maxBytes: number): Promise<string | null> {
  if (Number(request.headers.get("Content-Length")) > maxBytes) {
    // Tee cancellation may wait for another consumer; rejection of this request is immediate.
    void request.body?.cancel().catch(() => {});
    return null;
  }
  if (!request.body) return "";

  const reader = request.body.getReader();
  const bytes = new Uint8Array(maxBytes);
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) return new TextDecoder().decode(bytes.subarray(0, length));
      if (value.byteLength > maxBytes - length) {
        void reader.cancel().catch(() => {});
        return null;
      }
      bytes.set(value, length);
      length += value.byteLength;
    }
  } catch {
    return null;
  } finally {
    reader.releaseLock();
  }
}

export async function acceptsEmptyCommand(request: Request): Promise<boolean> {
  const text = (await readBoundedText(request, 64))?.trim();
  return text === "" || text === "{}";
}
