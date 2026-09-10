import { notFound } from "next/navigation";
import { MatchAnalysisShell } from "../../../components/match-analysis/match-analysis-shell";
import {
  MatchNotFoundError,
  StaleEvidenceError,
} from "../../../lib/analysis/backend-client";
import { loadMatchAnalysis } from "../../../lib/analysis/analysis-source";
import {
  parseAnalysisRouteContext,
  toAnalysisHref,
  type AnalysisSearchParams,
} from "../../../lib/analysis/route-context";

type Props = {
  params: Promise<{ matchId: string }>;
  searchParams: Promise<AnalysisSearchParams>;
};

export default async function MatchPage({ params, searchParams }: Props) {
  const [{ matchId }, query] = await Promise.all([params, searchParams]);
  const route = parseAnalysisRouteContext(matchId, query);

  let missing = false;
  let response;
  try {
    response = await loadMatchAnalysis(route);
  } catch (error) {
    if (error instanceof StaleEvidenceError) {
      const refreshHref = toAnalysisHref({
        matchId: route.matchId,
        focalParticipantId: route.focalParticipantId,
        mode: route.mode === "investigate"
          ? route.returnTarget?.mode === "review" ? "review" : "explore"
          : route.mode,
      });
      return (
        <main className="analysis-shell">
          <h1>Match evidence changed</h1>
          <p>Refresh this analysis before continuing. The previous selection is no longer current.</p>
          <a href={refreshHref}>Refresh analysis</a>
        </main>
      );
    } else if (error instanceof MatchNotFoundError) {
      missing = true;
    } else {
      throw error;
    }
  }

  if (missing || !response) notFound();
  return <MatchAnalysisShell response={response} route={route} />;
}
