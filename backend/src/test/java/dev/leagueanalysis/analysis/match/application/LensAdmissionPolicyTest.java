package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.ChampionTimingLens;
import dev.leagueanalysis.analysis.match.domain.LensKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.TransferLens;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LensAdmissionPolicyTest {
    private final LensAdmissionPolicy policy = new LensAdmissionPolicy();
    private TransitionPacket mixedValuePacket;
    private TransitionPacket conversionPacket;
    private TransitionPacket adversePacket;
    private TransitionPacket overlappingPacket;

    @BeforeEach
    void selectSanitizedEvidenceShapes() {
        var packets = new TransitionSelector().select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        mixedValuePacket = packetStartingAt(packets, 480_210L);
        conversionPacket = packetStartingAt(packets, 780_275L);
        adversePacket = packetStartingAt(packets, 1_080_335L);
        overlappingPacket = packetStartingAt(packets, 1_500_511L);
    }

    @Test
    void admits_only_useful_supported_lenses_and_always_keeps_receipt() {
        var admission = policy.admit(mixedValuePacket, Optional.empty());

        assertThat(admission.primary()).isEqualTo(LensKind.MAP);
        assertThat(admission.available()).containsExactly(
                LensKind.MAP, LensKind.SEQUENCE, LensKind.STATE, LensKind.RECEIPT);
        assertThat(admission.available()).doesNotContain(
                LensKind.CHAMPION_TIMING, LensKind.TRANSFER);
    }

    @Test
    void unavailable_requested_lens_falls_back_without_changing_claims() {
        var admission = policy.admit(mixedValuePacket, Optional.empty());

        var result = policy.select(admission, LensKind.CHAMPION_TIMING);

        assertThat(result.selected()).isEqualTo(LensKind.RECEIPT);
        assertThat(result.limitations()).contains("REQUESTED_LENS_UNAVAILABLE");
        assertThat(result.payload().claims())
                .containsExactlyElementsOf(mixedValuePacket.claims());
    }

    @Test
    void uses_sequence_as_primary_for_a_conversion_shape() {
        var admission = policy.admit(conversionPacket, Optional.empty());

        assertThat(admission.primary()).isEqualTo(LensKind.SEQUENCE);
        assertThat(admission.available()).contains(
                LensKind.MAP, LensKind.SEQUENCE, LensKind.STATE, LensKind.RECEIPT);
    }

    @Test
    void uses_state_as_primary_for_an_adverse_consequence_shape() {
        var admission = policy.admit(adversePacket, Optional.empty());

        assertThat(admission.primary()).isEqualTo(LensKind.STATE);
        assertThat(admission.available()).contains(LensKind.STATE, LensKind.RECEIPT);
    }

    @Test
    void admits_parallel_sequence_and_transfer_for_an_overlapping_exchange() {
        var admission = policy.admit(overlappingPacket, Optional.empty());

        assertThat(admission.primary()).isEqualTo(LensKind.SEQUENCE);
        assertThat(admission.available()).contains(
                LensKind.SEQUENCE, LensKind.TRANSFER, LensKind.RECEIPT);
    }

    @Test
    void transfer_rows_remain_neutral_observations_without_packet_wide_attribution() {
        var admission = policy.admit(overlappingPacket, Optional.empty());

        var transfer = (TransferLens) admission.payloads().get(LensKind.TRANSFER);
        assertThat(transfer.rows()).extracting(TransferLens.Row::observedFor)
                .containsOnly("OBSERVED");
        assertThat(transfer.toString().toLowerCase())
                .doesNotContain("caused", "because", "led to");
    }

    @Test
    void does_not_admit_map_without_exact_coordinates() {
        var admission = policy.admit(withoutCoordinates(mixedValuePacket), Optional.empty());

        assertThat(admission.available()).doesNotContain(LensKind.MAP);
        assertThat(admission.available()).contains(LensKind.RECEIPT);
    }

    @Test
    void does_not_admit_champion_timing_without_a_valid_champion_record() {
        var admission = policy.admit(
                conversionPacket, Optional.<ChampionTimingLens>empty());

        assertThat(admission.available()).doesNotContain(LensKind.CHAMPION_TIMING);
    }

    private TransitionPacket packetStartingAt(List<TransitionPacket> packets, long startMs) {
        return packets.stream()
                .filter(packet -> packet.interval().startMs() == startMs)
                .findFirst()
                .orElseThrow();
    }

    private TransitionPacket withoutCoordinates(TransitionPacket packet) {
        var anchors = packet.anchors().stream()
                .map(anchor -> new MatchAnchor(
                        anchor.key(), anchor.kind(), anchor.actorParticipantId(),
                        anchor.targetParticipantId(), anchor.teamId(),
                        anchor.assisterParticipantIds(), anchor.assistersObserved(),
                        null, null, anchor.descriptor(), anchor.evidenceReferences(),
                        anchor.limitationCodes()))
                .toList();
        return new TransitionPacket(
                packet.analyticalObjectId(), packet.interval(), anchors,
                packet.receipt(), packet.materialityReasons(), packet.focalRelationships(),
                packet.questionKind(), packet.title(), packet.interpretationClaim(),
                packet.claims(), packet.evidenceReferences(), packet.selectionRuleVersion(),
                packet.limitationCodes());
    }
}
