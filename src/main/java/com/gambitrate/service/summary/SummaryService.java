package com.gambitrate.service.summary;

import com.gambitrate.domain.Player;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.service.PlayerNotSyncedException;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.analytics.AnalyticsMath;
import com.gambitrate.service.openings.OpeningGroup;
import com.gambitrate.service.openings.OpeningVerdict;
import com.gambitrate.service.openings.OpeningsQuery;
import com.gambitrate.service.openings.OpeningsService;
import com.gambitrate.service.timecontrols.TimeControlsAnalysis;
import com.gambitrate.service.timecontrols.TimeControlsService;
import com.gambitrate.service.tilt.TiltAnalysis;
import com.gambitrate.service.tilt.TiltService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@Service
public class SummaryService {

    private static final int TOP_N = 3;
    private static final List<String> NOTES = List.of(
            "Estimated rating impact = (baselineWinRate - groupWinRate) x games x 10. "
                    + "Positive means estimated Elo cost. This is a heuristic, not a chess.com "
                    + "rating calculation.",
            "Only groups meeting minGames and a two-sided 95% guard are ranked.",
            "Opening baseline is the same-color winrate; time-class, game-length, hour, and day "
                    + "baselines are the overall filtered winrate; post-loss baseline is the "
                    + "post-win winrate.",
            "Trends is descriptive and does not produce a leak candidate.",
            "Hour/day candidates use tz; date filters remain UTC."
    );

    private final PlayerRepository playerRepository;
    private final OpeningsService openingsService;
    private final TimeControlsService timeControlsService;
    private final TiltService tiltService;

    public SummaryService(
            PlayerRepository playerRepository,
            OpeningsService openingsService,
            TimeControlsService timeControlsService,
            TiltService tiltService
    ) {
        this.playerRepository = playerRepository;
        this.openingsService = openingsService;
        this.timeControlsService = timeControlsService;
        this.tiltService = tiltService;
    }

    @Transactional(readOnly = true)
    public SummaryAnalysis analyze(String rawUsername, AnalyticsFilter filter, String rawTimezone) {
        String username = normalizeUsername(rawUsername);
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));
        filter.validate();

        OpeningsQuery openingsQuery = new OpeningsQuery(
                filter.from(),
                filter.to(),
                filter.timeClass(),
                filter.minGames(),
                false,
                6
        );
        var openings = openingsService.analyze(player, openingsQuery);
        var timeControls = timeControlsService.analyze(player, filter);
        var tilt = tiltService.analyze(player, filter, rawTimezone);

        List<Candidate> candidates = new ArrayList<>();
        addOpeningCandidates(candidates, openings);
        addTimeClassCandidates(candidates, timeControls, filter);
        addGameLengthCandidates(candidates, timeControls, filter);
        addPostLossCandidate(candidates, tilt, filter);
        addHourCandidates(candidates, tilt, filter);
        addDayCandidates(candidates, tilt, filter);

        List<Candidate> top = candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::impact).reversed()
                        .thenComparing(Candidate::area)
                        .thenComparing(Candidate::label))
                .limit(TOP_N)
                .toList();

        List<SummaryAnalysis.SummaryLeak> leaks = IntStream.range(0, top.size())
                .mapToObj(index -> {
                    Candidate candidate = top.get(index);
                    return new SummaryAnalysis.SummaryLeak(
                            index + 1,
                            candidate.area(),
                            candidate.label(),
                            candidate.evidence(),
                            AnalyticsMath.round(candidate.impact(), 2),
                            candidate.explanation()
                    );
                })
                .toList();

        return new SummaryAnalysis(
                player.getUsername(),
                filter,
                tilt.timezone(),
                leaks,
                NOTES,
                message(leaks.size())
        );
    }

    private static void addOpeningCandidates(
            List<Candidate> candidates,
            com.gambitrate.service.openings.OpeningsAnalysis openings
    ) {
        for (OpeningGroup group : openings.openings()) {
            if (group.verdict() != OpeningVerdict.LEAK || group.estimatedRatingCost() <= 0) {
                continue;
            }
            String label = "%s %s%s".formatted(
                    capitalize(group.color()),
                    group.openingName() == null ? "Unnamed opening" : group.openingName(),
                    group.ecoCode() == null ? "" : " (" + group.ecoCode() + ")"
            );
            var evidence = new SummaryAnalysis.Evidence(
                    group.color(),
                    group.ecoCode(),
                    group.openingName(),
                    null,
                    null,
                    null,
                    null,
                    group.games(),
                    group.wins(),
                    group.draws(),
                    group.losses(),
                    group.winRate(),
                    group.baselineWinRate(),
                    group.baselineGames(),
                    group.avgRatingDelta(),
                    group.ratingDeltaGames()
            );
            String explanation = "%s wins %s vs a %s same-color baseline over %d games; "
                    .formatted(
                            label,
                            AnalyticsMath.percent(group.winRate()),
                            AnalyticsMath.percent(group.baselineWinRate()),
                            group.games()
                    )
                    + "the opening verdict passes the 95% guard.";
            candidates.add(new Candidate(
                    "openings",
                    label,
                    evidence,
                    group.estimatedRatingCost(),
                    explanation
            ));
        }
    }

    private static void addTimeClassCandidates(
            List<Candidate> candidates,
            TimeControlsAnalysis timeControls,
            AnalyticsFilter filter
    ) {
        double baseline = timeControls.overall().winRate();
        long baselineGames = timeControls.overall().games();
        for (var row : timeControls.byTimeClass()) {
            if (row.games() < filter.minGames()
                    || row.winRate() >= baseline
                    || !AnalyticsMath.significantlyBelow(row.winRate(), baseline, row.games())) {
                continue;
            }
            double impact = AnalyticsMath.estimatedImpact(baseline, row.winRate(), row.games());
            if (impact <= 0) {
                continue;
            }
            String label = capitalize(row.timeClass()) + " results";
            var evidence = new SummaryAnalysis.Evidence(
                    null, null, null, row.timeClass(), null, null, null,
                    row.games(), row.wins(), row.draws(), row.losses(),
                    row.winRate(), baseline, baselineGames, null, null
            );
            String explanation = "%s wins %s vs a %s overall baseline over %d games; "
                    .formatted(
                            label,
                            AnalyticsMath.percent(row.winRate()),
                            AnalyticsMath.percent(baseline),
                            row.games()
                    )
                    + "the 95% guard is met.";
            candidates.add(new Candidate("timeClass", label, evidence, impact, explanation));
        }
    }

    private static void addGameLengthCandidates(
            List<Candidate> candidates,
            TimeControlsAnalysis timeControls,
            AnalyticsFilter filter
    ) {
        double baseline = timeControls.overall().winRate();
        long baselineGames = timeControls.overall().games();
        for (var row : timeControls.byGameLength()) {
            if (row.games() < filter.minGames()
                    || row.winRate() >= baseline
                    || !AnalyticsMath.significantlyBelow(row.winRate(), baseline, row.games())) {
                continue;
            }
            double impact = AnalyticsMath.estimatedImpact(baseline, row.winRate(), row.games());
            if (impact <= 0) {
                continue;
            }
            String label = "Games lasting " + row.bucket() + " full moves";
            var evidence = new SummaryAnalysis.Evidence(
                    null, null, null, filter.timeClass(), row.bucket(), null, null,
                    row.games(), row.wins(), row.draws(), row.losses(),
                    row.winRate(), baseline, baselineGames, null, null
            );
            String explanation = "%s win %s vs a %s overall baseline over %d games; "
                    .formatted(
                            label,
                            AnalyticsMath.percent(row.winRate()),
                            AnalyticsMath.percent(baseline),
                            row.games()
                    )
                    + "the 95% guard is met.";
            candidates.add(new Candidate("gameLength", label, evidence, impact, explanation));
        }
    }

    private static void addPostLossCandidate(
            List<Candidate> candidates,
            TiltAnalysis tilt,
            AnalyticsFilter filter
    ) {
        TiltAnalysis.TiltComparison postLoss = tilt.postLoss();
        TiltAnalysis.TiltComparison postWin = tilt.postWin();
        if (postLoss == null
                || postWin == null
                || postLoss.games() < filter.minGames()
                || postWin.games() < filter.minGames()
                || postLoss.winRate() >= postWin.winRate()
                || !AnalyticsMath.twoProportionSignificantlyBelow(
                        postLoss.wins(),
                        postLoss.games(),
                        postWin.wins(),
                        postWin.games()
                )) {
            return;
        }
        double impact = AnalyticsMath.estimatedImpact(
                postWin.winRate(),
                postLoss.winRate(),
                postLoss.games()
        );
        if (impact <= 0) {
            return;
        }
        var evidence = new SummaryAnalysis.Evidence(
                null, null, null, filter.timeClass(), null, null, null,
                postLoss.games(), postLoss.wins(), postLoss.draws(), postLoss.losses(),
                postLoss.winRate(), postWin.winRate(), postWin.games(),
                postLoss.averageRatingChange(), postLoss.ratingChangeSamples()
        );
        String explanation = "After a loss, winrate is %s vs %s after a win over %d post-loss "
                .formatted(
                        AnalyticsMath.percent(postLoss.winRate()),
                        AnalyticsMath.percent(postWin.winRate()),
                        postLoss.games()
                )
                + "and %d post-win games; the two-proportion guard is met.".formatted(postWin.games());
        candidates.add(new Candidate("postLoss", "After a loss", evidence, impact, explanation));
    }

    private static void addHourCandidates(
            List<Candidate> candidates,
            TiltAnalysis tilt,
            AnalyticsFilter filter
    ) {
        double baseline = tilt.overall().winRate();
        long baselineGames = tilt.overall().games();
        for (var row : tilt.byHour()) {
            if (row.games() < filter.minGames()
                    || row.winRate() >= baseline
                    || !AnalyticsMath.significantlyBelow(row.winRate(), baseline, row.games())) {
                continue;
            }
            double impact = AnalyticsMath.estimatedImpact(baseline, row.winRate(), row.games());
            if (impact <= 0) {
                continue;
            }
            String label = "Hour %02d (%s)".formatted(row.hourOfDay(), tilt.timezone());
            var evidence = new SummaryAnalysis.Evidence(
                    null, null, null, filter.timeClass(), null, row.hourOfDay(), null,
                    row.games(), row.wins(), row.draws(), row.losses(),
                    row.winRate(), baseline, baselineGames, null, null
            );
            String explanation = "At %02d:00 (%s), winrate is %s vs a %s overall baseline "
                    .formatted(
                            row.hourOfDay(),
                            tilt.timezone(),
                            AnalyticsMath.percent(row.winRate()),
                            AnalyticsMath.percent(baseline)
                    )
                    + "over %d games; the 95%% guard is met.".formatted(row.games());
            candidates.add(new Candidate("hourOfDay", label, evidence, impact, explanation));
        }
    }

    private static void addDayCandidates(
            List<Candidate> candidates,
            TiltAnalysis tilt,
            AnalyticsFilter filter
    ) {
        double baseline = tilt.overall().winRate();
        long baselineGames = tilt.overall().games();
        for (var row : tilt.byDay()) {
            if (row.games() < filter.minGames()
                    || row.winRate() >= baseline
                    || !AnalyticsMath.significantlyBelow(row.winRate(), baseline, row.games())) {
                continue;
            }
            double impact = AnalyticsMath.estimatedImpact(baseline, row.winRate(), row.games());
            if (impact <= 0) {
                continue;
            }
            String label = "%s (%s)".formatted(row.day(), tilt.timezone());
            var evidence = new SummaryAnalysis.Evidence(
                    null, null, null, filter.timeClass(), null, null, row.day(),
                    row.games(), row.wins(), row.draws(), row.losses(),
                    row.winRate(), baseline, baselineGames, null, null
            );
            String explanation = "On %s (%s), winrate is %s vs a %s overall baseline "
                    .formatted(
                            row.day(),
                            tilt.timezone(),
                            AnalyticsMath.percent(row.winRate()),
                            AnalyticsMath.percent(baseline)
                    )
                    + "over %d games; the 95%% guard is met.".formatted(row.games());
            candidates.add(new Candidate("dayOfWeek", label, evidence, impact, explanation));
        }
    }

    private static String message(int leakCount) {
        if (leakCount == 0) {
            return "No statistically guarded leaks were found for the requested filters.";
        }
        if (leakCount < TOP_N) {
            return "Only %d leak%s passed the 95%% guard for the requested filters."
                    .formatted(leakCount, leakCount == 1 ? "" : "s");
        }
        return null;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private record Candidate(
            String area,
            String label,
            SummaryAnalysis.Evidence evidence,
            double impact,
            String explanation
    ) {
    }
}
