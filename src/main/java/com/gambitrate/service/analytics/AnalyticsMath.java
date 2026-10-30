package com.gambitrate.service.analytics;

public final class AnalyticsMath {

    public static final double ELO_POINTS_PER_SCORE_UNIT = 10.0;
    public static final double Z_THRESHOLD = 1.96;

    private AnalyticsMath() {
    }

    public static double winRate(long wins, long games) {
        return games == 0 ? 0.0 : (double) wins / games;
    }

    public static double estimatedImpact(double baselineWinRate, double groupWinRate, long games) {
        return (baselineWinRate - groupWinRate) * games * ELO_POINTS_PER_SCORE_UNIT;
    }

    public static boolean significantlyBelow(double groupWinRate, double baselineWinRate, long games) {
        if (games <= 0) {
            return false;
        }
        double standardError = Math.sqrt(baselineWinRate * (1.0 - baselineWinRate) / games);
        if (standardError == 0.0) {
            return false;
        }
        return (groupWinRate - baselineWinRate) / standardError <= -Z_THRESHOLD;
    }

    public static double twoProportionZ(
            long groupWins,
            long groupGames,
            long baselineWins,
            long baselineGames
    ) {
        if (groupGames <= 0 || baselineGames <= 0) {
            return 0.0;
        }
        double pooled = (double) (groupWins + baselineWins) / (groupGames + baselineGames);
        if (pooled <= 0.0 || pooled >= 1.0) {
            return 0.0;
        }
        double standardError = Math.sqrt(
                pooled * (1.0 - pooled) * (1.0 / groupGames + 1.0 / baselineGames)
        );
        if (standardError == 0.0) {
            return 0.0;
        }
        double groupRate = (double) groupWins / groupGames;
        double baselineRate = (double) baselineWins / baselineGames;
        return (groupRate - baselineRate) / standardError;
    }

    public static boolean twoProportionSignificantlyBelow(
            long groupWins,
            long groupGames,
            long baselineWins,
            long baselineGames
    ) {
        return twoProportionZ(groupWins, groupGames, baselineWins, baselineGames) <= -Z_THRESHOLD;
    }

    public static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    public static String percent(double rate) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", rate * 100.0);
    }
}
