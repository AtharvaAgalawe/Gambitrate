package com.gambitrate.service.tilt;

import com.gambitrate.service.analytics.AnalyticsMath;

public final class TiltScoreCalculator {

    private TiltScoreCalculator() {
    }

    public static TiltSignal calculate(
            TiltAnalysis.TiltComparison postLoss,
            TiltAnalysis.TiltComparison postWin,
            int minGames
    ) {
        long lossGames = postLoss == null ? 0 : postLoss.games();
        long winGames = postWin == null ? 0 : postWin.games();
        if (lossGames < minGames || winGames < minGames) {
            return new TiltSignal(
                    null,
                    false,
                    false,
                    "Not enough games after wins and losses to judge tilt "
                            + "(%d after wins, %d after losses; minimum %d per group)."
                            .formatted(winGames, lossGames, minGames)
            );
        }

        double score = (postWin.winRate() - postLoss.winRate()) * 100.0;
        boolean significant = AnalyticsMath.twoProportionSignificantlyBelow(
                postLoss.wins(),
                postLoss.games(),
                postWin.wins(),
                postWin.games()
        );

        if (score <= 0.0) {
            return new TiltSignal(
                    AnalyticsMath.round(score, 2),
                    true,
                    false,
                    "No tilt signal: winrate after a loss is %s vs %s after a win over %d and %d games."
                            .formatted(
                                    AnalyticsMath.percent(postLoss.winRate()),
                                    AnalyticsMath.percent(postWin.winRate()),
                                    lossGames,
                                    winGames
                            )
            );
        }

        if (!significant) {
            return new TiltSignal(
                    AnalyticsMath.round(score, 2),
                    true,
                    false,
                    ("Winrate after a loss is %s vs %s after a win, a %.1f-point drop, "
                            + "but the 95%% guard is not met (%d post-loss, %d post-win games).")
                            .formatted(
                                    AnalyticsMath.percent(postLoss.winRate()),
                                    AnalyticsMath.percent(postWin.winRate()),
                                    score,
                                    lossGames,
                                    winGames
                            )
            );
        }

        return new TiltSignal(
                AnalyticsMath.round(score, 2),
                true,
                true,
                ("After a loss, winrate drops to %s from %s after a win, "
                        + "a %.1f-point tilt signal over %d post-loss and %d post-win games.")
                        .formatted(
                                AnalyticsMath.percent(postLoss.winRate()),
                                AnalyticsMath.percent(postWin.winRate()),
                                score,
                                lossGames,
                                winGames
                        )
        );
    }
}
