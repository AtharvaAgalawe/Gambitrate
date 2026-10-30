package com.gambitrate.api.dto;

import com.gambitrate.service.openings.OpeningGroup;
import com.gambitrate.service.openings.OpeningsAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "OpeningsResponse", description = "SQL-first opening analysis with sample-size-guarded verdicts.")
public record OpeningsResponse(
        @Schema(example = "hikaru") String username,
        OpeningsFilterResponse filters,
        @Schema(description = "Sorted by estimatedRatingCost descending.")
        List<OpeningGroupResponse> openings,
        @Schema(example = "No openings matched the requested filters and minimum game count.",
                nullable = true)
        String message
) {

    public static OpeningsResponse from(OpeningsAnalysis analysis) {
        OpeningsFilterResponse filters = new OpeningsFilterResponse(
                analysis.query().from(),
                analysis.query().to(),
                analysis.query().timeClass(),
                analysis.query().minGames(),
                analysis.query().groupByLine() ? "line" : "eco",
                analysis.query().firstMoves()
        );
        List<OpeningGroupResponse> openings = analysis.openings().stream()
                .map(OpeningsResponse::toResponse)
                .toList();
        return new OpeningsResponse(analysis.username(), filters, openings, analysis.message());
    }

    private static OpeningGroupResponse toResponse(OpeningGroup group) {
        return new OpeningGroupResponse(
                group.color(),
                group.ecoCode(),
                group.openingName(),
                group.line(),
                group.games(),
                group.wins(),
                group.draws(),
                group.losses(),
                group.winRate(),
                group.drawRate(),
                group.avgRatingDelta(),
                group.ratingDeltaGames(),
                group.baselineWinRate(),
                group.baselineGames(),
                group.estimatedRatingCost(),
                group.verdict().label(),
                group.verdictReason()
        );
    }
}
