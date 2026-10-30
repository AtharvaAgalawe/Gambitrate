package com.gambitrate.service.openings;

public enum OpeningVerdict {
    STRENGTH,
    FINE,
    LEAK;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
