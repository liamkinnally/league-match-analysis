import type { ChampionTimingLens as ChampionTimingPayload } from "../../lib/analysis/types";

const ASSERTION_LABELS: Record<string, string> = {
  OBSERVED: "Observed",
  RECONSTRUCTED: "Reconstructed",
  EXPERT_MAINTAINED: "Expert knowledge",
  INTERPRETED: "Interpretation",
  UNKNOWN: "Unknown",
};

const PREREQUISITE_LABELS: Record<string, string> = {
  OWNED: "Observed ownership required",
  ACTIVE_USE_REQUIRED: "Active use required",
  CHAMPION_HIT_FOR_MOVEMENT: "Champion hits required for movement speed",
};

export function ChampionTimingLens({ payload }: { payload: ChampionTimingPayload }) {
  return (
    <section className="champion-timing-lens" aria-labelledby="champion-timing-title">
      <div className="lens-heading">
        <p className="eyebrow">Capability receipt</p>
        <h3 id="champion-timing-title">Champion timing</h3>
      </div>
      <div className="champion-claims">
        {payload.claims.map((claim) => (
          <article key={claim.claimId} data-assertion-mode={claim.assertionMode}>
            <p className="context-label">
              {ASSERTION_LABELS[claim.assertionMode] ?? claim.assertionMode.toLowerCase().replaceAll("_", " ")}
            </p>
            <p>{claim.statement}</p>
          </article>
        ))}
      </div>
      <div className="champion-provenance">
        {payload.capabilities.map((capability) => {
          const assertion = capability.assertion;
          return (
            <div key={assertion?.assertionId ?? `${capability.category}:${capability.statement}`}>
              {payload.claims.some((claim) => claim.statement === capability.statement) ? null : (
                <p>{capability.statement}</p>
              )}
              {assertion ? (
                <>
                  <p>Patch {assertion.patch} · Build {assertion.applicableBuild}</p>
                  <p>
                    Approved by {assertion.reviewerId} · review revision {assertion.reviewRevision}
                  </p>
                  <a href={assertion.sourceUri}>Source revision {assertion.sourceRevision}</a>
                  {assertion.missingPrerequisites.length > 0 ? (
                    <ul aria-label="Unverified capability conditions">
                      {assertion.missingPrerequisites.map((prerequisite) => (
                        <li key={prerequisite}>
                          {PREREQUISITE_LABELS[prerequisite] ?? prerequisite.toLowerCase().replaceAll("_", " ")}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </>
              ) : null}
            </div>
          );
        })}
      </div>
      <p className="context-label">Knowledge version {payload.knowledgeVersion}</p>
    </section>
  );
}
