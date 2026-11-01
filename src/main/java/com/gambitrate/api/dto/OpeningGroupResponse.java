package com.gambitrate.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OpeningGroup", description = "Winrate and rating impact for one color + ECO group.")
public record OpeningGroupResponse(
        @Schema(example = "white") String color,
        @Schema(example = "B01") String ecoCode,
        @Schema(example = "Scandinavian Defense") String openingName,
        @Schema(example = "e4 d5 exd5 Qxd5 Nc3 Qa5", nullable = true)
        String line,
        @Schema(example = "42") long games,
        @Schema(example = "17") long wins,
        @Schema(example = "5") long draws,
        @Schema(example = "20") long losses,
        @Schema(description = "Wins / games as a fraction from 0 to 1.", example = "0.4048")
        double winRate,
        @Schema(description = "Draws / games as a fraction from 0 to 1.", example = "0.1190")
        double drawRate,
        @Schema(description = "Average Elo change between consecutive games in the same time class.",
                example = "-2.6667", nullable = true)
        Double avgRatingDelta,
        @Schema(description = "Games that had a previous same-time-class rating and therefore a delta.",
                example = "38")
        long ratingDeltaGames,
        @Schema(description = "Winrate for the same color across all filtered games.",
                example = "0.52")
        double baselineWinRate,
        @Schema(description = "Games used in the color baseline.", example = "210")
        long baselineGames,
        @Schema(description = "Heuristic Elo cost: (baselineWinRate - winRate) x games x 10. "
                + "Positive means rating cost; negative means rating gain.", example = "48.38")
        double estimatedRatingCost,
        @Schema(allowableValues = {"strength", "fine", "leak"}, example = "leak")
        String verdict,
        @Schema(example = "You win 40.5% of these games as White. You usually win 52.0%. "
                + "Over 42 games, a gap that big is very unlikely to be luck.")
        String verdictReason
) {
}
