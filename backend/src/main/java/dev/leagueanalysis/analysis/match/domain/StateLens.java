package dev.leagueanalysis.analysis.match.domain;

import java.util.List;
import java.util.Objects;

public record StateLens(StateReceipt receipt, List<EvidenceClaim> claims)
        implements LensPayload {
    public StateLens {
        receipt = Objects.requireNonNull(receipt, "receipt");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    @Override
    public LensKind kind() {
        return LensKind.STATE;
    }
}
