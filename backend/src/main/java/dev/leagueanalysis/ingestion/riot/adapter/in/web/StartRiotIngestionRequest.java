package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StartRiotIngestionRequest(
        @NotBlank @Size(max = 64) String gameName,
        @NotBlank @Size(max = 16) String tagLine,
        @Min(1) @Max(20) int matchLimit) {
    public StartRiotIngestionRequest {
        gameName = gameName == null ? "" : gameName.strip();
        tagLine = tagLine == null ? "" : tagLine.strip();
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("UNKNOWN_REQUEST_FIELD");
    }
}
