import { notFound, redirect } from "next/navigation";
import { MatchDevelopmentView } from "../../../../components/match-development/match-development-view";
import {
  DevelopmentNotFoundError,
  developmentLabEnabled,
  getDemoMatch,
  getMatchDevelopment,
} from "../../../../lib/development/backend-client";
import {
  developmentHref,
  parseDevelopmentSearch,
  type DevelopmentSearchParams,
} from "../../../../lib/development/route";

type Props = {
  params: Promise<{ matchId: string }>;
  searchParams: Promise<DevelopmentSearchParams>;
};

export default async function DevelopmentPage({ params, searchParams }: Props) {
  const [{ matchId }, rawSearch] = await Promise.all([params, searchParams]);
  const selection = parseDevelopmentSearch(rawSearch);

  let data;
  let demo = null;
  try {
    [data, demo] = await Promise.all([
      getMatchDevelopment(matchId, selection.focus, selection.compare),
      getDemoMatch().catch(() => null),
    ]);
  } catch (error) {
    if (error instanceof DevelopmentNotFoundError) notFound();
    throw error;
  }

  const focal = data.roster.find(
    (participant) => participant.participantId === selection.focus,
  );
  if (!focal) notFound();
  const requestedComparison =
    selection.compare === undefined
      ? undefined
      : data.roster.find(
          (participant) => participant.participantId === selection.compare,
        );
  const requestedOpponent =
    requestedComparison?.teamId !== focal.teamId
      ? requestedComparison
      : undefined;
  const sameRoleOpponents = data.roster.filter(
    (participant) =>
      participant.teamId !== focal.teamId &&
      participant.teamPosition === focal.teamPosition,
  );
  const automaticComparison =
    data.summary.mapId === 11 && focal.teamPosition !== "UNKNOWN" && sameRoleOpponents.length === 1
      ? sameRoleOpponents[0] : undefined;
  const comparison = requestedOpponent ?? automaticComparison;

  const firstTime = data.samples[0]?.timestampMs ?? 0;
  const lastTime = data.samples.at(-1)?.timestampMs ?? data.summary.durationMs;
  const exactTimes = new Set(data.samples.map((sample) => sample.timestampMs));
  const requestedInterval = selection.interval;
  const intervalIsSelectable =
    requestedInterval !== undefined &&
    exactTimes.has(requestedInterval.from) &&
    exactTimes.has(requestedInterval.to);
  const preserveForAutomaticComparison =
    requestedInterval !== undefined &&
    comparison?.participantId !== selection.compare &&
    exactTimes.has(requestedInterval.from) &&
    exactTimes.has(requestedInterval.to);
  const defaultWindow = data.suggestedWindows[0] ?? data.windows[0];
  const interval =
    intervalIsSelectable || preserveForAutomaticComparison
      ? requestedInterval!
      : defaultWindow
        ? { from: defaultWindow.startMs, to: defaultWindow.endMs }
        : { from: firstTime, to: lastTime };

  const compareChanged = comparison?.participantId !== selection.compare;
  const invalidExactInterval =
    requestedInterval !== undefined &&
    !intervalIsSelectable &&
    !preserveForAutomaticComparison;
  if (selection.hasInvalidSelection || compareChanged || invalidExactInterval) {
    redirect(
      developmentHref(
        matchId,
        focal.participantId,
        comparison?.participantId,
        intervalIsSelectable || preserveForAutomaticComparison
          ? requestedInterval
          : undefined,
        selection.metric,
        { historyRunId: selection.historyRunId, finalView: selection.finalView, runeParticipant: Array.isArray(rawSearch.runeParticipant) ? rawSearch.runeParticipant[0] : rawSearch.runeParticipant },
      ),
    );
  }

  return (
    <MatchDevelopmentView
      data={data}
      interval={interval}
      invented={
        (developmentLabEnabled() && matchId.startsWith("__lab_development")) ||
        (demo?.invented === true && demo.matchId === data.matchId)
      }
    />
  );
}
