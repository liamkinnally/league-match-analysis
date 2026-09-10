package dev.leagueanalysis.analysis.death.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeathContextRequestTest {
    @Test
    void rejectsBlankMatchIds() {
        assertThatThrownBy(() -> request("   ", 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BLANK_MATCH_ID");
        assertThatThrownBy(() -> request(null, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BLANK_MATCH_ID");
    }

    @Test
    void rejectsEveryNegativeEventOrderAndToleranceValue() {
        assertThatThrownBy(() -> request("NA1_1", -1, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NEGATIVE_DEATH_FRAME_TIME");
        assertThatThrownBy(() -> request("NA1_1", 0, -1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NEGATIVE_DEATH_EVENT_INDEX");
        assertThatThrownBy(() -> request("NA1_1", 0, 0, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NEGATIVE_MAX_PRIOR_OBSERVATION_AGE");
        assertThatThrownBy(() -> request("NA1_1", 0, 0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NEGATIVE_OBJECTIVE_LOOKBACK");
        assertThatThrownBy(() -> request("NA1_1", 0, 0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NEGATIVE_OBJECTIVE_LOOKAHEAD");
    }

    @Test
    void acceptsZeroAsAnExplicitNoToleranceOrWindowRequest() {
        var request = request("  NA1_1  ", 0, 0, 0, 0, 0);

        assertThat(request.matchId()).isEqualTo("NA1_1");
        assertThat(request.deathFrameAtMs()).isZero();
        assertThat(request.deathFrameEventIndex()).isZero();
        assertThat(request.maxPriorObservationAgeMs()).isZero();
        assertThat(request.objectiveLookbackMs()).isZero();
        assertThat(request.objectiveLookaheadMs()).isZero();
    }

    private DeathContextRequest request(
            String matchId,
            long deathFrameAtMs,
            int deathFrameEventIndex,
            long maxPriorObservationAgeMs,
            long objectiveLookbackMs,
            long objectiveLookaheadMs) {
        return new DeathContextRequest(
                matchId,
                deathFrameAtMs,
                deathFrameEventIndex,
                maxPriorObservationAgeMs,
                objectiveLookbackMs,
                objectiveLookaheadMs);
    }
}
