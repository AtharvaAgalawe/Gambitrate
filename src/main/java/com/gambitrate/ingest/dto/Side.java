package com.gambitrate.ingest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Side(
        String username,
        Integer rating,
        String result
) {
}
