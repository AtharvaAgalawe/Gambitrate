package com.gambitrate.service.openings;

import java.util.List;

public record OpeningsAnalysis(
        String username,
        OpeningsQuery query,
        List<OpeningGroup> openings,
        String message
) {
}
