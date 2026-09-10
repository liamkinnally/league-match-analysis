package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.MatchDevelopment;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchDevelopmentWindowsTest {
    @Test
    void summarizesTheExactBeforeAndAfterDifferencesWhenEveryLeadExtends() {
        var before = sample(480_000, 100, 4, 20);
        var after = sample(600_000, 510, 13, 220);

        var window = MatchDevelopmentService.summarize(before, after, "Tryndamere");

        assertThat(window.id()).isEqualTo("480000-600000");
        assertThat(window.startMs()).isEqualTo(480_000);
        assertThat(window.endMs()).isEqualTo(600_000);
        assertThat(window.before()).isEqualTo(before);
        assertThat(window.after()).isEqualTo(after);
        assertThat(window.summary()).isEqualTo(
                "Tryndamere extended his lead: CS +4 to +13, gold +100 to +510, and XP +20 to +220.");
    }

    @Test
    void describesDecliningAndOpposingTrendsAsValuesWithoutCallingThemAnExtendedLead() {
        var window = MatchDevelopmentService.summarize(
                sample(480_000, 100, 13, 220),
                sample(600_000, 510, 4, -20),
                "Tryndamere");

        assertThat(window.summary()).isEqualTo(
                "Tryndamere's comparison changed: CS +13 to +4, gold +100 to +510, and XP +220 to -20.");
        assertThat(window.summary()).doesNotContain("extended his lead");
    }

    @Test
    void omitsUnavailableXpFromTheSentenceAndKeepsItNullInTheTypedSamples() {
        var window = MatchDevelopmentService.summarize(
                sample(480_000, -25, -2, null),
                sample(600_000, 400, 7, null),
                "Tryndamere");

        assertThat(window.summary()).isEqualTo(
                "Tryndamere's comparison changed: CS -2 to +7 and gold -25 to +400.");
        assertThat(window.before().xpDifference()).isNull();
        assertThat(window.after().xpDifference()).isNull();
        assertThat(window.summary()).doesNotContain("XP");
    }

    @Test
    void identicalInputsAreReportedAsUnchangedRatherThanAnExtendedLead() {
        var window = MatchDevelopmentService.summarize(
                sample(480_000, 100, 4, 20),
                sample(600_000, 100, 4, 20),
                "Tryndamere");

        assertThat(window.summary()).isEqualTo(
                "Tryndamere's comparison was unchanged: CS +4 to +4, gold +100 to +100, and XP +20 to +20.");
        assertThat(window.summary()).doesNotContain("extended his lead");
    }

    @Test
    void includesAWindowAtTheExactThreeHundredGoldBoundary() {
        var suggestions = MatchDevelopmentService.suggestedWindows(List.of(
                sample(0, -100, -2, -20),
                sample(120_000, 200, 3, 40)), "Tryndamere");

        assertThat(suggestions).extracting(MatchDevelopment.WindowSummary::id)
                .containsExactly("0-120000");
    }

    @Test
    void usesTheFirstUsableEndpointAndBreaksOverlappingTiesByEarlierStart() {
        var suggestions = MatchDevelopmentService.suggestedWindows(List.of(
                sample(0, 0, 0, 0),
                sample(60_000, 100, 1, 10),
                sample(120_000, null, null, null),
                sample(150_000, 400, 4, 40),
                sample(180_000, 500, 5, 50)), "Tryndamere");

        assertThat(suggestions).extracting(MatchDevelopment.WindowSummary::id)
                .containsExactly("0-150000");
    }

    @Test
    void ranksByGoldChangeKeepsTouchingWindowsAndDisplaysTheChosenSetChronologically() {
        var suggestions = MatchDevelopmentService.suggestedWindows(List.of(
                sample(0, 0, 0, 0),
                sample(120_000, 300, 3, 30),
                sample(240_000, 900, 9, 90),
                sample(360_000, 1_200, 12, 120),
                sample(480_000, 2_000, 20, 200)), "Tryndamere");

        assertThat(suggestions).extracting(MatchDevelopment.WindowSummary::id)
                .containsExactly("0-120000", "120000-240000", "360000-480000");
    }

    @Test
    void selectableWindowsStayBoundedToAdjacentAndFirstTwoToThreeMinutePairs() {
        var windows = MatchDevelopmentService.selectableWindows(List.of(
                sample(0, 0, 0, 0),
                sample(60_000, 100, 1, 10),
                sample(120_000, 200, 2, 20),
                sample(180_000, 300, 3, 30)), "Tryndamere");

        assertThat(windows).extracting(MatchDevelopment.WindowSummary::id)
                .containsExactly(
                        "0-60000", "0-120000", "60000-120000",
                        "60000-180000", "120000-180000");
    }

    @Test
    void everySuggestionRemainsSelectableWhenAnEarlierEndpointHasOnlyCs() {
        var samples = List.of(
                sample(0, 0, 0, null),
                sample(120_000, null, 1, null),
                sample(150_000, 400, 2, null));

        var suggestions = MatchDevelopmentService.suggestedWindows(samples, "Tryndamere");
        var windows = MatchDevelopmentService.selectableWindows(samples, "Tryndamere");

        assertThat(suggestions).extracting(MatchDevelopment.WindowSummary::id)
                .containsExactly("0-150000");
        assertThat(windows).extracting(MatchDevelopment.WindowSummary::id)
                .containsExactly("0-120000", "0-150000", "120000-150000");
        assertThat(windows).containsAll(suggestions);
    }

    private MatchDevelopment.Sample sample(
            long timestampMs,
            Integer goldDifference,
            Integer csDifference,
            Integer xpDifference) {
        return new MatchDevelopment.Sample(
                timestampMs, goldDifference, csDifference, xpDifference,
                null, null, null, null, null);
    }
}
