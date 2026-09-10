package dev.leagueanalysis.analysis.match.domain;

public enum QuestionKind {
    MIXED_VALUE("mixed-value"),
    CONVERSION("advantage-conversion"),
    ADVERSE_CONSEQUENCE("adverse-consequence"),
    OVERLAPPING_EXCHANGE("overlapping-exchange");

    private final String questionId;

    QuestionKind(String questionId) {
        this.questionId = questionId;
    }

    public String questionId() {
        return questionId;
    }
}
