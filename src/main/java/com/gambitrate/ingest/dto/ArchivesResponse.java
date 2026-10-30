package com.gambitrate.ingest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArchivesResponse(List<String> archives) {
}
