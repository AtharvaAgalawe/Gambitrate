package com.gambitrate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class TimeControlAnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TimeControlAnalyticsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TimeControlOverallStats findOverall(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass
    ) {
        String sql = """
                SELECT COUNT(*) AS games,
                       COALESCE(SUM(CASE WHEN g.result = 'win' THEN 1 ELSE 0 END), 0) AS wins,
                       COALESCE(SUM(CASE WHEN g.result = 'draw' THEN 1 ELSE 0 END), 0) AS draws,
                       COALESCE(SUM(CASE WHEN g.result = 'loss' THEN 1 ELSE 0 END), 0) AS losses
                FROM game g
                WHERE g.player_id = :playerId
                  AND (CAST(:from AS timestamptz) IS NULL
                           OR g.played_at >= CAST(:from AS timestamptz))
                  AND (CAST(:toExclusive AS timestamptz) IS NULL
                           OR g.played_at < CAST(:toExclusive AS timestamptz))
                  AND (CAST(:timeClass AS varchar) IS NULL
                           OR g.time_class = CAST(:timeClass AS varchar))
                """;

        return jdbcTemplate.queryForObject(sql, filterParameters(playerId, from, toExclusive, timeClass),
                (resultSet, rowNumber) -> new TimeControlOverallStats(
                        resultSet.getLong("games"),
                        resultSet.getLong("wins"),
                        resultSet.getLong("draws"),
                        resultSet.getLong("losses")
                ));
    }

    public List<TimeClassStats> findByTimeClass(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames
    ) {
        String sql = """
                SELECT g.time_class,
                       COUNT(*) AS games,
                       SUM(CASE WHEN g.result = 'win' THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN g.result = 'draw' THEN 1 ELSE 0 END) AS draws,
                       SUM(CASE WHEN g.result = 'loss' THEN 1 ELSE 0 END) AS losses,
                       AVG(CASE WHEN g.result = 'win' THEN 1.0 ELSE 0.0 END)::float8 AS win_rate
                FROM game g
                WHERE g.player_id = :playerId
                  AND (CAST(:from AS timestamptz) IS NULL
                           OR g.played_at >= CAST(:from AS timestamptz))
                  AND (CAST(:toExclusive AS timestamptz) IS NULL
                           OR g.played_at < CAST(:toExclusive AS timestamptz))
                  AND (CAST(:timeClass AS varchar) IS NULL
                           OR g.time_class = CAST(:timeClass AS varchar))
                GROUP BY g.time_class
                HAVING COUNT(*) >= :minGames
                ORDER BY g.time_class
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new TimeClassStats(
                resultSet.getString("time_class"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate")
        ));
    }

    public List<GameLengthStats> findByGameLength(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames
    ) {
        String sql = """
                WITH filtered AS (
                    SELECT g.result,
                           ((cardinality(regexp_split_to_array(
                               btrim(g.moves_san), '\\s+'
                           )) + 1) / 2) AS full_moves
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                ),
                bucketed AS (
                    SELECT f.result,
                           CASE
                               WHEN f.full_moves <= 19 THEN '1-19'
                               WHEN f.full_moves <= 39 THEN '20-39'
                               WHEN f.full_moves <= 59 THEN '40-59'
                               WHEN f.full_moves <= 79 THEN '60-79'
                               WHEN f.full_moves <= 99 THEN '80-99'
                               ELSE '100+'
                           END AS bucket,
                           CASE
                               WHEN f.full_moves <= 19 THEN 1
                               WHEN f.full_moves <= 39 THEN 2
                               WHEN f.full_moves <= 59 THEN 3
                               WHEN f.full_moves <= 79 THEN 4
                               WHEN f.full_moves <= 99 THEN 5
                               ELSE 6
                           END AS bucket_order
                    FROM filtered f
                )
                SELECT b.bucket,
                       COUNT(*) AS games,
                       SUM(CASE WHEN b.result = 'win' THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN b.result = 'draw' THEN 1 ELSE 0 END) AS draws,
                       SUM(CASE WHEN b.result = 'loss' THEN 1 ELSE 0 END) AS losses,
                       AVG(CASE WHEN b.result = 'win' THEN 1.0 ELSE 0.0 END)::float8 AS win_rate
                FROM bucketed b
                GROUP BY b.bucket, b.bucket_order
                HAVING COUNT(*) >= :minGames
                ORDER BY b.bucket_order
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new GameLengthStats(
                resultSet.getString("bucket"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate")
        ));
    }

    public List<LossTerminationStats> findLossTerminations(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames
    ) {
        String sql = """
                WITH filtered AS (
                    SELECT g.time_class, g.result, g.termination
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                ),
                totals AS (
                    SELECT time_class, COUNT(*) AS total_games
                    FROM filtered
                    GROUP BY time_class
                )
                SELECT f.time_class,
                       t.total_games,
                       COUNT(*) AS total_losses,
                       COUNT(*) FILTER (WHERE f.termination = 'timeout') AS timeout_losses,
                       COUNT(*) FILTER (WHERE f.termination = 'checkmate') AS checkmate_losses,
                       COUNT(*) FILTER (WHERE f.termination = 'resign') AS resign_losses,
                       COUNT(*) FILTER (WHERE f.termination NOT IN ('timeout', 'checkmate', 'resign'))
                           AS other_losses
                FROM filtered f
                JOIN totals t USING (time_class)
                WHERE f.result = 'loss'
                  AND t.total_games >= :minGames
                GROUP BY f.time_class, t.total_games
                ORDER BY f.time_class
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new LossTerminationStats(
                resultSet.getString("time_class"),
                resultSet.getLong("total_games"),
                resultSet.getLong("total_losses"),
                resultSet.getLong("timeout_losses"),
                resultSet.getLong("checkmate_losses"),
                resultSet.getLong("resign_losses"),
                resultSet.getLong("other_losses")
        ));
    }

    public List<ClockAtMoveStats> findClockAtMoves(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames
    ) {
        String sql = """
                WITH filtered_games AS (
                    SELECT g.id, g.time_class
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                ),
                time_class_games AS (
                    SELECT time_class, COUNT(*) AS games
                    FROM filtered_games
                    GROUP BY time_class
                ),
                player_moves AS (
                    SELECT fg.time_class,
                           m.game_id,
                           m.clock_remaining_ms,
                           ROW_NUMBER() OVER (
                               PARTITION BY m.game_id
                               ORDER BY m.ply
                           ) AS player_move_number
                    FROM move m
                    JOIN filtered_games fg ON fg.id = m.game_id
                    WHERE m.is_player_move
                )
                SELECT tcg.time_class,
                       requested.move_number,
                       tcg.games AS games_in_time_class,
                       COUNT(pm.game_id) FILTER (
                           WHERE pm.clock_remaining_ms IS NOT NULL
                       ) AS games_with_clock,
                       AVG(pm.clock_remaining_ms)::float8 AS average_clock_ms
                FROM time_class_games tcg
                CROSS JOIN (VALUES (10), (20), (30)) AS requested(move_number)
                LEFT JOIN player_moves pm
                       ON pm.time_class = tcg.time_class
                      AND pm.player_move_number = requested.move_number
                WHERE tcg.games >= :minGames
                GROUP BY tcg.time_class, requested.move_number, tcg.games
                ORDER BY tcg.time_class, requested.move_number
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new ClockAtMoveStats(
                resultSet.getString("time_class"),
                resultSet.getInt("move_number"),
                resultSet.getLong("games_in_time_class"),
                resultSet.getLong("games_with_clock"),
                resultSet.getObject("average_clock_ms", Double.class)
        ));
    }

    public List<LossClockStats> findLossClocks(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames
    ) {
        String sql = """
                WITH filtered_games AS (
                    SELECT g.id, g.time_class, g.result
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                ),
                time_class_games AS (
                    SELECT time_class, COUNT(*) AS games
                    FROM filtered_games
                    GROUP BY time_class
                ),
                losses AS (
                    SELECT id, time_class
                    FROM filtered_games
                    WHERE result = 'loss'
                ),
                last_player_move AS (
                    SELECT l.id AS game_id,
                           l.time_class,
                           last_move.clock_remaining_ms
                    FROM losses l
                    LEFT JOIN LATERAL (
                        SELECT m.clock_remaining_ms
                        FROM move m
                        WHERE m.game_id = l.id
                          AND m.is_player_move
                        ORDER BY m.ply DESC
                        LIMIT 1
                    ) last_move ON TRUE
                )
                SELECT lpm.time_class,
                       COUNT(*) AS total_losses,
                       COUNT(*) FILTER (
                           WHERE lpm.clock_remaining_ms IS NOT NULL
                       ) AS losses_with_clock,
                       COUNT(*) FILTER (
                           WHERE lpm.clock_remaining_ms > 30000
                       ) AS over_30s,
                       COUNT(*) FILTER (
                           WHERE lpm.clock_remaining_ms >= 10000
                             AND lpm.clock_remaining_ms <= 30000
                       ) AS between_10_and_30s,
                       COUNT(*) FILTER (
                           WHERE lpm.clock_remaining_ms < 10000
                       ) AS under_10s
                FROM last_player_move lpm
                JOIN time_class_games tcg USING (time_class)
                WHERE tcg.games >= :minGames
                GROUP BY lpm.time_class
                ORDER BY lpm.time_class
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new LossClockStats(
                resultSet.getString("time_class"),
                resultSet.getLong("total_losses"),
                resultSet.getLong("losses_with_clock"),
                resultSet.getLong("over_30s"),
                resultSet.getLong("between_10_and_30s"),
                resultSet.getLong("under_10s")
        ));
    }

    private static MapSqlParameterSource filterParameters(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass
    ) {
        return new MapSqlParameterSource()
                .addValue("playerId", playerId)
                .addValue("from", from, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("timeClass", timeClass);
    }
}
