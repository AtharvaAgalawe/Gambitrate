package com.gambitrate.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeControlParser {

    private static final Pattern CONTROL = Pattern.compile("^(\\d+)(?:\\+(\\d+))?$");

    private TimeControlParser() {
    }

    public static TimeControl parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("time_control is missing");
        }
        String basePart = value.trim();
        int slash = basePart.indexOf('/');
        if (slash >= 0) {
            basePart = basePart.substring(0, slash);
        }
        Matcher matcher = CONTROL.matcher(basePart);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported time_control: " + value);
        }
        int baseSeconds = Integer.parseInt(matcher.group(1));
        int incrementSeconds = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (baseSeconds <= 0 || incrementSeconds < 0) {
            throw new IllegalArgumentException("Invalid time_control: " + value);
        }
        return new TimeControl(baseSeconds, incrementSeconds);
    }

    public record TimeControl(int baseSeconds, int incrementSeconds) {
    }
}
