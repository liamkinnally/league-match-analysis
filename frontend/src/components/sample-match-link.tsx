import Link from "next/link";
import { getDemoMatch } from "../lib/development/backend-client";
import { developmentHref } from "../lib/development/route";

export function SampleMatchPending() {
  return <div className="entry-sample entry-sample--pending" role="status">
    <span className="entry-spinner" aria-hidden="true" /> Loading sample match…
  </div>;
}

export async function SampleMatchLink() {
  const demo = await getDemoMatch().catch(() => null);
  if (!demo) return <div className="entry-sample entry-sample--unavailable">
    <strong>Sample match unavailable</strong>
    <span>You can still search for a player above.</span>
  </div>;
  return <Link className="entry-sample" aria-label="Explore sample match"
    href={developmentHref(demo.matchId, demo.focusParticipantId, demo.compareParticipantId)}>
    <div><strong>Explore sample match</strong><span>Invented data — a complete timeline to try the app.</span></div>
    <span className="entry-sample__arrow" aria-hidden="true">→</span>
  </Link>;
}
