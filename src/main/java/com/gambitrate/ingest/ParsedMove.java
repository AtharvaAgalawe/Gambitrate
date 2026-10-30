package com.gambitrate.ingest;

public record ParsedMove(int ply, String san, Long clockRemainingMs) {
}
