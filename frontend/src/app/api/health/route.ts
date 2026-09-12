import { backendFetch } from "../../../lib/backend-transport";
import { isSamplePreview } from "../../../lib/preview-mode";

export const dynamic = "force-dynamic";

export async function GET(): Promise<Response> {
  const headers = { "Cache-Control": "no-store" };
  if (isSamplePreview()) return Response.json({ status: "UP", dataSource: "sample", backend: "NOT_USED" }, { headers });
  try {
    const upstream = await backendFetch("/actuator/health", {
      cache: "no-store",
      signal: AbortSignal.timeout(2000),
    });
    if (!upstream.ok) throw new Error("Backend is unavailable");

    const body: unknown = await upstream.json();
    if (typeof body !== "object" || body === null ||
        !("status" in body) || body.status !== "UP") {
      throw new Error("Backend returned an unexpected status");
    }
    return Response.json({ status: "UP" }, { headers });
  } catch {
    return Response.json({ status: "DOWN" }, { status: 503, headers });
  }
}
