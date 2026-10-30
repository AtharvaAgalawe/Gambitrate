package com.gambitrate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class OpeningAnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OpeningAnalyticsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OpeningGroupStats> findOpeningGroups(
            long playerId,
            OffsetDateTime from,
            OffsetDateTime toExclusive,
            String timeClass,
            int minGames,
            int linePlies
    ) {
        String sql = """
                WITH rated AS (
                    SELECT g.id,
                           g.color,
                           g.eco_code,
                           g.opening_name,
                           g.player_rating,
                           g.result,
                           g.played_at,
                           g.time_class,
                           g.moves_san,
                           LAG(g.player_rating) OVER (
                               PARTITION BY g.player_id, g.time_class
                               ORDER BY g.played_at, g.id
                           ) AS previous_rating
                    FROM game g
                    WHERE g.player_id = :playerId
                ),
                filtered AS (
                    SELECT r.*,
                           CASE
                               WHEN :linePlies > 0 THEN (
                                   SELECT string_agg(token, ' ' ORDER BY ord)
                                   FROM unnest(
                                       regexp_split_to_array(r.moves_san, '\\s+')
                                   ) WITH ORDINALITY AS token_row(token, ord)
                                   WHERE ord <= :linePlies
                               )
                               ELSE NULL
                           END AS line
                    FROM rated r
                    WHERE (CAST(:from AS timestamptz) IS NULL
                               OR r.played_at >= CAST(:from AS timestamptz))
                      AND (CAST(:toExclusive AS timestamptz) IS NULL
                               OR r.played_at < CAST(:toExclusive AS timestamptz))
                      AND (CAST(:timeClass AS varchar) IS NULL
                               OR r.time_class = CAST(:timeClass AS varchar))
                ),
                baseline AS (
                    SELECT color,
                           AVG(CASE WHEN result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                               AS baseline_win_rate,
                           COUNT(*) AS baseline_games
                    FROM filtered
                    GROUP BY color
                ),
                grouped AS (
                    SELECT f.color,
                           f.eco_code,
                           MAX(f.opening_name) AS opening_name,
                           f.line,
                           COUNT(*) AS games,
                           SUM(CASE WHEN f.result = 'win' THEN 1 ELSE 0 END) AS wins,
                           SUM(CASE WHEN f.result = 'draw' THEN 1 ELSE 0 END) AS draws,
                           SUM(CASE WHEN f.result = 'loss' THEN 1 ELSE 0 END) AS losses,
                           AVG(CASE WHEN f.result = 'win' THEN 1.0 ELSE 0.0 END)::float8
                               AS win_rate,
                           AVG(CASE WHEN f.result = 'draw' THEN 1.0 ELSE 0.0 END)::float8
                               AS draw_rate,
                           AVG(CASE
                                   WHEN f.previous_rating IS NOT NULL
                                       AND f.player_rating IS NOT NULL
                                   THEN f.player_rating - f.previous_rating
                               END)::float8 AS avg_rating_delta,
                           COUNT(CASE
                                   WHEN f.previous_rating IS NOT NULL
                                       AND f.player_rating IS NOT NULL
                                   THEN 1
                               END) AS rating_delta_games
                    FROM filtered f
                    WHERE f.eco_code IS NOT NULL
                    GROUP BY f.color, f.eco_code, f.line
                    HAVING COUNT(*) >= :minGames
                )
                SELECT grouped.color,
                       grouped.eco_code,
                       grouped.opening_name,
                       grouped.line,
                       grouped.games,
                       grouped.wins,
                       grouped.draws,
                       grouped.losses,
                       grouped.win_rate,
                       grouped.draw_rate,
                       grouped.avg_rating_delta,
                       grouped.rating_delta_games,
                       baseline.baseline_win_rate,
                       baseline.baseline_games
                FROM grouped
                JOIN baseline USING (color)
                """;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("playerId", playerId)
                .addValue("from", from, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("timeClass", timeClass)
                .addValue("minGames", minGames)
                .addValue("linePlies", linePlies);

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new OpeningGroupStats(
                resultSet.getString("color"),
                resultSet.getString("eco_code"),
                resultSet.getString("opening_name"),
                resultSet.getString("line"),
                resultSet.getLong("games"),
                resultSet.getLong("wins"),
                resultSet.getLong("draws"),
                resultSet.getLong("losses"),
                resultSet.getDouble("win_rate"),
                resultSet.getDouble("draw_rate"),
                resultSet.getObject("avg_rating_delta", Double.class),
                resultSet.getLong("rating_delta_games"),
                resultSet.getDouble("baseline_win_rate"),
                resultSet.getLong("baseline_games")
        ));
    }
}
