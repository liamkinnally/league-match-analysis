export const dynamic = "force-dynamic";

export async function GET() {
  const value = process.env.RIOT_VERIFICATION_TOKEN ?? "";
  const headers = { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" };
  // Verification is a public portal-issued value, never a Riot API credential.
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,511}$/.test(value) || /^RGAPI-/i.test(value)) {
    return new Response("Not configured", { status: 404, headers });
  }
  return new Response(value, { headers });
}
