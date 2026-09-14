package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.MatchDevelopment;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.ParticipantObservation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public final class MatchDevelopmentService {
    private final HistoricalMatchQuery historicalMatches;
    private final MatchOverviewQuery overviews;
    private final dev.leagueanalysis.ingestion.riot.application.RiotIngestionStore ingestionStore;

    public MatchDevelopmentService(
            HistoricalMatchQuery historicalMatches,
            MatchOverviewQuery overviews) {
        this(historicalMatches, overviews, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MatchDevelopmentService(HistoricalMatchQuery historicalMatches, MatchOverviewQuery overviews,
            dev.leagueanalysis.ingestion.riot.application.RiotIngestionStore ingestionStore) {
        this.ingestionStore = ingestionStore;
        this.historicalMatches = historicalMatches;
        this.overviews = overviews;
    }

    public Optional<MatchDevelopment> load(String matchId, int focus, Integer compare) {
        requireParticipantId(focus);
        if (compare != null) {
            requireParticipantId(compare);
            if (compare == focus) {
                throw new IllegalArgumentException("MATCHED_COMPARISON_PARTICIPANT");
            }
        }
        if (ingestionStore != null) ingestionStore.enrichParticipantDetails(matchId);
        return historicalMatches.inReadSnapshot(() -> {
            var snapshot = historicalMatches.load(matchId);
            var overview = overviews.load(matchId);
            if (snapshot.isEmpty() || overview.isEmpty()) {
                return Optional.empty();
            }
            return develop(snapshot.orElseThrow(), overview.orElseThrow(), focus, compare);
        });
    }

    private Optional<MatchDevelopment> develop(
            MatchEvidenceSnapshot snapshot,
            MatchOverviewQuery.Overview overview,
            int focus,
            Integer compare) {
        if (!snapshot.header().matchId().equals(overview.matchId())) {
            throw new IllegalStateException("MISMATCHED_MATCH_OVERVIEW");
        }
        var focusOverview = overview.participants().stream()
                .filter(participant -> participant.participantId() == focus)
                .findFirst();
        if (focusOverview.isEmpty()) {
            return Optional.empty();
        }
        if (compare != null && overview.participants().stream()
                .noneMatch(participant -> participant.participantId() == compare)) {
            return Optional.empty();
        }
        var focal = focusOverview.orElseThrow();
        var summary = new MatchDevelopment.Summary(
                overview.queueId(), overview.mapId(), overview.gameMode(), overview.gameVersion(),
                overview.gameCreationMs(), overview.durationMs(), focus, compare, focal.win(),
                focal.kills(), focal.deaths(), focal.assists(), focal.totalCs(), focal.goldEarned());
        var roster = overview.participants().stream().map(p -> participant(p, overview.gameVersion())).toList();
        var timelineAvailable = snapshot.sourceRevision().timelineCaptureId() != null;
        var samples = timelineAvailable ? samples(snapshot, focus, compare) : List.<MatchDevelopment.Sample>of();
        var windows = selectableWindows(samples, focal.championName());
        return Optional.of(new MatchDevelopment(
                overview.matchId(),
                summary,
                roster,
                timelineAvailable,
                samples,
                windows,
                suggestedWindows(samples, focal.championName()),
                timelineAvailable ? (overview.events() == null ? events(snapshot.anchors()) : overview.events()) : List.of(),
                overview.teams()));
    }

    static MatchDevelopment.WindowSummary summarize(
            MatchDevelopment.Sample before,
            MatchDevelopment.Sample after,
            String championName) {
        var metrics = new ArrayList<String>();
        addMetric(metrics, "CS", before.csDifference(), after.csDifference());
        addMetric(metrics, "gold", before.goldDifference(), after.goldDifference());
        addMetric(metrics, "XP", before.xpDifference(), after.xpDifference());

        var allExtended = grewAndFinishedNonnegative(before.csDifference(), after.csDifference())
                && grewAndFinishedNonnegative(before.goldDifference(), after.goldDifference())
                && grewAndFinishedNonnegative(before.xpDifference(), after.xpDifference());
        var allUnchanged = !metrics.isEmpty()
                && unchanged(before.csDifference(), after.csDifference())
                && unchanged(before.goldDifference(), after.goldDifference())
                && unchanged(before.xpDifference(), after.xpDifference());
        var prefix = allExtended
                ? championName + " extended his lead: "
                : allUnchanged
                        ? championName + "'s comparison was unchanged: "
                        : championName + "'s comparison changed: ";
        var detail = metrics.isEmpty()
                ? "no comparable CS, gold, or XP values were available"
                : joinMetrics(metrics);
        return new MatchDevelopment.WindowSummary(
                before.timestampMs() + "-" + after.timestampMs(),
                before.timestampMs(),
                after.timestampMs(),
                before,
                after,
                prefix + detail + ".");
    }

    static List<MatchDevelopment.WindowSummary> suggestedWindows(
            List<MatchDevelopment.Sample> samples,
            String championName) {
        var candidates = new ArrayList<MatchDevelopment.WindowSummary>();
        for (var pair : boundedGoldPairs(samples)) {
            if (goldChange(pair.before(), pair.after()) >= 300) {
                candidates.add(summarize(pair.before(), pair.after(), championName));
            }
        }
        candidates.sort(Comparator
                .comparingLong((MatchDevelopment.WindowSummary window) -> goldChange(window)).reversed()
                .thenComparingLong(MatchDevelopment.WindowSummary::startMs));

        var selected = new ArrayList<MatchDevelopment.WindowSummary>();
        for (var candidate : candidates) {
            if (selected.stream().noneMatch(existing -> overlaps(existing, candidate))) {
                selected.add(candidate);
                if (selected.size() == 3) {
                    break;
                }
            }
        }
        selected.sort(Comparator.comparingLong(MatchDevelopment.WindowSummary::startMs));
        return List.copyOf(selected);
    }

    static List<MatchDevelopment.WindowSummary> selectableWindows(
            List<MatchDevelopment.Sample> samples,
            String championName) {
        var windows = new LinkedHashMap<String, MatchDevelopment.WindowSummary>();
        for (int start = 0; start < samples.size(); start++) {
            var before = samples.get(start);
            if (start + 1 < samples.size()) {
                addWindow(windows, before, samples.get(start + 1), championName);
            }
        }
        for (var pair : boundedGoldPairs(samples)) {
            addWindow(windows, pair.before(), pair.after(), championName);
        }
        return windows.values().stream()
                .sorted(Comparator.comparingLong(MatchDevelopment.WindowSummary::startMs)
                        .thenComparingLong(MatchDevelopment.WindowSummary::endMs))
                .toList();
    }

    private static List<SamplePair> boundedGoldPairs(List<MatchDevelopment.Sample> samples) {
        var pairs = new ArrayList<SamplePair>();
        for (int start = 0; start < samples.size(); start++) {
            var before = samples.get(start);
            if (before.goldDifference() == null) {
                continue;
            }
            for (int end = start + 1; end < samples.size(); end++) {
                var after = samples.get(end);
                var elapsed = after.timestampMs() - before.timestampMs();
                if (elapsed < 120_000) {
                    continue;
                }
                if (elapsed > 180_000) {
                    break;
                }
                if (after.goldDifference() == null) {
                    continue;
                }
                pairs.add(new SamplePair(before, after));
                break;
            }
        }
        return pairs;
    }

    private static void addWindow(
            LinkedHashMap<String, MatchDevelopment.WindowSummary> windows,
            MatchDevelopment.Sample before,
            MatchDevelopment.Sample after,
            String championName) {
        if (!hasComparableMetric(before, after)) {
            return;
        }
        var window = summarize(before, after, championName);
        windows.putIfAbsent(window.id(), window);
    }

    private static boolean hasComparableMetric(
            MatchDevelopment.Sample before,
            MatchDevelopment.Sample after) {
        return available(before.csDifference(), after.csDifference())
                || available(before.goldDifference(), after.goldDifference())
                || available(before.xpDifference(), after.xpDifference());
    }

    private static boolean available(Integer before, Integer after) {
        return before != null && after != null;
    }

    private static void addMetric(
            List<String> metrics,
            String name,
            Integer before,
            Integer after) {
        if (available(before, after)) {
            metrics.add(name + " " + signed(before) + " to " + signed(after));
        }
    }

    private static String joinMetrics(List<String> metrics) {
        if (metrics.size() == 1) {
            return metrics.getFirst();
        }
        if (metrics.size() == 2) {
            return metrics.getFirst() + " and " + metrics.getLast();
        }
        return metrics.get(0) + ", " + metrics.get(1) + ", and " + metrics.get(2);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static boolean grewAndFinishedNonnegative(Integer before, Integer after) {
        return before != null && after != null && after > before && after >= 0;
    }

    private static boolean unchanged(Integer before, Integer after) {
        return before == null && after == null
                || before != null && after != null && before.equals(after);
    }

    private static long goldChange(MatchDevelopment.Sample before, MatchDevelopment.Sample after) {
        return Math.abs((long) after.goldDifference() - before.goldDifference());
    }

    private static long goldChange(MatchDevelopment.WindowSummary window) {
        return goldChange(window.before(), window.after());
    }

    private static boolean overlaps(
            MatchDevelopment.WindowSummary left,
            MatchDevelopment.WindowSummary right) {
        return left.startMs() < right.endMs() && right.startMs() < left.endMs();
    }

    private MatchDevelopment.Participant participant(MatchOverviewQuery.Participant participant, String gameVersion) {
        return new MatchDevelopment.Participant(
                participant.participantId(), participant.teamId(), participant.championId(),
                participant.championName(), participant.teamPosition(), participant.win(),
                participant.kills(), participant.deaths(), participant.assists(),
                participant.laneCs(), participant.jungleCs(), participant.totalCs(),
                participant.goldEarned(), participant.goldSpent(), participant.visionScore(),
                participant.summonerSpellOneId(), participant.summonerSpellTwoId(),
                participant.endItemIds(), participant.gameName(), participant.tagLine(), participant.summonerName(),
                RunePerformanceMapper.project(participant.runeSnapshot(), gameVersion), participant.participantTotals());
    }

    private List<MatchDevelopment.Sample> samples(
            MatchEvidenceSnapshot snapshot,
            int focus,
            Integer compare) {
        var byParticipantAndTime = new TreeMap<ParticipantTime, List<ParticipantObservation>>();
        for (var observation : snapshot.observations()) {
            byParticipantAndTime.computeIfAbsent(
                    new ParticipantTime(observation.participantId(), observation.representedAtMs()),
                    ignored -> new ArrayList<>()).add(observation);
        }
        var knownTimes = snapshot.observations().stream()
                .map(ParticipantObservation::representedAtMs)
                .distinct()
                .sorted()
                .toList();
        return knownTimes.stream().map(timestamp -> {
            var focal = reconcile(byParticipantAndTime.get(new ParticipantTime(focus, timestamp)));
            var compared = compare == null
                    ? Optional.<State>empty()
                    : reconcile(byParticipantAndTime.get(new ParticipantTime(compare, timestamp)));
            return sample(timestamp, focal, compared);
        }).toList();
    }

    private MatchDevelopment.Sample sample(
            long timestamp,
            Optional<State> focal,
            Optional<State> compared) {
        var focalState = focal.orElse(null);
        var comparedState = compared.orElse(null);
        var comparable = focalState != null && comparedState != null;
        return new MatchDevelopment.Sample(
                timestamp,
                comparable ? focalState.totalGold() - comparedState.totalGold() : null,
                comparable ? focalState.totalCs() - comparedState.totalCs() : null,
                comparable ? focalState.xp() - comparedState.xp() : null,
                focalState == null ? null : focalState.level(),
                comparedState == null ? null : comparedState.level(),
                focalState == null ? null : focalState.totalGold(),
                focalState == null ? null : focalState.totalCs(),
                focalState == null ? null : focalState.xp());
    }

    private Optional<State> reconcile(List<ParticipantObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return Optional.empty();
        }
        var states = observations.stream().map(State::from).distinct().toList();
        return states.size() == 1 ? Optional.of(states.getFirst()) : Optional.empty();
    }

    private List<MatchDevelopment.Event> events(List<MatchAnchor> anchors) {
        return anchors.stream()
                .map(anchor -> new MatchDevelopment.Event(
                        anchor.key().representedAtMs(),
                        label(anchor),
                        participantIds(anchor),
                        anchor.kind() == AnchorKind.ITEM ? nullableItemId(anchor) : null,
                        anchor.actorParticipantId(),
                        anchor.targetParticipantId(),
                        anchor.assisterParticipantIds(),
                        anchor.assistersObserved()))
                .toList();
    }

    private String label(MatchAnchor anchor) {
        return switch (anchor.key().providerEventType()) {
            case "ITEM_PURCHASED" -> "Purchased item " + itemId(anchor);
            case "ITEM_SOLD" -> "Sold item " + itemId(anchor);
            case "ITEM_DESTROYED" -> "Removed item " + itemId(anchor);
            case "ITEM_UNDO" -> "Undid item change";
            default -> switch (anchor.kind()) {
                case CHAMPION_KILL -> "Champion kill";
                case DRAGON -> "Dragon secured";
                case BARON -> "Baron secured";
                case HERALD -> "Rift Herald secured";
                case STRUCTURE -> "Structure destroyed";
                case TURRET_PLATE -> "Turret plate destroyed";
                case PROGRESSION -> "Level gained";
                case ITEM -> "Item change";
                case OTHER -> "Match event";
            };
        };
    }

    private String itemId(MatchAnchor anchor) {
        var descriptor = anchor.descriptor();
        return descriptor != null && descriptor.startsWith("ITEM:")
                ? descriptor.substring("ITEM:".length())
                : "unknown";
    }

    private Integer nullableItemId(MatchAnchor anchor) {
        var descriptor = anchor.descriptor();
        if (descriptor == null || !descriptor.startsWith("ITEM:")) {
            return null;
        }
        try {
            return Integer.valueOf(descriptor.substring("ITEM:".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Integer> participantIds(MatchAnchor anchor) {
        var ids = new LinkedHashSet<Integer>();
        if (anchor.actorParticipantId() != null && anchor.actorParticipantId() > 0) {
            ids.add(anchor.actorParticipantId());
        }
        if (anchor.targetParticipantId() != null && anchor.targetParticipantId() > 0) {
            ids.add(anchor.targetParticipantId());
        }
        ids.addAll(anchor.assisterParticipantIds());
        return ids.stream().sorted().toList();
    }

    private void requireParticipantId(int participantId) {
        if (participantId < 1 || participantId > 10) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
    }

    private record ParticipantTime(int participantId, long timestampMs)
            implements Comparable<ParticipantTime> {
        @Override
        public int compareTo(ParticipantTime other) {
            var byParticipant = Integer.compare(participantId, other.participantId);
            return byParticipant != 0 ? byParticipant : Long.compare(timestampMs, other.timestampMs);
        }
    }

    private record State(int totalGold, int level, int xp, int totalCs) {
        private static State from(ParticipantObservation observation) {
            return new State(
                    observation.totalGold(), observation.level(), observation.xp(),
                    Math.addExact(observation.minionsKilled(), observation.jungleMinionsKilled()));
        }
    }

    private record SamplePair(MatchDevelopment.Sample before, MatchDevelopment.Sample after) {}
}
