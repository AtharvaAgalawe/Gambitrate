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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningsAnalyticsIT {

    private static final String PLAYER = "seeduser";
    private static final String WHITE_SCANDINAVIAN =
            "e4 d5 exd5 Qxd5 Nc3 Qa5 d4 Nf6 Nf3 e6 Be2 Be7 O-O O-O";
    private static final String WHITE_ITALIAN =
            "e4 e5 Nf3 Nc6 Bc4 Bc5 c3 Nf6 d3 d6 O-O O-O h3 h6";

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
    void openings_returnsEcoVerdictsSortedByRatingCost() {
        seedPlayer();
        seedRapidGames();

        JsonNode body = getOpenings("/api/players/seeduser/openings?timeClass=rapid").getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("username").asText()).isEqualTo(PLAYER);
        assertThat(body.path("openings")).hasSize(2);

        JsonNode leak = body.path("openings").get(0);
        assertThat(leak.path("color").asText()).isEqualTo("white");
        assertThat(leak.path("ecoCode").asText()).isEqualTo("B01");
        assertThat(leak.path("openingName").asText()).isEqualTo("Scandinavian Defense");
        assertThat(leak.path("games").asLong()).isEqualTo(10);
        assertThat(leak.path("wins").asLong()).isEqualTo(2);
        assertThat(leak.path("draws").asLong()).isEqualTo(3);
        assertThat(leak.path("losses").asLong()).isEqualTo(5);
        assertThat(leak.path("winRate").asDouble()).isCloseTo(0.20, within(0.0001));
        assertThat(leak.path("drawRate").asDouble()).isCloseTo(0.30, within(0.0001));
        assertThat(leak.path("avgRatingDelta").asDouble()).isCloseTo(-2.6667, within(0.0001));
        assertThat(leak.path("ratingDeltaGames").asLong()).isEqualTo(9);
        assertThat(leak.path("baselineWinRate").asDouble()).isCloseTo(0.60, within(0.0001));
        assertThat(leak.path("estimatedRatingCost").asDouble()).isCloseTo(40.0, within(0.0001));
        assertThat(leak.path("verdict").asText()).isEqualTo("leak");

        JsonNode strength = body.path("openings").get(1);
        assertThat(strength.path("ecoCode").asText()).isEqualTo("C50");
        assertThat(strength.path("openingName").asText()).isEqualTo("Italian Game");
        assertThat(strength.path("games").asLong()).isEqualTo(10);
        assertThat(strength.path("winRate").asDouble()).isCloseTo(1.0, within(0.0001));
        assertThat(strength.path("avgRatingDelta").asDouble()).isCloseTo(6.8, within(0.0001));
        assertThat(strength.path("estimatedRatingCost").asDouble()).isCloseTo(-40.0, within(0.0001));
        assertThat(strength.path("verdict").asText()).isEqualTo("strength");
    }

    @Test
    void openings_appliesDateFilterAndDoesNotFlagSmallSamples() {
        seedPlayer();
        seedRapidGames();

        JsonNode body = getOpenings(
                "/api/players/seeduser/openings?timeClass=rapid&from=2024-01-01&to=2024-01-31"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("openings")).hasSize(2);
        assertThat(body.path("openings").get(0).path("ecoCode").asText()).isEqualTo("B01");
        assertThat(body.path("openings").get(0).path("games").asLong()).isEqualTo(5);
        assertThat(body.path("openings").get(0).path("wins").asLong()).isEqualTo(1);
        assertThat(body.path("openings").get(0).path("draws").asLong()).isEqualTo(1);
        assertThat(body.path("openings").get(0).path("losses").asLong()).isEqualTo(3);
        assertThat(body.path("openings").get(0).path("winRate").asDouble()).isCloseTo(0.20, within(0.0001));
        assertThat(body.path("openings").get(0).path("avgRatingDelta").asDouble())
                .isCloseTo(-2.0, within(0.0001));
        assertThat(body.path("openings").get(0).path("verdict").asText()).isEqualTo("fine");
        assertThat(body.path("openings").get(0).path("estimatedRatingCost").asDouble())
                .isCloseTo(20.0, within(0.0001));

        assertThat(body.path("openings").get(1).path("ecoCode").asText()).isEqualTo("C50");
        assertThat(body.path("openings").get(1).path("games").asLong()).isEqualTo(5);
        assertThat(body.path("openings").get(1).path("avgRatingDelta").asDouble())
                .isCloseTo(5.6, within(0.0001));
        assertThat(body.path("openings").get(1).path("verdict").asText()).isEqualTo("fine");
    }

    @Test
    void openings_supportsOpeningLineGrouping() {
        seedPlayer();
        seedRapidGames();

        JsonNode body = getOpenings(
                "/api/players/seeduser/openings?timeClass=rapid&groupBy=line&firstMoves=6"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("openings")).hasSize(2);
        assertThat(body.path("openings").get(0).path("line").asText())
                .isEqualTo("e4 d5 exd5 Qxd5 Nc3 Qa5 d4 Nf6 Nf3 e6 Be2 Be7");
        assertThat(body.path("openings").get(0).path("games").asLong()).isEqualTo(10);
        assertThat(body.path("openings").get(1).path("line").asText())
                .isEqualTo("e4 e5 Nf3 Nc6 Bc4 Bc5 c3 Nf6 d3 d6 O-O O-O");
    }

    @Test
    void openings_hidesGroupsBelowMinGames() {
        seedPlayer();
        seedRapidScandinavianGames();
        insertGame("rapid-mini-1", "rapid", playedAt(2024, 1, 20, 1), "win", 1400,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");
        insertGame("rapid-mini-2", "rapid", playedAt(2024, 1, 20, 2), "win", 1402,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");
        insertGame("rapid-mini-3", "rapid", playedAt(2024, 1, 20, 3), "win", 1404,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");

        JsonNode body = getOpenings(
                "/api/players/seeduser/openings?timeClass=rapid&minGames=5"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("openings")).hasSize(1);
        assertThat(body.path("openings").get(0).path("ecoCode").asText()).isEqualTo("B01");
        assertThat(body.path("openings").get(0).path("games").asLong()).isEqualTo(10);
        assertThat(body.path("openings").get(0).path("baselineGames").asLong()).isEqualTo(13);
        assertThat(body.path("openings").get(0).path("baselineWinRate").asDouble())
                .isCloseTo(5.0 / 13.0, within(0.0001));
    }

    @Test
    void openings_filtersByTimeClass() {
        seedPlayer();
        seedRapidGames();
        insertGame("blitz-1", "blitz", playedAt(2024, 3, 1, 1), "win", 1600,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");
        insertGame("blitz-2", "blitz", playedAt(2024, 3, 1, 2), "win", 1602,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");
        insertGame("blitz-3", "blitz", playedAt(2024, 3, 1, 3), "draw", 1600,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");
        insertGame("blitz-4", "blitz", playedAt(2024, 3, 1, 4), "win", 1602,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");
        insertGame("blitz-5", "blitz", playedAt(2024, 3, 1, 5), "draw", 1600,
                "D00", "Queen's Pawn Game", "d4 d5 c4 e6");

        JsonNode body = getOpenings(
                "/api/players/seeduser/openings?timeClass=blitz"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("openings")).hasSize(1);
        assertThat(body.path("openings").get(0).path("ecoCode").asText()).isEqualTo("D00");
        assertThat(body.path("openings").get(0).path("games").asLong()).isEqualTo(5);
        assertThat(body.path("openings").get(0).path("winRate").asDouble())
                .isCloseTo(0.60, within(0.0001));
        assertThat(body.path("openings").get(0).path("verdict").asText()).isEqualTo("fine");
    }

    @Test
    void openings_usesSeparateBaselinesForEachColor() {
        seedPlayer();
        for (int i = 1; i <= 10; i++) {
            insertGame("white-b01-" + i, "rapid", playedAt(2024, 1, 1, i),
                    i <= 8 ? "win" : "loss", 1500 + i,
                    "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        }
        for (int i = 1; i <= 10; i++) {
            insertBlackGame("black-b01-" + i, playedAt(2024, 2, 1, i),
                    i <= 2 ? "win" : "loss", 1400 + i,
                    "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        }

        JsonNode body = getOpenings(
                "/api/players/seeduser/openings?timeClass=rapid"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.path("openings")).hasSize(2);
        assertThat(body.path("openings").get(0).path("color").asText()).isEqualTo("black");
        assertThat(body.path("openings").get(0).path("baselineWinRate").asDouble())
                .isCloseTo(0.20, within(0.0001));
        assertThat(body.path("openings").get(0).path("verdict").asText()).isEqualTo("fine");
        assertThat(body.path("openings").get(1).path("color").asText()).isEqualTo("white");
        assertThat(body.path("openings").get(1).path("baselineWinRate").asDouble())
                .isCloseTo(0.80, within(0.0001));
        assertThat(body.path("openings").get(1).path("verdict").asText()).isEqualTo("fine");
    }

    @Test
    void openings_returns404_whenPlayerHasNotBeenSynced() {
        ResponseEntity<JsonNode> response =
                getOpenings("/api/players/nobody/openings");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(404);
        assertThat(response.getBody().path("message").asText())
                .contains("nobody")
                .contains("sync");
    }

    @Test
    void openings_returnsEmptyGroupsWithMessage_whenNoGamesMatchFilters() {
        seedPlayer();

        ResponseEntity<JsonNode> response = getOpenings(
                "/api/players/seeduser/openings?from=2025-01-01"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("openings")).isEmpty();
        assertThat(response.getBody().path("message").asText())
                .contains("No openings matched");
    }

    @Test
    void openings_returns400_whenDateRangeIsReversed() {
        seedPlayer();

        ResponseEntity<JsonNode> response = getOpenings(
                "/api/players/seeduser/openings?from=2024-02-01&to=2024-01-01"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("message").asText())
                .containsIgnoringCase("from")
                .containsIgnoringCase("to");
    }

    private ResponseEntity<JsonNode> getOpenings(String path) {
        return restTemplate.getForEntity(path, JsonNode.class);
    }

    private void seedPlayer() {
        jdbcTemplate.update("""
                INSERT INTO player (username, created_at)
                VALUES (?, now())
                """, PLAYER);
    }

    private void seedRapidGames() {
        seedRapidScandinavianGames();
        insertGame("rapid-italian-1", "rapid", playedAt(2024, 1, 6, 1), "win", 1488,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-2", "rapid", playedAt(2024, 1, 7, 1), "win", 1496,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-3", "rapid", playedAt(2024, 1, 8, 1), "win", 1504,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-4", "rapid", playedAt(2024, 1, 9, 1), "win", 1512,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-5", "rapid", playedAt(2024, 1, 10, 1), "win", 1520,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-6", "rapid", playedAt(2024, 2, 6, 1), "win", 1512,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-7", "rapid", playedAt(2024, 2, 7, 1), "win", 1520,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-8", "rapid", playedAt(2024, 2, 8, 1), "win", 1528,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-9", "rapid", playedAt(2024, 2, 9, 1), "win", 1536,
                "C50", "Italian Game", WHITE_ITALIAN);
        insertGame("rapid-italian-10", "rapid", playedAt(2024, 2, 10, 1), "win", 1544,
                "C50", "Italian Game", WHITE_ITALIAN);
    }

    private void seedRapidScandinavianGames() {
        insertGame("rapid-scandi-1", "rapid", playedAt(2024, 1, 1, 1), "win", 1500,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-2", "rapid", playedAt(2024, 1, 2, 1), "draw", 1498,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-3", "rapid", playedAt(2024, 1, 3, 1), "loss", 1496,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-4", "rapid", playedAt(2024, 1, 4, 1), "loss", 1494,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-5", "rapid", playedAt(2024, 1, 5, 1), "loss", 1492,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-6", "rapid", playedAt(2024, 2, 1, 1), "win", 1512,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-7", "rapid", playedAt(2024, 2, 2, 1), "draw", 1510,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-8", "rapid", playedAt(2024, 2, 3, 1), "draw", 1508,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-9", "rapid", playedAt(2024, 2, 4, 1), "loss", 1506,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
        insertGame("rapid-scandi-10", "rapid", playedAt(2024, 2, 5, 1), "loss", 1504,
                "B01", "Scandinavian Defense", WHITE_SCANDINAVIAN);
    }

    private void insertBlackGame(
            String id,
            OffsetDateTime playedAt,
            String result,
            int rating,
            String eco,
            String opening,
            String movesSan
    ) {
        insertGame(id, "rapid", playedAt, "black", result, rating, eco, opening, movesSan);
    }

    private void insertGame(
            String id,
            String timeClass,
            OffsetDateTime playedAt,
            String result,
            int rating,
            String eco,
            String opening,
            String movesSan
    ) {
        insertGame(id, timeClass, playedAt, "white", result, rating, eco, opening, movesSan);
    }

    private void insertGame(
            String id,
            String timeClass,
            OffsetDateTime playedAt,
            String color,
            String result,
            int rating,
            String eco,
            String opening,
            String movesSan
    ) {
        jdbcTemplate.update("""
                INSERT INTO game (
                    chesscom_id, player_id, played_at, time_class, time_control,
                    base_seconds, increment_seconds, color, result, termination,
                    player_rating, opponent_rating, eco_code, opening_name, moves_san, pgn
                )
                VALUES (?, 1, ?, ?, '600+5', 600, 5, ?, ?, ?, ?, ?, ?, ?, ?, '')
                """,
                id,
                playedAt,
                timeClass,
                color,
                result,
                terminationFor(result),
                rating,
                rating + 50,
                eco,
                opening,
                movesSan
        );
    }

    private static OffsetDateTime playedAt(int year, int month, int day, int sequence) {
        return OffsetDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.UTC)
                .plusMinutes(sequence);
    }

    private static String terminationFor(String result) {
        return switch (result) {
            case "win" -> "checkmate";
            case "draw" -> "agreement";
            default -> "resign";
        };
    }
}
