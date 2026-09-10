package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public interface HistoricalMatchQuery {
    default <T> T inReadSnapshot(Supplier<T> work) {
        return Objects.requireNonNull(work, "work").get();
    }

    Optional<MatchEvidenceSnapshot> load(String matchId);
}
