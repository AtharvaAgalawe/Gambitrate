package com.gambitrate.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncIngestionIT {

    private static final String PLAYER = "fixtureuser";
    private static final String PROFILE_PATH = "/pub/player/" + PLAYER;
    private static final String ARCHIVES_PATH = "/pub/player/" + PLAYER + "/games/archives";
    private static final String MONTH_PATH = "/pub/player/" + PLAYER + "/games/2024/01";
    private static final Instant PLAYED_AT = Instant.parse("2024-01-02T01:02:03Z");

    private static final WireMockServer CHESS_COM = new WireMockServer(options().dynamicPort());
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        CHESS_COM.start();
        REDIS.start();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void chessComProperties(DynamicPropertyRegistry registry) {
        registry.add("gambitrate.chesscom.base-url", CHESS_COM::baseUrl);
        registry.add("gambitrate.chesscom.request-delay", () -> "0ms");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopWireMock() {
        CHESS_COM.stop();
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE move, game, player RESTART IDENTITY CASCADE");
        CHESS_COM.resetAll();
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void sync_isIdempotent_whenRunTwice() throws Exception {
        stubChessCom();

        ResponseEntity<JsonNode> first = sync("FixtureUser");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getHeaders().getLocation()).isNotNull();
        assertThat(first.getHeaders().getLocation().getPath())
                .isEqualTo("/api/players/" + PLAYER + "/sync/status");
        JsonNode firstJob = awaitTerminalJob(PLAYER);
        assertThat(firstJob.path("status").asText()).isEqualTo("DONE");
        assertThat(firstJob.path("monthsTotal").asInt()).isEqualTo(1);
        assertThat(firstJob.path("monthsDone").asInt()).isEqualTo(1);

        assertThat(count("select count(*) from game")).isEqualTo(2);
        assertThat(count("select count(*) from move")).isEqualTo(11);
        assertThat(count("select count(*) from game where chesscom_id = 'variant-1'")).isZero();

        Map<String, Object> whiteGame = jdbcTemplate.queryForMap("""
                select color, result, termination, eco_code, opening_name, base_seconds,
                       increment_seconds, player_accuracy, moves_san
                from game
                where chesscom_id = 'game-1'
                """);
        assertThat(whiteGame.get("color")).isEqualTo("white");
        assertThat(whiteGame.get("result")).isEqualTo("win");
        assertThat(whiteGame.get("termination")).isEqualTo("checkmate");
        assertThat(whiteGame.get("eco_code")).isEqualTo("C20");
        assertThat(whiteGame.get("opening_name")).isEqualTo("Kings Pawn Opening");
        assertThat(whiteGame.get("base_seconds")).isEqualTo(600);
        assertThat(whiteGame.get("increment_seconds")).isEqualTo(5);
        assertThat((BigDecimal) whiteGame.get("player_accuracy")).isEqualByComparingTo("85.30");
        assertThat(whiteGame.get("moves_san")).isEqualTo("e4 e5 Bc4 Nc6 Qh5 Nf6 Qxf7#");

        Map<String, Object> blackGame = jdbcTemplate.queryForMap("""
                select color, result, termination, player_accuracy
                from game
                where chesscom_id = 'game-2'
                """);
        assertThat(blackGame.get("color")).isEqualTo("black");
        assertThat(blackGame.get("result")).isEqualTo("loss");
        assertThat(blackGame.get("termination")).isEqualTo("timeout");
        assertThat((BigDecimal) blackGame.get("player_accuracy")).isEqualByComparingTo("61.25");

        Map<String, Object> player = jdbcTemplate.queryForMap("""
                select username, last_synced_month, last_game_end_time
                from player
                where id = 1
                """);
        assertThat(player.get("username")).isEqualTo(PLAYER);
        assertThat(player.get("last_synced_month")).isEqualTo("2024-01");
        assertThat(player.get("last_game_end_time")).isNotNull();

        Map<String, Object> playerMove = jdbcTemplate.queryForMap("""
                select m.san, m.ply, m.clock_remaining_ms, m.is_player_move
                from move m
                join game g on g.id = m.game_id
                where g.chesscom_id = 'game-2' and m.ply = 2
                """);
        assertThat(playerMove.get("san")).isEqualTo("d5");
        assertThat(playerMove.get("clock_remaining_ms")).isEqualTo(599_000L);
        assertThat(playerMove.get("is_player_move")).isEqualTo(true);

        ResponseEntity<JsonNode> second = sync(PLAYER);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode secondJob = awaitTerminalJob(PLAYER);
        assertThat(secondJob.path("status").asText()).isEqualTo("DONE");
        assertThat(count("select count(*) from game")).isEqualTo(2);
        assertThat(count("select count(*) from move")).isEqualTo(11);
        assertThat(count("select count(*) from sync_job")).isEqualTo(2);

        CHESS_COM.verify(2, getRequestedFor(urlEqualTo(ARCHIVES_PATH))
                .withHeader("User-Agent", containing("GambitRate")));
        CHESS_COM.verify(1, getRequestedFor(urlEqualTo(MONTH_PATH)));
    }

    @Test
    void incrementalSync_usesCacheAndRevalidatesCurrentMonth() throws Exception {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        YearMonth pastMonth = currentMonth.minusMonths(1);
        String pastPath = monthPath(pastMonth);
        String currentPath = monthPath(currentMonth);
        String etag = "\"current-v1\"";

        CHESS_COM.stubFor(get(urlEqualTo(PROFILE_PATH))
                .willReturn(okJson("{\"username\":\"fixtureuser\"}")));
        String archivesJson = objectMapper.writeValueAsString(Map.of(
                "archives", List.of(
                        CHESS_COM.baseUrl() + pastPath,
                        CHESS_COM.baseUrl() + currentPath
                )
        ));
        CHESS_COM.stubFor(get(urlEqualTo(ARCHIVES_PATH)).willReturn(okJson(archivesJson)));

        CHESS_COM.stubFor(get(urlEqualTo(pastPath)).willReturn(okJson(monthWithGame("past-game"))));
        CHESS_COM.stubFor(get(urlEqualTo(currentPath))
                .inScenario("current-month")
                .whenScenarioStateIs("Started")
                .willSetStateTo("revalidated")
                .willReturn(okJson(monthWithGame("current-game")).withHeader("ETag", etag)));
        CHESS_COM.stubFor(get(urlEqualTo(currentPath))
                .inScenario("current-month")
                .whenScenarioStateIs("revalidated")
                .willReturn(aResponse().withStatus(304).withHeader("ETag", etag)));

        ResponseEntity<JsonNode> first = sync(PLAYER);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode firstJob = awaitTerminalJob(PLAYER);
        assertThat(firstJob.path("status").asText()).isEqualTo("DONE");
        assertThat(firstJob.path("monthsTotal").asInt()).isEqualTo(2);
        assertThat(firstJob.path("monthsDone").asInt()).isEqualTo(2);

        ResponseEntity<JsonNode> second = sync(PLAYER);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode secondJob = awaitTerminalJob(PLAYER);
        assertThat(secondJob.path("status").asText()).isEqualTo("DONE");
        assertThat(secondJob.path("monthsTotal").asInt()).isEqualTo(1);
        assertThat(secondJob.path("monthsDone").asInt()).isEqualTo(1);

        assertThat(count("select count(*) from game")).isEqualTo(2);
        Map<String, Object> player = jdbcTemplate.queryForMap("""
                select last_synced_month, last_game_end_time
                from player
                where id = 1
                """);
        assertThat(player.get("last_synced_month")).isEqualTo(currentMonth.toString());
        assertThat(player.get("last_game_end_time")).isNotNull();

        CHESS_COM.verify(1, getRequestedFor(urlEqualTo(pastPath)));
        CHESS_COM.verify(2, getRequestedFor(urlEqualTo(currentPath)));
        CHESS_COM.verify(1, getRequestedFor(urlEqualTo(currentPath))
                .withHeader("If-None-Match", equalTo(etag)));
    }

    @Test
    void sync_marksJobFailed_whenChessComArchivesFail() {
        CHESS_COM.stubFor(get(urlEqualTo(PROFILE_PATH))
                .willReturn(okJson("{\"username\":\"fixtureuser\"}")));
        CHESS_COM.stubFor(get(urlEqualTo(ARCHIVES_PATH))
                .willReturn(aResponse().withStatus(500)));

        ResponseEntity<JsonNode> response = sync(PLAYER);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        JsonNode job = awaitTerminalJob(PLAYER);
        assertThat(job.path("status").asText()).isEqualTo("FAILED");
        assertThat(job.path("error").asText()).contains("500");
    }

    @Test
    void sync_returns404_whenChessComUserDoesNotExist() {
        CHESS_COM.stubFor(get(urlEqualTo("/pub/player/nobody"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"not found\"}")));

        ResponseEntity<JsonNode> response =
                restTemplate.postForEntity("/api/players/nobody/sync", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("error").asText()).isEqualTo("Not Found");
        assertThat(body.path("message").asText()).contains("nobody");
        assertThat(body.path("path").asText()).isEqualTo("/api/players/nobody/sync");
        assertThat(body.path("timestamp").asText()).isNotBlank();
    }

    @Test
    void sync_returns400_whenUsernameIsInvalid() {
        ResponseEntity<JsonNode> response =
                restTemplate.postForEntity("/api/players/bad.name/sync", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(400);
    }

    @Test
    void status_returns404_whenNoSyncJobExists() {
        jdbcTemplate.update("""
                INSERT INTO player (username, created_at)
                VALUES ('idleuser', now())
                """);

        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/api/players/idleuser/sync/status", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("message").asText())
                .containsIgnoringCase("sync job");
    }

    private ResponseEntity<JsonNode> sync(String username) {
        return restTemplate.postForEntity("/api/players/" + username + "/sync", null, JsonNode.class);
    }

    private JsonNode awaitTerminalJob(String username) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<JsonNode> response =
                    restTemplate.getForEntity("/api/players/" + username + "/sync/status", JsonNode.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                last = response.getBody();
                String status = last.path("status").asText();
                if ("DONE".equals(status) || "FAILED".equals(status)) {
                    return last;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for sync job.");
            }
        }
        fail("Sync job did not finish. Last status: " + last);
        return last;
    }

    private void stubChessCom() throws Exception {
        CHESS_COM.stubFor(get(urlEqualTo(PROFILE_PATH))
                .willReturn(okJson("{\"username\":\"fixtureuser\"}")));

        String archivesJson = objectMapper.writeValueAsString(Map.of(
                "archives", List.of(CHESS_COM.baseUrl() + MONTH_PATH)
        ));
        CHESS_COM.stubFor(get(urlEqualTo(ARCHIVES_PATH)).willReturn(okJson(archivesJson)));

        Map<String, Object> whiteWin = game(
                "game-1",
                "FixtureUser",
                "Opponent",
                "win",
                "checkmated",
                "chess",
                scholarMatePgn(),
                85.3,
                78.1
        );
        Map<String, Object> blackTimeout = game(
                "game-2",
                "Opponent",
                "FixtureUser",
                "win",
                "timeout",
                "chess",
                timeoutPgn(),
                71.4,
                61.25
        );
        Map<String, Object> variant = game(
                "variant-1",
                "FixtureUser",
                "Opponent",
                "win",
                "checkmated",
                "chess960",
                scholarMatePgn(),
                90.0,
                70.0
        );

        String monthJson = objectMapper.writeValueAsString(Map.of(
                "games", List.of(whiteWin, blackTimeout, variant)
        ));
        CHESS_COM.stubFor(get(urlEqualTo(MONTH_PATH)).willReturn(okJson(monthJson)));
    }

    private Map<String, Object> game(
            String uuid,
            String whiteUsername,
            String blackUsername,
            String whiteResult,
            String blackResult,
            String rules,
            String pgn,
            double whiteAccuracy,
            double blackAccuracy
    ) {
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("uuid", uuid);
        game.put("url", "https://www.chess.com/game/live/" + uuid);
        game.put("pgn", pgn);
        game.put("time_control", "600+5");
        game.put("time_class", "rapid");
        game.put("rules", rules);
        game.put("end_time", PLAYED_AT.getEpochSecond());
        game.put("white", Map.of("username", whiteUsername, "rating", 1501, "result", whiteResult));
        game.put("black", Map.of("username", blackUsername, "rating", 1499, "result", blackResult));
        game.put("accuracies", Map.of("white", whiteAccuracy, "black", blackAccuracy));
        return game;
    }

    private long count(String sql) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    private String monthPath(YearMonth month) {
        return "/pub/player/" + PLAYER + "/games/%d/%02d".formatted(month.getYear(), month.getMonthValue());
    }

    private String monthWithGame(String uuid) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "games", List.of(game(
                        uuid,
                        "FixtureUser",
                        "Opponent",
                        "win",
                        "checkmated",
                        "chess",
                        scholarMatePgn(),
                        85.3,
                        78.1
                ))
        ));
    }

    private static String scholarMatePgn() {
        return """
                [Event "Live Chess"]
                [Site "Chess.com"]
                [Date "2024.01.02"]
                [White "FixtureUser"]
                [Black "Opponent"]
                [Result "1-0"]
                [ECO "C20"]
                [ECOUrl "https://www.chess.com/openings/Kings-Pawn-Opening"]
                [TimeControl "600+5"]
                [Termination "FixtureUser won by checkmate"]

                1. e4 {[%clk 0:09:59.9]} e5 {[%clk 0:09:59.8]} 2. Bc4 {[%clk 0:09:55.4]}
                Nc6 {[%clk 0:09:54.1]} 3. Qh5 {[%clk 0:09:50.2]} Nf6 {[%clk 0:09:47.9]}
                4. Qxf7# {[%clk 0:09:41.6]} 1-0
                """;
    }

    private static String timeoutPgn() {
        return """
                [Event "Live Chess"]
                [Site "Chess.com"]
                [Date "2024.01.02"]
                [White "Opponent"]
                [Black "FixtureUser"]
                [Result "1-0"]
                [ECO "D00"]
                [Opening "Queen's Pawn Game"]
                [TimeControl "600+5"]
                [Termination "Opponent won on time"]

                1. d4 {[%clk 0:09:58]} d5 {[%clk 0:09:59]} 2. c4 {[%clk 0:09:53]}
                e6 {[%clk 0:09:55]} 1-0
                """;
    }
}
