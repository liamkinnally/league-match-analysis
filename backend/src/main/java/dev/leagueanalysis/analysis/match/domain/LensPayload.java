package dev.leagueanalysis.analysis.match.domain;

import java.util.List;

public sealed interface LensPayload
        permits MapLens, SequenceLens, StateLens,
                ChampionTimingLens, TransferLens, ReceiptLens {
    LensKind kind();

    List<EvidenceClaim> claims();
}
