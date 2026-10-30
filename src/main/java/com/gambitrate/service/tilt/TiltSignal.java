package com.gambitrate.service.tilt;

public record TiltSignal(
        Double score,
        boolean sampleSufficient,
        boolean significant,
        String verdict
) {
}
