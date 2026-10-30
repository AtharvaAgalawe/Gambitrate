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
