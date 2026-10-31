# Phase 4 Analytics Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add `/time-controls`, `/tilt`, `/trends`, and `/summary` with SQL-first analytics, guarded verdicts, exact-number integration tests, Swagger docs, and updated project documentation.

**Architecture:** Each endpoint follows controller -> service -> repository. PostgreSQL performs filtering, windowing, timezone conversion, and aggregation. Java only applies the small, explicit verdict/ranking rules and serializes DTOs. Shared filter validation and statistics live in `service/analytics`; each analytics family has its own repository and result records.

**Tech Stack:** Java 21, Spring Boot 3.5, NamedParameterJdbcTemplate, PostgreSQL 16, Flyway, JUnit 5, Testcontainers PostgreSQL, TestRestTemplate, springdoc-openapi.

**Spec:** `prd.md` sections 5.4.2-5.4.5 and the Phase 4 scope in `AGENTS.md`.

## Global Constraints

- Never claim clock data proves move quality. Clock-at-move-N is time remaining after the player's Nth move.
- Accuracy aggregates always include coverage.
- Every verdict/leak has a sample-size guard and exposes the sample size.
- Analytics filters are `from`/`to` inclusive UTC dates, optional `timeClass`, and `minGames` (default 5).
- `tz` is an IANA zone ID validated with `ZoneId`; invalid input is a 400.
- No live chess.com calls from tests.
- Run `./mvnw verify` before declaring completion.
- New schema changes are Flyway migrations only.

## Design Calls

1. **Game-length buckets:** full moves `1-19`, `20-39`, `40-59`, `60-79`, `80-99`, `100+`. Full moves = `ceil(plies / 2)`; plies are the SAN token count from `moves_san`.
2. **Clock at move N:** `clock_remaining_ms` after the player's Nth own move. It is found with `ROW_NUMBER() OVER (PARTITION BY game_id ORDER BY ply)` over `move WHERE is_player_move`. Coverage is games with a clock at N divided by games in that time class.
3. **Loss clock buckets:** final player clock is the clock after the player's last recorded own move. Buckets are `> 30,000 ms`, `10,000-30,000 ms` inclusive, and `< 10,000 ms`. Shares use losses with clock data; coverage against all losses is explicit.
4. **Tilt session:** a gap of at least the configured `30m` starts a new session. Filters are applied before session windows. Session index and previous result are computed with `LAG`/`ROW_NUMBER`.
5. **Tilt score:** `(postWinWinRate - postLossWinRate) * 100`, positive means worse after a loss. A verdict requires at least `minGames` on both sides and a two-proportion 95% z-test. Average rating change only uses consecutive games in the same time class; coverage is returned.
6. **Timezone:** hour/day use PostgreSQL `played_at AT TIME ZONE :tz`. Date filters remain UTC. `tz` is validated with `ZoneId`; invalid values are a 400. IANA region IDs, UTC, and PostgreSQL-supported fixed offsets are accepted.
7. **minGames:** grouped arrays hide groups below the threshold. Tilt still returns post-loss/post-win counts, but withholds the score/verdict when either side is too small. Summary only considers guarded candidates.
8. **Trends:** months are UTC calendar months; `averageRating` is the mean of non-null `player_rating`; accuracy is the mean of non-null `player_accuracy` with month and overall coverage. `minGames` hides small months.
9. **Summary impact:** `(baselineWinRate - groupWinRate) * games * 10`. Opening baseline is the same-color baseline; time class, game length, hour, and day use the overall filtered baseline; post-loss uses the post-win baseline. Only positive, 95%-guarded candidates are ranked. Trends is descriptive and does not manufacture a leak candidate. The value is always labelled an estimate.
10. **Indexes:** add `(player_id, time_class, played_at)` and `(game_id, is_player_move, ply)`.
11. **Extra parameter:** `/summary` accepts optional `tz` because its hour/day candidates need the same timezone as `/tilt`; it defaults to UTC.

## Review Focus

- A game never reaching the requested move number must lower coverage, not silently count as a zero clock.
- A session boundary at exactly the configured gap must start a new session, not continue the old one.
- A timezone that is valid as a Java `ZoneId` but unsupported by PostgreSQL must not become a 500.
- Accuracy must never appear without the number of contributing games and coverage.
- `/summary` must not rank a small-sample or non-significant group even when its raw impact looks large.

---

### Task 1: Analytics foundations, config, indexes

**Files:**
- Create: `src/main/java/com/gambitrate/config/AnalyticsProperties.java`
- Create: `src/main/java/com/gambitrate/service/analytics/AnalyticsFilter.java`
- Create: `src/main/java/com/gambitrate/service/analytics/AnalyticsMath.java`
- Create: `src/main/java/com/gambitrate/service/analytics/AnalyticsFiltersResponse.java`
- Create: `src/main/resources/db/migration/V3__analytics_indexes.sql`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- `AnalyticsFilter(LocalDate from, LocalDate to, String timeClass, int minGames)` with UTC half-open conversion and validation.
- `AnalyticsMath.estimatedImpact`, `significantlyBelow`, `twoProportionSignificant`, `round`, `percent`.
- `AnalyticsProperties(Duration tiltSessionGap)`.
- `TILT_SESSION_GAP` default `30m`.

- [x] Add config, shared filter/math, and indexes.
- [x] Add a focused unit test for filter validation and math boundaries.
- [x] Run `./mvnw test`.

### Task 2: Time controls

**Files:**
- Create: `src/main/java/com/gambitrate/repository/TimeControlAnalyticsRepository.java`
- Create: `src/main/java/com/gambitrate/service/timecontrols/TimeControlsService.java`
- Create: `src/main/java/com/gambitrate/service/timecontrols/TimeControlsAnalysis.java`
- Create: `src/main/java/com/gambitrate/api/TimeControlsController.java`
- Create: `src/main/java/com/gambitrate/api/dto/TimeControlsResponse.java`
- Test: `src/test/java/com/gambitrate/integration/Phase4AnalyticsIT.java`

**Interfaces:**
- `TimeControlsService.analyze(String username, AnalyticsFilter filter)`.
- Response includes overall, byTimeClass, byGameLength, lossTerminations, clockAtMoves, lossClocks, notes, message.

- [x] Seed 10 rapid games (5 short, 5 long) and 5 blitz short games; assert exact win rates, bucket win rates, termination shares, move-10/20/30 averages, coverage, and loss-clock shares.
- [x] Assert minGames, timeClass, date filters, empty result message, 400 reversed dates, and 404 unsynced player.
- [x] Run the integration test.

### Task 3: Tilt

**Files:**
- Create: `src/main/java/com/gambitrate/repository/TiltAnalyticsRepository.java`
- Create: `src/main/java/com/gambitrate/service/tilt/TiltService.java`
- Create: `src/main/java/com/gambitrate/service/tilt/TiltAnalysis.java`
- Create: `src/main/java/com/gambitrate/service/tilt/TiltScoreCalculator.java`
- Create: `src/main/java/com/gambitrate/api/TiltController.java`
- Create: `src/main/java/com/gambitrate/api/dto/TiltResponse.java`
- Test: `src/test/java/com/gambitrate/unit/TiltScoreCalculatorTest.java`
- Test: `src/test/java/com/gambitrate/integration/Phase4AnalyticsIT.java`

**Interfaces:**
- `TiltService.analyze(String username, AnalyticsFilter filter, String tz)`.
- Response includes postLoss/postWin, tiltScore, verdict, bySessionIndex, byHour, byDay, overall, sessionGapMinutes, timezone.

- [x] Seed two sessions with exact post-loss/post-win rates and rating changes; assert score and verdict.
- [x] Assert session indexes, New York hour/day conversion, timezone invalid 400, and tiny-sample guard.
- [x] Run unit and integration tests.

### Task 4: Trends

**Files:**
- Create: `src/main/java/com/gambitrate/repository/TrendAnalyticsRepository.java`
- Create: `src/main/java/com/gambitrate/service/trends/TrendsService.java`
- Create: `src/main/java/com/gambitrate/service/trends/TrendsAnalysis.java`
- Create: `src/main/java/com/gambitrate/api/TrendsController.java`
- Create: `src/main/java/com/gambitrate/api/dto/TrendsResponse.java`
- Test: `src/test/java/com/gambitrate/integration/Phase4AnalyticsIT.java`

**Interfaces:**
- `TrendsService.analyze(String username, AnalyticsFilter filter)`.
- Response includes monthly rows, average rating/ratingGames, accuracy average/accuracyGames/coverage, overall accuracy coverage, message.

- [x] Seed three months, hide a small month at default minGames, assert monthly numbers, accuracy coverage, and chronological order.
- [x] Assert `minGames=4` reveals the small month.
- [x] Run the integration test.

### Task 5: Summary

**Files:**
- Create: `src/main/java/com/gambitrate/service/summary/SummaryService.java`
- Create: `src/main/java/com/gambitrate/service/summary/SummaryAnalysis.java`
- Create: `src/main/java/com/gambitrate/api/SummaryController.java`
- Create: `src/main/java/com/gambitrate/api/dto/SummaryResponse.java`
- Test: `src/test/java/com/gambitrate/integration/Phase4AnalyticsIT.java`

**Interfaces:**
- `SummaryService.analyze(String username, AnalyticsFilter filter, String tz)`.
- Response includes top 3 ranked leaks, each with area, label, filters, sample size, evidence, impact, explanation, and an estimate disclaimer.

- [x] Seed 30 games producing a post-loss tilt, an opening leak, a time-class leak, and a below-cutoff game-length impact; assert the top 3 order and exact impacts.
- [x] Assert every item exposes filters, sample size, baseline, and supporting numbers.
- [x] Run the integration test.

### Task 6: Documentation and final verification

**Files:**
- Modify: `README.md`
- Modify: `INTERVIEW_QUESTIONS.md`
- Modify: `docs/superpowers/plans/2026-10-30-phase-4-analytics.md`

- [x] Update status, endpoint examples, configuration, bucket/session/formula definitions, coverage rules, indexes, and test inventory.
- [x] Add Phase 4 interview questions with concrete numbers.
- [x] Run `./mvnw verify`; all unit and integration tests must pass.
- [x] Commit in small, imperative commits and verify the deployed branch only after a separate deployment check.
