package com.gambitrate.ingest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Accuracies(Double white, Double black) {
}
