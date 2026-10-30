package com.gambitrate.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase4AnalyticsIT {

    private static final String TC_PLAYER = "tcuser";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE move, game, player RESTART IDENTITY CASCADE");
    }

    @Test
    void timeControls_returnsExactWinRatesTerminationsAndClockCoverage() {
        seedTimeControlsData();

        JsonNode body = get("/api/players/" + TC_PLAYER + "/time-controls").getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("overall").path("games").asLong()).isEqualTo(15);
        assertThat(body.path("overall").path("wins").asLong()).isEqualTo(7);
        assertThat(body.path("overall").path("draws").asLong()).isEqualTo(3);
        assertThat(body.path("overall").path("losses").asLong()).isEqualTo(5);
        assertThat(body.path("overall").path("winRate").asDouble()).isCloseTo(0.4667, within(0.0001));

        JsonNode rapid = findBy(body.path("byTimeClass"), "timeClass", "rapid");
        assertThat(rapid.path("games").asLong()).isEqualTo(10);
        assertThat(rapid.path("wins").asLong()).isEqualTo(6);
        assertThat(rapid.path("draws").asLong()).isEqualTo(1);
        assertThat(rapid.path("losses").asLong()).isEqualTo(3);
        assertThat(rapid.path("winRate").asDouble()).isCloseTo(0.6, within(0.0001));

        JsonNode shortBucket = findBy(body.path("byGameLength"), "bucket", "1-19");
        assertThat(shortBucket.path("games").asLong()).isEqualTo(10);
        assertThat(shortBucket.path("wins").asLong()).isEqualTo(5);
        assertThat(shortBucket.path("draws").asLong()).isEqualTo(2);
        assertThat(shortBucket.path("losses").asLong()).isEqualTo(3);
        assertThat(shortBucket.path("winRate").asDouble()).isCloseTo(0.5, within(0.0001));
        assertThat(shortBucket.path("label").asText()).isEqualTo("1-19 moves");

        JsonNode longBucket = findBy(body.path("byGameLength"), "bucket", "20-39");
        assertThat(longBucket.path("games").asLong()).isEqualTo(5);
        assertThat(longBucket.path("wins").asLong()).isEqualTo(2);
        assertThat(longBucket.path("draws").asLong()).isEqualTo(1);
        assertThat(longBucket.path("losses").asLong()).isEqualTo(2);
        assertThat(longBucket.path("winRate").asDouble()).isCloseTo(0.4, within(0.0001));

        JsonNode rapidLosses = findBy(body.path("lossTerminations"), "timeClass", "rapid");
        assertThat(rapidLosses.path("totalLosses").asLong()).isEqualTo(3);
        assertThat(rapidLosses.path("timeout").path("losses").asLong()).isEqualTo(1);
        assertThat(rapidLosses.path("checkmate").path("losses").asLong()).isEqualTo(1);
        assertThat(rapidLosses.path("resign").path("losses").asLong()).isEqualTo(1);
        assertThat(rapidLosses.path("timeout").path("share").asDouble())
                .isCloseTo(0.3333, within(0.0001));

        JsonNode blitzLosses = findBy(body.path("lossTerminations"), "timeClass", "blitz");
        assertThat(blitzLosses.path("totalLosses").asLong()).isEqualTo(2);
        assertThat(blitzLosses.path("timeout").path("share").asDouble())
                .isCloseTo(0.5, within(0.0001));
        assertThat(blitzLosses.path("resign").path("share").asDouble())
                .isCloseTo(0.5, within(0.0001));

        JsonNode rapidMove10 = clockAt(body, "rapid", 10);
        assertThat(rapidMove10.path("gamesInTimeClass").asLong()).isEqualTo(10);
        assertThat(rapidMove10.path("gamesWithClock").asLong()).isEqualTo(10);
        assertThat(rapidMove10.path("coverage").asDouble()).isCloseTo(1.0, within(0.0001));
        assertThat(rapidMove10.path("averageClockRemainingMs").asLong()).isEqualTo(350_000);
        assertThat(rapidMove10.path("averageClockRemainingSeconds").asDouble())
                .isCloseTo(350.0, within(0.0001));

        JsonNode rapidMove20 = clockAt(body, "rapid", 20);
        assertThat(rapidMove20.path("gamesWithClock").asLong()).isEqualTo(5);
        assertThat(rapidMove20.path("coverage").asDouble()).isCloseTo(0.5, within(0.0001));
        assertThat(rapidMove20.path("averageClockRemainingMs").asLong()).isEqualTo(200_000);

        JsonNode rapidMove30 = clockAt(body, "rapid", 30);
        assertThat(rapidMove30.path("gamesWithClock").asLong()).isEqualTo(5);
        assertThat(rapidMove30.path("coverage").asDouble()).isCloseTo(0.5, within(0.0001));
        assertThat(rapidMove30.path("averageClockRemainingMs").asLong()).isEqualTo(100_000);

        JsonNode blitzMove10 = clockAt(body, "blitz", 10);
        assertThat(blitzMove10.path("gamesWithClock").asLong()).isEqualTo(5);
        assertThat(blitzMove10.path("averageClockRemainingMs").asLong()).isEqualTo(500_000);

        JsonNode rapidLossClocks = findBy(body.path("lossClocks"), "timeClass", "rapid");
        assertThat(rapidLossClocks.path("totalLosses").asLong()).isEqualTo(3);
        assertThat(rapidLossClocks.path("lossesWithClock").asLong()).isEqualTo(3);
        assertThat(rapidLossClocks.path("coverage").asDouble()).isCloseTo(1.0, within(0.0001));
        assertThat(rapidLossClocks.path("over30s").asLong()).isEqualTo(1);
        assertThat(rapidLossClocks.path("between10And30s").asLong()).isEqualTo(1);
        assertThat(rapidLossClocks.path("under10s").asLong()).isEqualTo(1);
        assertThat(rapidLossClocks.path("shareOver30s").asDouble())
                .isCloseTo(0.3333, within(0.0001));
        assertThat(rapidLossClocks.path("shareUnder10s").asDouble())
                .isCloseTo(0.3333, within(0.0001));

        JsonNode blitzLossClocks = findBy(body.path("lossClocks"), "timeClass", "blitz");
        assertThat(blitzLossClocks.path("over30s").asLong()).isEqualTo(1);
        assertThat(blitzLossClocks.path("between10And30s").asLong()).isZero();
        assertThat(blitzLossClocks.path("under10s").asLong()).isEqualTo(1);
        assertThat(blitzLossClocks.path("shareOver30s").asDouble())
                .isCloseTo(0.5, within(0.0001));

        assertThat(body.path("notes").toString()).contains("not evidence");
        assertThat(body.path("message").isNull()).isTrue();
    }

    @Test
    void timeControls_appliesTimeClassDateAndMinGamesFilters() {
        seedTimeControlsData();
        insertGame(
                "daily-1", "daily", playedAt(2024, 3, 1, 12, 0), "white", "win", "checkmate",
                1600, null, movesSan(24)
        );

        JsonNode timeClassFiltered = get(
                "/api/players/" + TC_PLAYER + "/time-controls?timeClass=rapid"
        ).getBody();
        assertThat(timeClassFiltered).isNotNull();
        assertThat(timeClassFiltered.path("overall").path("games").asLong()).isEqualTo(10);
        assertThat(timeClassFiltered.path("byTimeClass")).hasSize(1);
        assertThat(timeClassFiltered.path("byTimeClass").get(0).path("timeClass").asText())
                .isEqualTo("rapid");

        JsonNode february = get(
                "/api/players/" + TC_PLAYER + "/time-controls?from=2024-02-01&to=2024-02-28"
        ).getBody();
        assertThat(february).isNotNull();
        assertThat(february.path("overall").path("games").asLong()).isEqualTo(5);

        JsonNode defaultMin = get(
                "/api/players/" + TC_PLAYER + "/time-controls"
        ).getBody();
        assertThat(defaultMin).isNotNull();
        assertThat(containsTimeClass(defaultMin.path("byTimeClass"), "daily")).isFalse();

        JsonNode allClasses = get(
                "/api/players/" + TC_PLAYER + "/time-controls?minGames=1"
        ).getBody();
        assertThat(allClasses).isNotNull();
        assertThat(containsTimeClass(allClasses.path("byTimeClass"), "daily")).isTrue();
    }

    @Test
    void timeControls_returnsEmptyAnalysisWithMessage_whenNoGamesMatch() {
        seedPlayer(TC_PLAYER);

        JsonNode body = get("/api/players/" + TC_PLAYER + "/time-controls?from=2025-01-01")
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("overall").path("games").asLong()).isZero();
        assertThat(body.path("byTimeClass")).isEmpty();
        assertThat(body.path("message").asText()).contains("No games matched");
    }

    @Test
    void timeControls_returns400_whenDateRangeIsReversed() {
        seedPlayer(TC_PLAYER);

        ResponseEntity<JsonNode> response = get(
                "/api/players/" + TC_PLAYER + "/time-controls?from=2024-02-01&to=2024-01-01"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("message").asText())
                .containsIgnoringCase("from")
                .containsIgnoringCase("to");
    }

    @Test
    void timeControls_returns404_whenPlayerHasNotBeenSynced() {
        ResponseEntity<JsonNode> response = get("/api/players/nobody/time-controls");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("message").asText()).contains("nobody");
    }

    @Test
    void tilt_comparesPostLossAndPostWinAndGroupsSessionsHoursAndDays() {
        seedTiltData();

        JsonNode body = get(
                "/api/players/tiltuser/tilt?minGames=1&tz=America/New_York"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("filters").path("timezone").asText())
                .isEqualTo("America/New_York");
        assertThat(body.path("sessionGapMinutes").asLong()).isEqualTo(30);
        assertThat(body.path("overall").path("games").asLong()).isEqualTo(18);
        assertThat(body.path("overall").path("wins").asLong()).isEqualTo(11);
        assertThat(body.path("overall").path("losses").asLong()).isEqualTo(7);
        assertThat(body.path("overall").path("winRate").asDouble())
                .isCloseTo(0.6111, within(0.0001));

        JsonNode postLoss = body.path("postLoss");
        assertThat(postLoss.path("previousResult").asText()).isEqualTo("loss");
        assertThat(postLoss.path("games").asLong()).isEqualTo(7);
        assertThat(postLoss.path("wins").asLong()).isEqualTo(2);
        assertThat(postLoss.path("winRate").asDouble()).isCloseTo(0.2857, within(0.0001));
        assertThat(postLoss.path("averageRatingChange").asDouble())
                .isCloseTo(-5.0, within(0.01));
        assertThat(postLoss.path("ratingChangeSamples").asLong()).isEqualTo(7);

        JsonNode postWin = body.path("postWin");
        assertThat(postWin.path("games").asLong()).isEqualTo(9);
        assertThat(postWin.path("wins").asLong()).isEqualTo(7);
        assertThat(postWin.path("winRate").asDouble()).isCloseTo(0.7778, within(0.0001));
        assertThat(postWin.path("averageRatingChange").asDouble())
                .isCloseTo(5.0, within(0.01));

        assertThat(body.path("tiltScore").asDouble()).isCloseTo(49.21, within(0.01));
        assertThat(body.path("sampleSufficient").asBoolean()).isTrue();
        assertThat(body.path("significant").asBoolean()).isTrue();
        assertThat(body.path("verdict").asText())
                .contains("After a loss")
                .contains("49.2-point");

        JsonNode firstGame = findBy(body.path("bySessionIndex"), "gameIndex", "1");
        assertThat(firstGame.path("games").asLong()).isEqualTo(2);
        assertThat(firstGame.path("wins").asLong()).isEqualTo(2);
        assertThat(firstGame.path("winRate").asDouble()).isCloseTo(1.0, within(0.0001));

        JsonNode secondGame = findBy(body.path("bySessionIndex"), "gameIndex", "2");
        assertThat(secondGame.path("games").asLong()).isEqualTo(2);
        assertThat(secondGame.path("winRate").asDouble()).isCloseTo(0.5, within(0.0001));

        JsonNode seventhGame = findBy(body.path("bySessionIndex"), "gameIndex", "7");
        assertThat(seventhGame.path("games").asLong()).isEqualTo(1);
        assertThat(seventhGame.path("winRate").asDouble()).isCloseTo(0.0, within(0.0001));

        JsonNode hour7 = findBy(body.path("byHour"), "hourOfDay", "7");
        assertThat(hour7.path("games").asLong()).isEqualTo(6);
        assertThat(hour7.path("wins").asLong()).isEqualTo(6);
        JsonNode hour8 = findBy(body.path("byHour"), "hourOfDay", "8");
        assertThat(hour8.path("games").asLong()).isEqualTo(6);
        assertThat(hour8.path("wins").asLong()).isEqualTo(1);
        assertThat(hour8.path("winRate").asDouble()).isCloseTo(0.1667, within(0.0001));
        JsonNode hour11 = findBy(body.path("byHour"), "hourOfDay", "11");
        assertThat(hour11.path("games").asLong()).isEqualTo(6);
        assertThat(hour11.path("winRate").asDouble()).isCloseTo(0.6667, within(0.0001));

        JsonNode monday = findBy(body.path("byDay"), "day", "Monday");
        assertThat(monday.path("isoDayOfWeek").asInt()).isEqualTo(1);
        assertThat(monday.path("games").asLong()).isEqualTo(18);
        assertThat(monday.path("wins").asLong()).isEqualTo(11);
        assertThat(monday.path("winRate").asDouble()).isCloseTo(0.6111, within(0.0001));

        JsonNode utcBody = get(
                "/api/players/tiltuser/tilt?minGames=1&tz=UTC"
        ).getBody();
        assertThat(utcBody).isNotNull();
        JsonNode utcHour12 = findBy(utcBody.path("byHour"), "hourOfDay", "12");
        assertThat(utcHour12.path("games").asLong()).isEqualTo(6);
        assertThat(utcHour12.path("wins").asLong()).isEqualTo(6);
    }

    @Test
    void tilt_startsNewSessionAtConfiguredGap() {
        seedPlayer("gapuser");
        insertGame("gap-1", "rapid", playedAt(2024, 1, 1, 12, 0), "white", "win", "checkmate",
                1500, null, movesSan(24));
        insertGame("gap-2", "rapid", playedAt(2024, 1, 1, 12, 20), "white", "win", "checkmate",
                1505, null, movesSan(24));
        insertGame("gap-3", "rapid", playedAt(2024, 1, 1, 12, 50), "white", "win", "checkmate",
                1510, null, movesSan(24));

        JsonNode body = get("/api/players/gapuser/tilt?minGames=1").getBody();

        assertThat(body).isNotNull();
        JsonNode firstGame = findBy(body.path("bySessionIndex"), "gameIndex", "1");
        assertThat(firstGame.path("games").asLong()).isEqualTo(2);
        JsonNode secondGame = findBy(body.path("bySessionIndex"), "gameIndex", "2");
        assertThat(secondGame.path("games").asLong()).isEqualTo(1);
    }

    @Test
    void tilt_withholdsScoreForTinySamples() {
        seedPlayer("tinyuser");
        insertGame("tiny-1", "rapid", playedAt(2024, 1, 1, 12, 0), "white", "win", "checkmate",
                1500, null, movesSan(24));
        insertGame("tiny-2", "rapid", playedAt(2024, 1, 1, 12, 10), "white", "loss", "resign",
                1495, null, movesSan(24));
        insertGame("tiny-3", "rapid", playedAt(2024, 1, 1, 12, 20), "white", "win", "checkmate",
                1500, null, movesSan(24));

        ResponseEntity<JsonNode> response = get("/api/players/tinyuser/tilt");
        JsonNode body = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body).isNotNull();
        assertThat(body.path("sessionGapMinutes").asLong()).isEqualTo(30);
        assertThat(body.path("postLoss").path("games").asLong()).isEqualTo(1);
        assertThat(body.path("postWin").path("games").asLong()).isEqualTo(1);
        assertThat(body.path("tiltScore").isNull()).isTrue();
        assertThat(body.path("sampleSufficient").asBoolean()).isFalse();
        assertThat(body.path("verdict").asText()).contains("Not enough games");
    }

    @Test
    void tilt_returns400_whenTimezoneIsInvalid() {
        seedPlayer("tzuser");

        ResponseEntity<JsonNode> response = get("/api/players/tzuser/tilt?tz=Not/AZone");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("message").asText())
                .containsIgnoringCase("tz")
                .containsIgnoringCase("timezone");
    }

    @Test
    void trends_returnsMonthlyStatsAndAccuracyCoverageChronologically() {
        seedTrendData();

        JsonNode body = get("/api/players/trenduser/trends").getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("months")).hasSize(2);
        JsonNode january = body.path("months").get(0);
        assertThat(january.path("month").asText()).isEqualTo("2024-01");
        assertThat(january.path("games").asLong()).isEqualTo(6);
        assertThat(january.path("wins").asLong()).isEqualTo(3);
        assertThat(january.path("draws").asLong()).isEqualTo(1);
        assertThat(january.path("losses").asLong()).isEqualTo(2);
        assertThat(january.path("winRate").asDouble()).isCloseTo(0.5, within(0.0001));
        assertThat(january.path("averageRating").asDouble()).isCloseTo(1512.5, within(0.01));
        assertThat(january.path("ratingGames").asLong()).isEqualTo(6);
        assertThat(january.path("averageAccuracy").asDouble()).isCloseTo(85.0, within(0.01));
        assertThat(january.path("accuracyGames").asLong()).isEqualTo(4);
        assertThat(january.path("accuracyCoverage").asDouble())
                .isCloseTo(0.6667, within(0.0001));

        JsonNode february = body.path("months").get(1);
        assertThat(february.path("month").asText()).isEqualTo("2024-02");
        assertThat(february.path("games").asLong()).isEqualTo(5);
        assertThat(february.path("wins").asLong()).isEqualTo(2);
        assertThat(february.path("losses").asLong()).isEqualTo(3);
        assertThat(february.path("winRate").asDouble()).isCloseTo(0.4, within(0.0001));
        assertThat(february.path("averageRating").asDouble()).isCloseTo(1540.0, within(0.01));
        assertThat(february.path("averageAccuracy").asDouble()).isCloseTo(65.0, within(0.01));
        assertThat(february.path("accuracyGames").asLong()).isEqualTo(3);
        assertThat(february.path("accuracyCoverage").asDouble()).isCloseTo(0.6, within(0.0001));

        assertThat(body.path("accuracyCoverage").path("gamesWithAccuracy").asLong())
                .isEqualTo(7);
        assertThat(body.path("accuracyCoverage").path("totalGames").asLong()).isEqualTo(11);
        assertThat(body.path("accuracyCoverage").path("coverage").asDouble())
                .isCloseTo(0.6364, within(0.0001));
        assertThat(body.path("notes").toString()).contains("coverage");
        assertThat(body.path("message").isNull()).isTrue();

        JsonNode allMonths = get("/api/players/trenduser/trends?minGames=4").getBody();
        assertThat(allMonths).isNotNull();
        assertThat(allMonths.path("months")).hasSize(3);
        JsonNode december = allMonths.path("months").get(0);
        assertThat(december.path("month").asText()).isEqualTo("2023-12");
        assertThat(december.path("games").asLong()).isEqualTo(4);
        assertThat(december.path("winRate").asDouble()).isCloseTo(0.25, within(0.0001));
        assertThat(december.path("averageRating").asDouble()).isCloseTo(1457.5, within(0.01));
        assertThat(december.path("averageAccuracy").asDouble()).isCloseTo(52.5, within(0.01));
        assertThat(december.path("accuracyCoverage").asDouble()).isCloseTo(0.5, within(0.0001));
        assertThat(allMonths.path("accuracyCoverage").path("coverage").asDouble())
                .isCloseTo(0.6, within(0.0001));
    }

    @Test
    void trends_filtersByTimeClassAndDate() {
        seedPlayer("trendfilteruser");
        for (int i = 1; i <= 5; i++) {
            insertGame(
                    "trend-rapid-" + i,
                    "rapid",
                    playedAt(2024, 1, i, 12, 0),
                    "white",
                    "win",
                    "checkmate",
                    1500 + i * 5,
                    new BigDecimal("80.00"),
                    movesSan(24)
            );
            insertGame(
                    "trend-blitz-" + i,
                    "blitz",
                    playedAt(2024, 2, i, 18, 0),
                    "white",
                    "loss",
                    "resign",
                    1400 - i * 5,
                    new BigDecimal("60.00"),
                    movesSan(24)
            );
        }

        JsonNode rapid = get(
                "/api/players/trendfilteruser/trends?timeClass=rapid"
        ).getBody();
        assertThat(rapid).isNotNull();
        assertThat(rapid.path("months")).hasSize(1);
        assertThat(rapid.path("months").get(0).path("month").asText()).isEqualTo("2024-01");
        assertThat(rapid.path("months").get(0).path("games").asLong()).isEqualTo(5);
        assertThat(rapid.path("months").get(0).path("winRate").asDouble())
                .isCloseTo(1.0, within(0.0001));

        JsonNode february = get(
                "/api/players/trendfilteruser/trends?from=2024-02-01&to=2024-02-29"
        ).getBody();
        assertThat(february).isNotNull();
        assertThat(february.path("months")).hasSize(1);
        assertThat(february.path("months").get(0).path("month").asText()).isEqualTo("2024-02");
        assertThat(february.path("months").get(0).path("games").asLong()).isEqualTo(5);
        assertThat(february.path("months").get(0).path("winRate").asDouble())
                .isCloseTo(0.0, within(0.0001));
    }

    @Test
    void trends_returnsEmptyMessage_whenNoMonthsMatch() {
        seedPlayer("trendempty");

        JsonNode body = get("/api/players/trendempty/trends?from=2025-01-01").getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("months")).isEmpty();
        assertThat(body.path("accuracyCoverage").path("totalGames").asLong()).isZero();
        assertThat(body.path("message").asText()).contains("No months matched");
    }

    @Test
    void summary_returnsTopThreeLeaksRankedByEstimatedRatingImpact() {
        seedSummaryData();

        JsonNode body = get("/api/players/summaryuser/summary").getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("filters").path("timezone").asText()).isEqualTo("UTC");
        assertThat(body.path("leaks")).hasSize(3);

        JsonNode postLoss = body.path("leaks").get(0);
        assertThat(postLoss.path("rank").asInt()).isEqualTo(1);
        assertThat(postLoss.path("area").asText()).isEqualTo("postLoss");
        assertThat(postLoss.path("sampleSize").asLong()).isEqualTo(14);
        assertThat(postLoss.path("estimatedRatingImpact").asDouble())
                .isCloseTo(98.89, within(0.01));
        assertThat(postLoss.path("evidence").path("winRate").asDouble())
                .isCloseTo(0.0714, within(0.0001));
        assertThat(postLoss.path("evidence").path("baselineWinRate").asDouble())
                .isCloseTo(0.7778, within(0.0001));
        assertThat(postLoss.path("evidence").path("baselineSampleSize").asLong()).isEqualTo(9);
        assertThat(postLoss.path("explanation").asText())
                .contains("After a loss")
                .contains("two-proportion guard");

        JsonNode opening = body.path("leaks").get(1);
        assertThat(opening.path("rank").asInt()).isEqualTo(2);
        assertThat(opening.path("area").asText()).isEqualTo("openings");
        assertThat(opening.path("label").asText())
                .contains("Scandinavian Defense")
                .contains("B01");
        assertThat(opening.path("sampleSize").asLong()).isEqualTo(10);
        assertThat(opening.path("estimatedRatingImpact").asDouble())
                .isCloseTo(40.0, within(0.01));
        assertThat(opening.path("evidence").path("color").asText()).isEqualTo("white");
        assertThat(opening.path("evidence").path("winRate").asDouble())
                .isCloseTo(0.1, within(0.0001));
        assertThat(opening.path("evidence").path("baselineWinRate").asDouble())
                .isCloseTo(0.5, within(0.0001));
        assertThat(opening.path("evidence").path("baselineSampleSize").asLong()).isEqualTo(20);

        JsonNode timeClass = body.path("leaks").get(2);
        assertThat(timeClass.path("rank").asInt()).isEqualTo(3);
        assertThat(timeClass.path("area").asText()).isEqualTo("timeClass");
        assertThat(timeClass.path("label").asText()).isEqualTo("Blitz results");
        assertThat(timeClass.path("sampleSize").asLong()).isEqualTo(10);
        assertThat(timeClass.path("estimatedRatingImpact").asDouble())
                .isCloseTo(33.33, within(0.01));
        assertThat(timeClass.path("evidence").path("timeClass").asText()).isEqualTo("blitz");
        assertThat(timeClass.path("evidence").path("winRate").asDouble())
                .isCloseTo(0.0, within(0.0001));
        assertThat(timeClass.path("evidence").path("baselineWinRate").asDouble())
                .isCloseTo(0.3333, within(0.0001));
        assertThat(timeClass.path("evidence").path("baselineSampleSize").asLong()).isEqualTo(30);

        for (JsonNode leak : body.path("leaks")) {
            assertThat(leak.path("filters").path("minGames").asInt()).isEqualTo(5);
            assertThat(leak.path("filters").path("timezone").asText()).isEqualTo("UTC");
            assertThat(leak.path("sampleSize").asLong())
                    .isEqualTo(leak.path("evidence").path("sampleSize").asLong());
        }

        assertThat(body.path("notes").toString()).contains("heuristic").contains("Trends");
        assertThat(body.path("message").isNull()).isTrue();
    }

    @Test
    void summary_returnsEmptyMessage_whenNoLeakPassesTheGuard() {
        seedPlayer("summaryclean");
        for (int i = 1; i <= 5; i++) {
            insertGame(
                    "clean-" + i,
                    "rapid",
                    playedAt(2024, 1, i, 12, 0),
                    "white",
                    "win",
                    "checkmate",
                    1500 + i * 5,
                    null,
                    movesSan(24)
            );
        }

        JsonNode body = get("/api/players/summaryclean/summary").getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("leaks")).isEmpty();
        assertThat(body.path("message").asText())
                .contains("No statistically guarded leaks");
    }

    private void seedSummaryData() {
        seedPlayer("summaryuser");

        for (int i = 0; i < 10; i++) {
            insertGameWithOpening(
                    "summary-b01-" + i,
                    "rapid",
                    playedAt(2024, 1, 1, 12, 0).plusMinutes(i * 10L),
                    "white",
                    i == 0 ? "win" : "loss",
                    i == 0 ? "checkmate" : "resign",
                    1500 - i * 5,
                    "B01",
                    "Scandinavian Defense",
                    movesSan(64)
            );
        }
        for (int i = 0; i < 10; i++) {
            insertGameWithOpening(
                    "summary-c50-" + i,
                    "rapid",
                    playedAt(2024, 1, 2, 12, 0).plusMinutes(i * 10L),
                    "white",
                    i == 1 ? "loss" : "win",
                    i == 1 ? "resign" : "checkmate",
                    1550 + i * 5,
                    "C50",
                    "Italian Game",
                    movesSan(24)
            );
        }
        int blitzIndex = 0;
        for (int day = 3; day <= 7; day++) {
            int hour = 18 + (day - 3);
            for (int pair = 0; pair < 2; pair++) {
                insertGameWithOpening(
                        "summary-blitz-" + blitzIndex,
                        "blitz",
                        playedAt(2024, 1, day, hour, pair * 10),
                        "black",
                        "loss",
                        "resign",
                        1400 - blitzIndex * 5,
                        "D00",
                        "Queen's Pawn Game",
                        movesSan(24)
                );
                blitzIndex++;
            }
        }
    }

    private void seedTrendData() {
        seedPlayer("trenduser");
        insertGame("trend-dec-1", "rapid", playedAt(2023, 12, 1, 12, 0),
                "white", "win", "checkmate", 1450, new BigDecimal("50.00"), movesSan(24));
        insertGame("trend-dec-2", "rapid", playedAt(2023, 12, 2, 12, 0),
                "white", "draw", "agreement", 1455, new BigDecimal("55.00"), movesSan(24));
        insertGame("trend-dec-3", "rapid", playedAt(2023, 12, 3, 12, 0),
                "white", "loss", "resign", 1460, null, movesSan(24));
        insertGame("trend-dec-4", "rapid", playedAt(2023, 12, 4, 12, 0),
                "white", "loss", "resign", 1465, null, movesSan(24));

        insertGame("trend-jan-1", "rapid", playedAt(2024, 1, 1, 12, 0),
                "white", "win", "checkmate", 1500, new BigDecimal("70.00"), movesSan(24));
        insertGame("trend-jan-2", "rapid", playedAt(2024, 1, 2, 12, 0),
                "white", "win", "checkmate", 1505, new BigDecimal("80.00"), movesSan(24));
        insertGame("trend-jan-3", "rapid", playedAt(2024, 1, 3, 12, 0),
                "white", "win", "checkmate", 1510, new BigDecimal("90.00"), movesSan(24));
        insertGame("trend-jan-4", "rapid", playedAt(2024, 1, 4, 12, 0),
                "white", "draw", "agreement", 1515, null, movesSan(24));
        insertGame("trend-jan-5", "rapid", playedAt(2024, 1, 5, 12, 0),
                "white", "loss", "resign", 1520, null, movesSan(24));
        insertGame("trend-jan-6", "rapid", playedAt(2024, 1, 6, 12, 0),
                "white", "loss", "resign", 1525, new BigDecimal("100.00"), movesSan(24));

        insertGame("trend-feb-1", "rapid", playedAt(2024, 2, 1, 12, 0),
                "white", "win", "checkmate", 1530, new BigDecimal("60.00"), movesSan(24));
        insertGame("trend-feb-2", "rapid", playedAt(2024, 2, 2, 12, 0),
                "white", "win", "checkmate", 1535, new BigDecimal("65.00"), movesSan(24));
        insertGame("trend-feb-3", "rapid", playedAt(2024, 2, 3, 12, 0),
                "white", "loss", "resign", 1540, new BigDecimal("70.00"), movesSan(24));
        insertGame("trend-feb-4", "rapid", playedAt(2024, 2, 4, 12, 0),
                "white", "loss", "resign", 1545, null, movesSan(24));
        insertGame("trend-feb-5", "rapid", playedAt(2024, 2, 5, 12, 0),
                "white", "loss", "resign", 1550, null, movesSan(24));
    }

    private void seedTiltData() {
        seedPlayer("tiltuser");
        String[] results = {
                "win", "win", "win", "win", "win", "win", "loss", "loss", "loss",
                "loss", "loss", "win", "win", "loss", "loss", "win", "win", "win"
        };
        int rating = 1500;
        for (int i = 0; i < results.length; i++) {
            boolean firstSession = i < 12;
            int withinSession = firstSession ? i : i - 12;
            int hour = firstSession ? 12 : 16;
            OffsetDateTime playedAt = OffsetDateTime.of(
                    2024, 1, 1, hour, 0, 0, 0, ZoneOffset.UTC
            ).plusMinutes(withinSession * 10L);
            String result = results[i];
            insertGame(
                    "tilt-" + (i + 1),
                    "rapid",
                    playedAt,
                    "white",
                    result,
                    "win".equals(result) ? "checkmate" : "resign",
                    rating,
                    null,
                    movesSan(24)
            );
            rating += "win".equals(result) ? 5 : -5;
        }
    }

    private void seedTimeControlsData() {
        seedPlayer(TC_PLAYER);

        long s1 = insertGame("tc-s1", "rapid", rapidShortTime(1), "white", "win", "checkmate",
                1500, null, movesSan(24));
        long s2 = insertGame("tc-s2", "rapid", rapidShortTime(2), "white", "win", "checkmate",
                1505, null, movesSan(24));
        long s3 = insertGame("tc-s3", "rapid", rapidShortTime(3), "white", "win", "checkmate",
                1510, null, movesSan(24));
        long s4 = insertGame("tc-s4", "rapid", rapidShortTime(4), "white", "win", "checkmate",
                1515, null, movesSan(24));
        long s5 = insertGame("tc-s5", "rapid", rapidShortTime(5), "white", "loss", "timeout",
                1510, null, movesSan(24));

        long l1 = insertGame("tc-l1", "rapid", rapidLongTime(1), "white", "win", "checkmate",
                1515, null, movesSan(64));
        long l2 = insertGame("tc-l2", "rapid", rapidLongTime(2), "white", "win", "checkmate",
                1520, null, movesSan(64));
        long l3 = insertGame("tc-l3", "rapid", rapidLongTime(3), "white", "draw", "agreement",
                1520, null, movesSan(64));
        long l4 = insertGame("tc-l4", "rapid", rapidLongTime(4), "white", "loss", "checkmate",
                1515, null, movesSan(64));
        long l5 = insertGame("tc-l5", "rapid", rapidLongTime(5), "white", "loss", "resign",
                1510, null, movesSan(64));

        long b1 = insertGame("tc-b1", "blitz", blitzTime(1), "white", "win", "checkmate",
                1400, null, movesSan(24));
        long b2 = insertGame("tc-b2", "blitz", blitzTime(2), "white", "draw", "agreement",
                1400, null, movesSan(24));
        long b3 = insertGame("tc-b3", "blitz", blitzTime(3), "white", "loss", "timeout",
                1395, null, movesSan(24));
        long b4 = insertGame("tc-b4", "blitz", blitzTime(4), "white", "loss", "resign",
                1390, null, movesSan(24));
        long b5 = insertGame("tc-b5", "blitz", blitzTime(5), "white", "draw", "agreement",
                1390, null, movesSan(24));

        seedMoves(s1, true, 12, 400_000L, null, null, 350_000L);
        seedMoves(s2, true, 12, 400_000L, null, null, 350_000L);
        seedMoves(s3, true, 12, 400_000L, null, null, 350_000L);
        seedMoves(s4, true, 12, 400_000L, null, null, 350_000L);
        seedMoves(s5, true, 12, 400_000L, null, null, 5_000L);

        seedMoves(l1, true, 32, 300_000L, 200_000L, 100_000L, 350_000L);
        seedMoves(l2, true, 32, 300_000L, 200_000L, 100_000L, 350_000L);
        seedMoves(l3, true, 32, 300_000L, 200_000L, 100_000L, 350_000L);
        seedMoves(l4, true, 32, 300_000L, 200_000L, 100_000L, 20_000L);
        seedMoves(l5, true, 32, 300_000L, 200_000L, 100_000L, 60_000L);

        seedMoves(b1, true, 12, 500_000L, null, null, 400_000L);
        seedMoves(b2, true, 12, 500_000L, null, null, 400_000L);
        seedMoves(b3, true, 12, 500_000L, null, null, 0L);
        seedMoves(b4, true, 12, 500_000L, null, null, 40_000L);
        seedMoves(b5, true, 12, 500_000L, null, null, 400_000L);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return restTemplate.getForEntity(path, JsonNode.class);
    }

    private void seedPlayer(String username) {
        jdbcTemplate.update("""
                INSERT INTO player (username, created_at)
                VALUES (?, now())
                """, username);
    }

    private long insertGame(
            String chesscomId,
            String timeClass,
            OffsetDateTime playedAt,
            String color,
            String result,
            String termination,
            Integer rating,
            BigDecimal accuracy,
            String movesSan
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO game (
                    chesscom_id, player_id, played_at, time_class, time_control,
                    base_seconds, increment_seconds, color, result, termination,
                    player_rating, opponent_rating, eco_code, opening_name, moves_san,
                    player_accuracy, pgn
                )
                VALUES (?, 1, ?, ?, '600+5', 600, 5, ?, ?, ?, ?, ?, 'C20', 'King Pawn',
                        ?, ?, '')
                RETURNING id
                """, Long.class,
                chesscomId,
                playedAt,
                timeClass,
                color,
                result,
                termination,
                rating,
                rating == null ? null : rating + 50,
                movesSan,
                accuracy
        );
    }

    private long insertGameWithOpening(
            String chesscomId,
            String timeClass,
            OffsetDateTime playedAt,
            String color,
            String result,
            String termination,
            Integer rating,
            String ecoCode,
            String openingName,
            String movesSan
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO game (
                    chesscom_id, player_id, played_at, time_class, time_control,
                    base_seconds, increment_seconds, color, result, termination,
                    player_rating, opponent_rating, eco_code, opening_name, moves_san, pgn
                )
                VALUES (?, 1, ?, ?, '600+5', 600, 5, ?, ?, ?, ?, ?, ?, ?, ?, '')
                RETURNING id
                """, Long.class,
                chesscomId,
                playedAt,
                timeClass,
                color,
                result,
                termination,
                rating,
                rating == null ? null : rating + 50,
                ecoCode,
                openingName,
                movesSan
        );
    }

    private void seedMoves(
            long gameId,
            boolean playerIsWhite,
            int playerMoves,
            long clockAt10,
            Long clockAt20,
            Long clockAt30,
            long finalClock
    ) {
        for (int ply = 1; ply <= playerMoves * 2; ply++) {
            boolean playerMove = (ply % 2 == 1) == playerIsWhite;
            Long clock = playerMove ? 300_000L : 300_000L;
            if (playerMove) {
                int playerMoveNumber = playerIsWhite ? (ply + 1) / 2 : ply / 2;
                if (playerMoveNumber == 10) {
                    clock = clockAt10;
                } else if (playerMoveNumber == 20 && clockAt20 != null) {
                    clock = clockAt20;
                } else if (playerMoveNumber == 30 && clockAt30 != null) {
                    clock = clockAt30;
                }
                if (playerMoveNumber == playerMoves) {
                    clock = finalClock;
                }
            }
            jdbcTemplate.update("""
                    INSERT INTO move (game_id, ply, san, clock_remaining_ms, is_player_move)
                    VALUES (?, ?, 'Nf3', ?, ?)
                    """, gameId, ply, clock, playerMove);
        }
    }

    private static JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asText())) {
                return node;
            }
        }
        throw new AssertionError("No item with " + field + "=" + value + " in " + array);
    }

    private static boolean containsTimeClass(JsonNode array, String timeClass) {
        for (JsonNode node : array) {
            if (timeClass.equals(node.path("timeClass").asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode clockAt(JsonNode body, String timeClass, int moveNumber) {
        for (JsonNode node : body.path("clockAtMoves")) {
            if (timeClass.equals(node.path("timeClass").asText())
                    && node.path("moveNumber").asInt() == moveNumber) {
                return node;
            }
        }
        throw new AssertionError("No clock row for " + timeClass + " move " + moveNumber);
    }

    private static OffsetDateTime rapidShortTime(int sequence) {
        return playedAt(2024, 1, sequence, 12, 0);
    }

    private static OffsetDateTime rapidLongTime(int sequence) {
        return playedAt(2024, 1, 10 + sequence, 12, 0);
    }

    private static OffsetDateTime blitzTime(int sequence) {
        return playedAt(2024, 2, sequence, 18, 0);
    }

    private static OffsetDateTime playedAt(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    private static String movesSan(int plies) {
        return String.join(" ", Collections.nCopies(plies, "Nf3"));
    }
}
