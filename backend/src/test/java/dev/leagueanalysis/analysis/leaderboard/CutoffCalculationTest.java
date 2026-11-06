package dev.leagueanalysis.analysis.leaderboard;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CutoffCalculationTest {
    @Test void underfilledChallengerOccupancyLeavesGrandmasterSeatsAvailableAndAppliesMinimums() {
        var result=CutoffCalculation.calculate(List.of(new CutoffCalculation.Bucket(900,100)),
                List.of(new CutoffCalculation.Bucket(500,700)),List.of(new CutoffCalculation.Bucket(399,1000)));
        assertThat(result.challenger()).isEqualTo(new Leaderboard.Cutoff(300,800,null,800));
        assertThat(result.grandmaster()).isEqualTo(new Leaderboard.Cutoff(700,400,500,501));
        var sparse=CutoffCalculation.calculate(List.of(new CutoffCalculation.Bucket(799,299)),List.of(),List.of());
        assertThat(sparse.challenger().boundaryLp()).isNull();assertThat(sparse.grandmaster().boundaryLp()).isNull();
        assertThat(sparse.grandmaster().promotionLp()).isEqualTo(400);
    }
    @Test void tiedBoundaryRequiresOneMoreLpAndUnrepresentableTargetRejectsEstimate() {
        var tied=CutoffCalculation.calculate(List.of(new CutoffCalculation.Bucket(1500,300)),List.of(new CutoffCalculation.Bucket(900,700)),List.of());
        assertThat(tied.challenger().promotionLp()).isEqualTo(1501);assertThat(tied.grandmaster().promotionLp()).isEqualTo(901);
        assertThatThrownBy(()->CutoffCalculation.calculate(List.of(new CutoffCalculation.Bucket(Integer.MAX_VALUE,300)),List.of(),List.of())).isInstanceOf(ArithmeticException.class);
    }
}
