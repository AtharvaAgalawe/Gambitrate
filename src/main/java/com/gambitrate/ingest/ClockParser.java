package com.gambitrate.ingest;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClockParser {

    private static final Pattern CLOCK_ANNOTATION = Pattern.compile(
            "\\[%clk\\s+(?:(\\d+):)?(\\d{1,2}):(\\d{2}(?:\\.\\d+)?)\\]");

    private ClockParser() {
    }

    public static Long parseMillis(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        Matcher matcher = CLOCK_ANNOTATION.matcher(comment);
        if (!matcher.find()) {
            return null;
        }
        try {
            long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
            long minutes = Long.parseLong(matcher.group(2));
            BigDecimal seconds = new BigDecimal(matcher.group(3));
            return hours * 3_600_000L
                    + minutes * 60_000L
                    + seconds.multiply(BigDecimal.valueOf(1_000L)).longValue();
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
