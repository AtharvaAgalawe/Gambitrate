package com.gambitrate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class TrendAnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TrendAnalyticsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MonthlyTrendStats> findMonthly(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames
    ) {
        String sql = """
                WITH monthly AS (
                    SELECT date_trunc(
                               'month',
                               g.played_at AT TIME ZONE 'UTC'
                           ) AS month_start,
                           to_char(
                               date_trunc('month', g.played_at AT TIME ZONE 'UTC'),
                               'YYYY-MM'
                           ) AS month,
                           COUNT(*) AS games,
                           SUM(CASE WHEN g.result = 'win' THEN 1 ELSE 0 END) AS wins,
                           SUM(CASE WHEN g.result = 'draw' THEN 1 ELSE 0 END) AS draws,
                           SUM(CASE WHEN g.result = 'loss' THEN 1 ELSE 0 END) AS losses,
                           AVG(CASE WHEN g.result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                               AS win_rate,
                           AVG(g.player_rating)::float8 AS average_rating,
                           COUNT(g.player_rating) AS rating_games,
                           AVG(g.player_accuracy)::float8 AS average_accuracy,
                           COUNT(g.player_accuracy) AS accuracy_games
                    FROM game g
                    WHERE g.player_id = :playerId
                      AND (CAST(:from AS timestamptz) IS NULL
                               OR g.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR g.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR g.time_class = CAST(:timeClass AS varchar))
                    GROUP BY month_start, month
                )
                SELECT month,
                       games,
                       wins,
                       draws,
                       losses,
                       win_rate,
                       average_rating,
                       rating_games,
                       average_accuracy,
                       accuracy_games
                FROM monthly
                WHERE games >= :minGames
                ORDER BY month_start
                """;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("playerId", playerId)
                .addValue("from", from, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("timeClass", timeClass)
                .addValue("minGames", minGames);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new MonthlyTrendStats(
                resultSet.getString("month"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate"),
                resultSet.getObject("average_rating", Double.class),
                resultSet.getLong("rating_games"),
                resultSet.getObject("average_accuracy", Double.class),
                resultSet.getLong("accuracy_games")
        ));
    }
}
