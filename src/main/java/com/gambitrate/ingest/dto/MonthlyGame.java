package com.gambitrate.ingest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MonthlyGame(
        String uuid,
        String url,
        String pgn,
        @JsonProperty("time_control") String timeControl,
        @JsonProperty("time_class") String timeClass,
        String rules,
        @JsonProperty("end_time") Long endTime,
        Side white,
        Side black,
        Accuracies accuracies
) {
}
