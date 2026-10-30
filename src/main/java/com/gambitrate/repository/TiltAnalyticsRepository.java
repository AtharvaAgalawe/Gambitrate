package com.gambitrate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class TiltAnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TiltAnalyticsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TiltOverallStats findOverall(
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
                (resultSet, rowNumber) -> new TiltOverallStats(
                        resultSet.getLong("games"),
                        resultSet.getLong("wins"),
                        resultSet.getLong("draws"),
                        resultSet.getLong("losses")
                ));
    }

    public List<ResultComparisonStats> findResultComparisons(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            long sessionGapSeconds
    ) {
        String sql = """
                WITH filtered AS (
                    SELECT g.id,
                           g.played_at,
                           g.time_class,
                           g.result,
                           g.player_rating
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                ),
                session_flags AS (
                    SELECT f.*,
                           CASE
                               WHEN LAG(f.played_at) OVER (
                                        ORDER BY f.played_at, f.id
                                    ) IS NULL
                                   OR f.played_at - LAG(f.played_at) OVER (
                                        ORDER BY f.played_at, f.id
                                    ) >= (:sessionGapSeconds * INTERVAL '1 second')
                               THEN 1
                               ELSE 0
                           END AS starts_session
                    FROM filtered f
                ),
                sessioned AS (
                    SELECT sf.*,
                           SUM(sf.starts_session) OVER (
                               ORDER BY sf.played_at, sf.id
                           ) AS session_id
                    FROM session_flags sf
                ),
                with_previous AS (
                    SELECT s.*,
                           LAG(s.result) OVER (
                               PARTITION BY s.session_id
                               ORDER BY s.played_at, s.id
                           ) AS previous_result,
                           LAG(s.player_rating) OVER (
                               PARTITION BY s.session_id
                               ORDER BY s.played_at, s.id
                           ) AS previous_rating,
                           LAG(s.time_class) OVER (
                               PARTITION BY s.session_id
                               ORDER BY s.played_at, s.id
                           ) AS previous_time_class
                    FROM sessioned s
                )
                SELECT previous_result,
                       COUNT(*) AS games,
                       SUM(CASE WHEN result = 'win' THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN result = 'draw' THEN 1 ELSE 0 END) AS draws,
                       SUM(CASE WHEN result = 'loss' THEN 1 ELSE 0 END) AS losses,
                       AVG(CASE WHEN result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                           AS win_rate,
                       AVG(CASE
                               WHEN previous_time_class = time_class
                                   AND previous_rating IS NOT NULL
                                   AND player_rating IS NOT NULL
                               THEN player_rating - previous_rating
                           END)::float8 AS average_rating_change,
                       COUNT(*) FILTER (
                           WHERE previous_time_class = time_class
                             AND previous_rating IS NOT NULL
                             AND player_rating IS NOT NULL
                       ) AS rating_change_samples
                FROM with_previous
                WHERE previous_result IN ('win', 'loss')
                GROUP BY previous_result
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("sessionGapSeconds", sessionGapSeconds);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) ->
                new ResultComparisonStats(
                        resultSet.getString("previous_result"),
                        resultSet.getLong("games"),
                        resultSet.getLong("wins"),
                        resultSet.getLong("draws"),
                        resultSet.getLong("losses"),
                        resultSet.getDouble("win_rate"),
                        resultSet.getObject("average_rating_change", Double.class),
                        resultSet.getLong("rating_change_samples")
                ));
    }

    public List<SessionIndexStats> findSessionIndexes(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            long sessionGapSeconds,
            int minGames
    ) {
        String sql = """
                WITH filtered AS (
                    SELECT g.id,
                           g.played_at,
                           g.time_class,
                           g.result
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                ),
                session_flags AS (
                    SELECT f.*,
                           CASE
                               WHEN LAG(f.played_at) OVER (
                                        ORDER BY f.played_at, f.id
                                    ) IS NULL
                                   OR f.played_at - LAG(f.played_at) OVER (
                                        ORDER BY f.played_at, f.id
                                    ) >= (:sessionGapSeconds * INTERVAL '1 second')
                               THEN 1
                               ELSE 0
                           END AS starts_session
                    FROM filtered f
                ),
                sessioned AS (
                    SELECT sf.*,
                           SUM(sf.starts_session) OVER (
                               ORDER BY sf.played_at, sf.id
                           ) AS session_id
                    FROM session_flags sf
                ),
                indexed AS (
                    SELECT s.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY s.session_id
                               ORDER BY s.played_at, s.id
                           ) AS game_index
                    FROM sessioned s
                )
                SELECT game_index,
                       COUNT(*) AS games,
                       SUM(CASE WHEN result = 'win' THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN result = 'draw' THEN 1 ELSE 0 END) AS draws,
                       SUM(CASE WHEN result = 'loss' THEN 1 ELSE 0 END) AS losses,
                       AVG(CASE WHEN result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                           AS win_rate
                FROM indexed
                GROUP BY game_index
                HAVING COUNT(*) >= :minGames
                ORDER BY game_index
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("sessionGapSeconds", sessionGapSeconds)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new SessionIndexStats(
                resultSet.getInt("game_index"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate")
        ));
    }

    public List<HourStats> findHourly(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            String timezone,
            int minGames
    ) {
        String sql = """
                SELECT EXTRACT(HOUR FROM g.played_at AT TIME ZONE :timezone)::int
                           AS hour_of_day,
                       COUNT(*) AS games,
                       SUM(CASE WHEN g.result = 'win' THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN g.result = 'draw' THEN 1 ELSE 0 END) AS draws,
                       SUM(CASE WHEN g.result = 'loss' THEN 1 ELSE 0 END) AS losses,
                       AVG(CASE WHEN g.result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                           AS win_rate
                FROM game g
                WHERE g.player_id = :playerId
                  AND (CAST(:from AS timestamptz) IS NULL
                           OR g.played_at >= CAST(:from AS timestamptz))
                  AND (CAST(:toExclusive AS timestamptz) IS NULL
                           OR g.played_at < CAST(:toExclusive AS timestamptz))
                  AND (CAST(:timeClass AS varchar) IS NULL
                           OR g.time_class = CAST(:timeClass AS varchar))
                GROUP BY 1
                HAVING COUNT(*) >= :minGames
                ORDER BY 1
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("timezone", timezone)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new HourStats(
                resultSet.getInt("hour_of_day"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate")
        ));
    }

    public List<DayStats> findDaily(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            String timezone,
            int minGames
    ) {
        String sql = """
                SELECT EXTRACT(ISODOW FROM g.played_at AT TIME ZONE :timezone)::int
                           AS iso_day_of_week,
                       COUNT(*) AS games,
                       SUM(CASE WHEN g.result = 'win' THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN g.result = 'draw' THEN 1 ELSE 0 END) AS draws,
                       SUM(CASE WHEN g.result = 'loss' THEN 1 ELSE 0 END) AS losses,
                       AVG(CASE WHEN g.result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                           AS win_rate
                FROM game g
                WHERE g.player_id = :playerId
                  AND (CAST(:from AS timestamptz) IS NULL
                           OR g.played_at >= CAST(:from AS timestamptz))
                  AND (CAST(:toExclusive AS timestamptz) IS NULL
                           OR g.played_at < CAST(:toExclusive AS timestamptz))
                  AND (CAST(:timeClass AS varchar) IS NULL
                           OR g.time_class = CAST(:timeClass AS varchar))
                GROUP BY 1
                HAVING COUNT(*) >= :minGames
                ORDER BY 1
                """;

        MapSqlParameterSource parameters = filterParameters(playerId, from, toExclusive, timeClass)
                .addValue("timezone", timezone)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new DayStats(
                resultSet.getInt("iso_day_of_week"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate")
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
