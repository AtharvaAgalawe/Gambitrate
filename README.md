# GambitRate

GambitRate ingests a public chess.com player's games and turns them into opinionated answers about what is hurting their rating. It is **not** a game viewer. Every analytics endpoint ends in numbers and a verdict.

**Current status: Phase 4 complete in code.** The live service is at **[gambitrate.onrender.com](https://gambitrate.onrender.com)**; Phase 4 is only considered live after the deployment metadata and Render service branch are checked post-push. Async sync jobs, Redis caching, incremental sync, ETag revalidation, the distributed outbound gate, and the scheduled sweep are in place. The Phase 4 analytics endpoints are `/time-controls`, `/tilt`, `/trends`, and `/summary`. A single-page dashboard for sync and opening analysis is included ahead of Phase 5.

## Architecture

```text
POST /api/players/{username}/sync
              |
              v
      PlayerSyncController
              |
              v
        SyncJobService --------> sync_job (QUEUED/RUNNING/DONE/FAILED)
              |                         ^
              v                         |
      syncTaskExecutor                 GET .../sync/status
              |
              v
        SyncJobWorker
              |
              v
        PlayerSyncService
       /        |          \
      v         v           v
ChessComClient PgnParser  GameFactory
  |  |              \          /
  |  +-- Redis gate  +-- monthly list
  |  +-- Redis cache         |
  |                          v
  |                 MonthPersistenceService
  |                 (one @Transactional unit)
  v                          |
chess.com                   v
                       PostgreSQL
                player / game / move / sync_job
```

`ChessComClient` checks Redis before calling chess.com: immutable past months are cache hits, while the current month and archive list revalidate with `If-None-Match`. Redis also holds a short-lived host lock so only one chess.com request is in flight across app instances. The scheduler enqueues the same persistent jobs for tracked players.

Analytics take a separate path:

```text
GET /api/players/{username}/openings
                 |
                 v
        OpeningsController
                 |
                 v
          OpeningsService
          (verdict + Elo heuristic)
                 |
                 v
     OpeningAnalyticsRepository
     (NamedParameterJdbcTemplate)
                 |
                 v
            PostgreSQL
     GROUP BY color, eco_code
     + LAG(player_rating) for deltas
```

Analytics do not load a player's games into Java. PostgreSQL computes the groups and aggregates; Java only applies the verdict and sorting rules to the small result set.

Phase 4 adds three more SQL-first paths:

```text
GET /time-controls -> TimeControlsService -> TimeControlAnalyticsRepository
GET /tilt          -> TiltService          -> TiltAnalyticsRepository
GET /trends        -> TrendsService        -> TrendAnalyticsRepository
GET /summary       -> SummaryService       -> guarded openings + time-controls + tilt results
```

`/tilt` uses window functions for session number, previous result, rating change, and
session game index. `/trends` groups UTC calendar months in PostgreSQL. `/summary`
does not load game history; it ranks the small, already-guarded result sets.

## Stack

- Java 21, Spring Boot 3.5, Maven
- Spring Web, Spring Data JPA, Bean Validation
- PostgreSQL 16, Flyway
- Redis 7 via Spring Cache, Lettuce, and a distributed outbound host gate
- chesslib 1.3.7 for PGN parsing and move validation
- springdoc-openapi / Swagger UI
- JUnit 5, Testcontainers PostgreSQL + Redis, WireMock
- Docker Compose (app, PostgreSQL, Redis)
- Render Blueprint (`render.yaml`)

## Run locally

Prerequisites: Java 21+, Docker Desktop.

```bash
# Start dependencies only
docker compose up -d postgres redis

# Run the app on the host
./mvnw spring-boot:run
```

Or run everything in containers:

```bash
docker compose up --build
```

- Dashboard: `http://localhost:8080/`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw`.

## Sync a player

```bash
curl -i -X POST http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/sync
```

The request returns `202 Accepted` with a persisted job and a `Location` header:

```json
{
  "id": 42,
  "username": "exampleuser",
  "status": "QUEUED",
  "monthsTotal": 0,
  "monthsDone": 0,
  "createdAt": "2026-10-30T12:00:00Z",
  "startedAt": null,
  "finishedAt": null,
  "error": null
}
```

Poll the status:

```bash
curl http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/sync/status
```

```json
{
  "id": 42,
  "username": "exampleuser",
  "status": "RUNNING",
  "monthsTotal": 18,
  "monthsDone": 7,
  "createdAt": "2026-10-30T12:00:00Z",
  "startedAt": "2026-10-30T12:00:01Z",
  "finishedAt": null,
  "error": null
}
```

Statuses are `QUEUED`, `RUNNING`, `DONE`, and `FAILED`. Only one job per player can be queued or running; a repeated request returns the active job. Jobs older than the stale threshold are marked failed so a crashed process cannot block future syncs forever.

The first time a player is requested, the POST validates the chess.com profile synchronously so an unknown username still returns `404`. All ingestion after that point is asynchronous.

Run the same command twice. The second job imports zero new games and leaves the row counts unchanged.

Inspect the persisted data:

```bash
docker compose exec postgres psql -U gambitrate -d gambitrate -c \
  "SELECT p.username, count(DISTINCT g.id) AS games, count(m.id) AS moves
   FROM player p
   LEFT JOIN game g ON g.player_id = p.id
   LEFT JOIN move m ON m.game_id = g.id
   GROUP BY p.username;"
```

```bash
docker compose exec postgres psql -U gambitrate -d gambitrate -c \
  "SELECT g.chesscom_id, g.color, g.result, g.termination, g.eco_code,
          g.opening_name, m.ply, m.san, m.clock_remaining_ms
   FROM game g
   JOIN move m ON m.game_id = g.id
   ORDER BY g.played_at DESC, m.ply
   LIMIT 20;"
```

## Find your rating-costing openings

```bash
curl "http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/openings?minGames=5"
```

Optional filters:

| Parameter | Meaning |
|---|---|
| `from`, `to` | Inclusive UTC calendar dates, `YYYY-MM-DD` |
| `timeClass` | `bullet`, `blitz`, `rapid`, or `daily` |
| `minGames` | Hide smaller groups; default `5` |
| `groupBy` | `eco` (default) or `line` |
| `firstMoves` | Full moves used by `groupBy=line`; default `6`, max `20` |

Example response, reduced to one group:

```json
{
  "username": "exampleuser",
  "filters": {
    "from": null,
    "to": null,
    "timeClass": "rapid",
    "minGames": 5,
    "groupBy": "eco",
    "firstMoves": 6
  },
  "openings": [
    {
      "color": "white",
      "ecoCode": "B01",
      "openingName": "Scandinavian Defense",
      "line": null,
      "games": 94,
      "wins": 47,
      "draws": 8,
      "losses": 39,
      "winRate": 0.5,
      "drawRate": 0.0851,
      "avgRatingDelta": -1.2366,
      "ratingDeltaGames": 93,
      "baselineWinRate": 0.563,
      "baselineGames": 1280,
      "estimatedRatingCost": 59.5,
      "verdict": "fine",
      "verdictReason": "You win 50.0% of these games as White. You usually win 56.3%. Over 94 games, that is within normal game-to-game variation."
    }
  ],
  "message": null
}
```

- `winRate` and `drawRate` are fractions from `0.0` to `1.0`.
- `avgRatingDelta` is the real change between consecutive games in the same time class; `ratingDeltaGames` shows how many games had a measurable delta.
- `estimatedRatingCost = (baselineWinRate - winRate) × games × 10`. Positive means estimated Elo cost, negative means estimated gain. It is a heuristic for ordering, not a replacement for the verdict.
- `verdict` is `leak`, `fine`, or `strength`. A non-`fine` verdict needs at least 5 games and a two-sided 95% z-test against the same color's filtered baseline.
- Groups below `minGames` are hidden. If no groups qualify, the endpoint returns `200` with an empty `openings` array and an explanatory `message`.
- Results are sorted by `estimatedRatingCost` descending. The verdict, not the cost alone, decides whether a group is actionable.

## Time controls and clock coverage

```bash
curl "http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/time-controls?timeClass=rapid&minGames=5"
```

Reduced response:

```json
{
  "username": "exampleuser",
  "filters": {
    "from": null,
    "to": null,
    "timeClass": "rapid",
    "minGames": 5,
    "timezone": null
  },
  "overall": {
    "games": 120,
    "wins": 55,
    "draws": 20,
    "losses": 45,
    "winRate": 0.4583
  },
  "byTimeClass": [
    {
      "timeClass": "rapid",
      "games": 120,
      "wins": 55,
      "draws": 20,
      "losses": 45,
      "winRate": 0.4583
    }
  ],
  "byGameLength": [
    {
      "bucket": "20-39",
      "label": "20-39 moves",
      "games": 35,
      "wins": 14,
      "draws": 6,
      "losses": 15,
      "winRate": 0.4
    }
  ],
  "lossTerminations": [
    {
      "timeClass": "rapid",
      "totalGames": 120,
      "totalLosses": 45,
      "timeout": { "losses": 10, "share": 0.2222 },
      "checkmate": { "losses": 15, "share": 0.3333 },
      "resign": { "losses": 18, "share": 0.4 },
      "other": { "losses": 2, "share": 0.0444 }
    }
  ],
  "clockAtMoves": [
    {
      "timeClass": "rapid",
      "moveNumber": 10,
      "gamesInTimeClass": 120,
      "gamesWithClock": 100,
      "coverage": 0.8333,
      "averageClockRemainingMs": 345000,
      "averageClockRemainingSeconds": 345.0
    }
  ],
  "lossClocks": [
    {
      "timeClass": "rapid",
      "totalLosses": 45,
      "lossesWithClock": 40,
      "coverage": 0.8889,
      "over30s": 12,
      "between10And30s": 8,
      "under10s": 20,
      "shareOver30s": 0.3,
      "shareBetween10And30s": 0.2,
      "shareUnder10s": 0.5
    }
  ],
  "notes": [
    "Game-length buckets use full moves: 1-19, 20-39, 40-59, 60-79, 80-99, and 100+.",
    "Clock data is a time measurement, not evidence that a move was a blunder."
  ],
  "message": null
}
```

- Game-length buckets are full moves: `1-19`, `20-39`, `40-59`, `60-79`, `80-99`, `100+`.
- Clock at move N is the player's remaining clock **after their Nth own move**, not a move-quality claim.
- Clock coverage is `gamesWithClock / gamesInTimeClass`. A game that never reaches move N lowers coverage instead of contributing a zero.
- Loss-clock shares use losses with a recorded final player clock. `coverage` shows how many losses had that data.
- Termination shares are fractions of all losses in that time class.
- Groups below `minGames` are hidden.

## Tilt and session performance

```bash
curl "http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/tilt?tz=America/New_York&minGames=5"
```

Reduced response:

```json
{
  "username": "exampleuser",
  "filters": {
    "from": null,
    "to": null,
    "timeClass": null,
    "minGames": 5,
    "timezone": "America/New_York"
  },
  "sessionGapMinutes": 30,
  "overall": {
    "games": 300,
    "wins": 150,
    "draws": 30,
    "losses": 120,
    "winRate": 0.5
  },
  "postLoss": {
    "previousResult": "loss",
    "games": 140,
    "wins": 53,
    "draws": 5,
    "losses": 82,
    "winRate": 0.3786,
    "averageRatingChange": -3.2,
    "ratingChangeSamples": 138
  },
  "postWin": {
    "previousResult": "win",
    "games": 138,
    "wins": 72,
    "draws": 12,
    "losses": 54,
    "winRate": 0.5217,
    "averageRatingChange": 3.1,
    "ratingChangeSamples": 136
  },
  "tiltScore": 14.31,
  "sampleSufficient": true,
  "significant": true,
  "verdict": "After a loss, winrate drops to 37.9% from 52.2% after a win, a 14.3-point tilt signal over 140 post-loss and 138 post-win games.",
  "bySessionIndex": [
    {
      "gameIndex": 1,
      "games": 150,
      "wins": 80,
      "draws": 10,
      "losses": 60,
      "winRate": 0.5333
    }
  ],
  "byHour": [
    {
      "hourOfDay": 21,
      "games": 40,
      "wins": 16,
      "draws": 4,
      "losses": 20,
      "winRate": 0.4
    }
  ],
  "byDay": [
    {
      "isoDayOfWeek": 1,
      "day": "Monday",
      "games": 50,
      "wins": 20,
      "draws": 5,
      "losses": 25,
      "winRate": 0.4
    }
  ],
  "notes": [
    "A session is consecutive games with a gap under 30 minutes; the gap is configurable.",
    "Tilt score = (post-win winrate - post-loss winrate) x 100."
  ],
  "message": null
}
```

- A new session starts at a gap of **30 minutes or more**. Set `TILT_SESSION_GAP` to change it.
- `tiltScore` is percentage points; positive means worse after a loss.
- A tilt verdict requires at least `minGames` after wins and after losses, plus a two-proportion 95% z-test.
- `averageRatingChange` is the difference between consecutive pre-game ratings in the same time class; it reflects the previous result's rating effect. `ratingChangeSamples` exposes coverage.
- Hour/day grouping uses PostgreSQL `AT TIME ZONE` with `tz`. Date filters remain UTC.
- `tz` is validated with `ZoneId`; invalid values return `400`.

## Monthly trends and accuracy coverage

```bash
curl "http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/trends?timeClass=rapid&minGames=5"
```

Reduced response:

```json
{
  "username": "exampleuser",
  "filters": {
    "from": null,
    "to": null,
    "timeClass": "rapid",
    "minGames": 5,
    "timezone": null
  },
  "accuracyCoverage": {
    "gamesWithAccuracy": 24,
    "totalGames": 60,
    "coverage": 0.4
  },
  "months": [
    {
      "month": "2024-01",
      "games": 30,
      "wins": 15,
      "draws": 5,
      "losses": 10,
      "winRate": 0.5,
      "averageRating": 1512.5,
      "ratingGames": 30,
      "averageAccuracy": 85.0,
      "accuracyGames": 12,
      "accuracyCoverage": 0.4
    }
  ],
  "notes": [
    "Months are calendar months in UTC.",
    "averageAccuracy is computed only from games where chess.com supplied accuracy."
  ],
  "message": null
}
```

- Months are UTC calendar months and are sorted chronologically.
- `averageRating` is the mean of non-null pre-game `player_rating` values; `ratingGames` exposes coverage.
- Accuracy is sparse. `averageAccuracy` is never presented without `accuracyGames` and `accuracyCoverage`.
- The top-level coverage is computed over the months that pass `minGames`.
- Months below `minGames` are hidden.

## Summary: the top three leaks

```bash
curl "http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/summary?tz=America/New_York"
```

Reduced response:

```json
{
  "username": "exampleuser",
  "filters": {
    "from": null,
    "to": null,
    "timeClass": null,
    "minGames": 5,
    "timezone": "America/New_York"
  },
  "leaks": [
    {
      "rank": 1,
      "area": "postLoss",
      "label": "After a loss",
      "filters": {
        "from": null,
        "to": null,
        "timeClass": null,
        "minGames": 5,
        "timezone": "America/New_York"
      },
      "sampleSize": 14,
      "estimatedRatingImpact": 98.89,
      "evidence": {
        "color": null,
        "ecoCode": null,
        "openingName": null,
        "timeClass": null,
        "bucket": null,
        "hourOfDay": null,
        "day": null,
        "sampleSize": 14,
        "wins": 1,
        "draws": 0,
        "losses": 13,
        "winRate": 0.0714,
        "baselineWinRate": 0.7778,
        "baselineSampleSize": 9,
        "averageRatingChange": -5.0,
        "ratingChangeSamples": 14
      },
      "explanation": "After a loss, winrate is 7.1% vs 77.8% after a win over 14 post-loss and 9 post-win games; the two-proportion guard is met."
    }
  ],
  "notes": [
    "Estimated rating impact = (baselineWinRate - groupWinRate) x games x 10. Positive means estimated Elo cost. This is a heuristic, not a chess.com rating calculation.",
    "Trends is descriptive and does not produce a leak candidate."
  ],
  "message": null
}
```

- Candidates come from statistically guarded openings, time classes, game-length buckets, post-loss tilt, and hour/day performance.
- Impact is `(baselineWinRate - groupWinRate) × games × 10`. Opening baseline is the same-color winrate; time-class, game-length, hour, and day baselines are the overall filtered winrate; post-loss uses the post-win winrate.
- Only positive, `minGames`-qualified, 95%-guarded candidates are ranked.
- Every item repeats the request filters and exposes its sample size, baseline, and supporting numbers.
- The estimate is a ranking heuristic, not a chess.com/Glicko rating calculation. `/trends` is descriptive and does not manufacture a leak.

## Dashboard

The root URL serves a static one-page dashboard for the features that exist today:

- Sync a chess.com username with a live job status and month progress.
- Show summary metrics, the biggest rating loss, weak-opening count, and games analyzed.
- Rank openings with signed estimated rating-impact bars, where negative means rating lost.
- Show the main finding with its games played, usual win rate, and plain-language reason.
- Filter by game speed, games per opening, and date range.
- Browse the opening table with sorting, a readable 20-row first page, and responsive cards on mobile.
- Explain the sample check, rating-impact estimate, and clock honesty in an expandable Method section.

The dashboard still focuses on sync and openings. The Phase 4 APIs exist, but the matching time-control, tilt, trend, and summary charts are the Phase 5 frontend work.

## Configuration

All values have safe local defaults and can be overridden with environment variables.

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_HOST` | `localhost` | PostgreSQL host |
| `DATABASE_PORT` | `5432` | PostgreSQL port |
| `DATABASE_NAME` | `gambitrate` | PostgreSQL database |
| `DATABASE_USER` | `gambitrate` | Database user |
| `DATABASE_PASSWORD` | `gambitrate` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | empty | Optional Redis password |
| `CHESSCOM_BASE_URL` | `https://api.chess.com` | Override for WireMock/testing |
| `CHESSCOM_USER_AGENT` | `GambitRate/1.0 (contact: you@example.com)` | Change the contact address before deploying |
| `CHESSCOM_REQUEST_DELAY` | `250ms` | Minimum pause between outbound calls |
| `CHESSCOM_MAX_ATTEMPTS` | `4` | Total attempts for a 429 response |
| `CHESSCOM_PAST_MONTH_TTL` | `3650d` | Redis TTL for immutable monthly archives |
| `CHESSCOM_CURRENT_MONTH_TTL` | `5m` | Redis TTL for the mutable current month |
| `CHESSCOM_ARCHIVES_TTL` | `5m` | Redis body TTL used for archive revalidation |
| `CHESSCOM_ETAG_TTL` | `7d` | ETag/Last-Modified revalidation metadata TTL |
| `CHESSCOM_HOST_LOCK_LEASE` | `2m` | Redis outbound lock lease |
| `CHESSCOM_HOST_LOCK_WAIT` | `60s` | Maximum wait for the distributed host gate |
| `SYNC_STALE_AFTER` | `2h` | Age after which a QUEUED/RUNNING job is treated as abandoned |
| `SYNC_FIXED_DELAY` | `1h` | Scheduled re-sync interval |
| `SYNC_INITIAL_DELAY` | `10m` | Delay before the first scheduled sweep |
| `TILT_SESSION_GAP` | `30m` | Gap that starts a new tilt session |
| `PORT` | `8080` | HTTP port |

## Tests

```bash
# Unit tests only
./mvnw test

# Unit + integration tests (Docker required)
./mvnw verify
```

The integration tests start PostgreSQL and Redis with Testcontainers. `SyncIngestionIT` also runs a WireMock chess.com server. They assert concrete values rather than just status codes:

- async POST returns `202`; polling reaches `DONE` with the right month progress;
- sync is idempotent: two games and eleven moves after two runs;
- PGN clocks, color, result, termination, opening, accuracy, `last_game_end_time`, and `is_player_move` are correct;
- variants are excluded and the descriptive `User-Agent` is sent;
- a second sync reads the immutable past month from Redis without another HTTP call;
- incremental sync selects only the current month and sends `If-None-Match`; a `304` reuses the cached body;
- the Redis host gate rejects a second concurrent caller until the first releases;
- the scheduler starts jobs for every tracked player and continues after one failure;
- opening groups have exact win rates, draw rates, rating deltas, colors, baselines, verdicts, ordering, and estimated Elo costs;
- date, time-class, `minGames`, and opening-line filters work;
- a 5-game sample is not flagged, while a 10-game sample with the same winrate is;
- unsynced players return `404`, reversed dates return `400`, and empty results return `200` with a message;
- time-control win rates, full-move buckets, termination shares, move-10/20/30 clock averages, coverage, and loss-clock shares match hand-calculated values;
- tilt post-loss/post-win rates, rating changes, session indexes, the exact 30-minute session boundary, New York hour/day grouping, and the tiny-sample guard are asserted;
- trends return exact monthly rating, win rate, game count, accuracy average, and accuracy coverage in chronological order;
- summary ranks a post-loss leak, an opening leak, and a time-class leak by exact estimated impact, and every item carries filters, sample size, baseline, and evidence;
- invalid timezones return `400`.

No test calls the live chess.com API.

## Design decisions

**Idempotency.** `game.chesscom_id` is unique; the sync skips IDs already present. This makes a repeated sync safe while treating completed games as immutable.

**Month-level atomicity.** Games from one monthly archive are parsed before persistence and written in one transaction. An error cannot leave a half-written month.

**PGN parsing.** chesslib loads and validates the PGN move text. GambitRate only extracts the clock annotation from comments; it does not treat clocks as move quality. `clock_remaining_ms` is time on the clock, not evidence of a blunder.

**Async sync jobs.** `POST /sync` persists a `sync_job`, returns `202`, and hands processing to a bounded executor. A partial unique index allows only one `QUEUED`/`RUNNING` job per player. Jobs older than two hours are marked `FAILED` so a process crash cannot block retries forever. `POST` still validates a first-time username synchronously to preserve the `404` contract.

**Outbound politeness.** A Redis lock leases the chess.com host to one caller at a time across instances. Requests are separated by a configurable delay and use a descriptive User-Agent. HTTP 429 honors `Retry-After` in seconds or HTTP-date form and otherwise backs off exponentially. Exhausted synchronous checks map to `503` with `Retry-After`; async failures are recorded on the job.

**Redis caching and revalidation.** Spring Cache stores raw monthly JSON in Redis: immutable past months use a 3,650-day TTL and are served without an HTTP call; the current month uses a 5-minute body TTL but is always re-fetched. ETags are stored in Redis, and current-month/archive requests send `If-None-Match`; a `304` reuses the cached body. Cache hits, misses, revalidations, status codes, and latency are logged.

**Incremental sync.** Each player tracks `last_synced_month` and `last_game_end_time`. Months older than `last_synced_month` are skipped unless they are the current month, which is always re-fetched. Progress updates after each fully committed month, so a retry starts from the last successful month; the unique chess.com game constraint keeps retries idempotent.

**Scheduling.** A configurable fixed-delay scheduled sweep enqueues syncs for every player with a `last_synced_at`. Active jobs are reused, so overlapping sweeps do not duplicate work.

**SQL-first analytics.** `/openings` filters and aggregates in PostgreSQL. A `LAG` window over each time class computes real rating changes between consecutive games; a `GROUP BY` produces color + ECO or opening-line groups. Java receives only the aggregate rows, applies the verdict guard, sorts, and serializes. No player history is loaded into memory.

**Verdicts are guarded.** Winrate deviation alone is not enough: a group needs at least five games and a two-sided 95% z-test against the same color's filtered baseline. A 5-game 20%-vs-60% group stays `fine`; the same rate gap over 10 games becomes a `leak`. Sample size and baseline are always returned with the verdict.

**Rating delta honesty.** chess.com's monthly payload does not include a post-game rating delta. `avgRatingDelta` is therefore the actual rating difference between consecutive games in the same time class, not an invented per-game Elo change. `ratingDeltaGames` exposes coverage, and the heuristic cost is labelled as an estimate.

**Date and timezone boundary.** `from` and `to` are inclusive calendar dates interpreted in UTC. Hour/day analytics use the explicit `tz` parameter and PostgreSQL `AT TIME ZONE`, so the conversion happens in SQL rather than in Java. `tz` is validated with `ZoneId`; invalid values return `400`. Date filters stay UTC so the same date range has one meaning across every endpoint.

**Game-length buckets.** Length is full moves, derived from the SAN token count: `1-19`, `20-39`, `40-59`, `60-79`, `80-99`, `100+`. The buckets are returned with a human label and are defined in the response notes.

**Clock coverage, not clock blame.** Clock at move N is the remaining clock after the player's Nth own move, found with a windowed `ROW_NUMBER` over `move` rows. Coverage is games with a clock at N divided by games in that time class. A game that never reaches move N lowers coverage; it is never treated as a zero clock. The final-clock loss buckets are `>30s`, `10-30s` inclusive, and `<10s` among losses with clock data, with coverage against all losses. These are time-management proxies, not evidence that a move was bad.

**Tilt sessions and score.** A session is a run of consecutive games with a gap under the configured threshold (30 minutes by default). A gap at or above the threshold starts a new session. The query computes session IDs, previous result, previous rating, and session game index with window functions. `tiltScore = (postWinWinRate - postLossWinRate) × 100`; positive means worse after a loss. A verdict requires `minGames` after both wins and losses and a two-proportion 95% z-test. The rating-change figure compares consecutive pre-game ratings in the same time class and exposes `ratingChangeSamples`.

**Accuracy coverage.** chess.com accuracy is sparse. Monthly accuracy is `AVG(player_accuracy)` over non-null values only, and every monthly and top-level accuracy figure carries `gamesWithAccuracy`, `ratingGames`, and/or `coverage`. Accuracy is never returned without the contributing sample.

**Summary ranking is a heuristic.** `/summary` considers guarded openings, time classes, game-length buckets, post-loss performance, and hour/day groups. Impact is `(baselineWinRate - groupWinRate) × games × 10`; positive means estimated Elo cost. Opening baseline is the same-color winrate; time-class/game-length/hour/day baselines are the overall filtered winrate; post-loss uses post-win. Only positive, `minGames`-qualified, 95%-guarded candidates are ranked. The value is explicitly an estimate, not a chess.com/Glicko calculation. Trends is descriptive and does not create a leak candidate.

**Analytics indexes.** `(player_id, time_class, played_at)` supports time-class-filtered session and trend scans; `(game_id, is_player_move, ply)` supports the player-move clock windows. The existing `(player_id, played_at)` index remains the main filter path.

**Honesty about data.** Accuracy is nullable and stored exactly as reported by chess.com. No blunder claims are made from clocks; that requires the optional Phase 6 engine.

## API errors

Every error uses one JSON shape:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Chess.com player 'nobody' was not found.",
  "timestamp": "2026-10-30T12:00:00Z",
  "path": "/api/players/nobody/sync"
}
```

| Condition | Status |
|---|---|
| Invalid username or filter | 400 |
| Unknown chess.com player | 404 |
| Player exists on chess.com but has not been synced | 404 |
| Player has no sync job yet | 404 |
| Chess.com failure or unparseable PGN | 502 |
| Synchronous chess.com rate-limit exhaustion | 503 + `Retry-After` |

Async ingestion failures are recorded in the sync job’s `error` field and do not change the already-returned `202`.

## Deployment

The service is live at **https://gambitrate.onrender.com**. `render.yaml` provisions the free Render web service, Postgres database, and Key Value cache. See [DEPLOYMENT.md](DEPLOYMENT.md) for setup and free-tier sleep caveats.

## Roadmap

1. **Phase 1 (done):** skeleton, Flyway schema, one-player ingestion, parsed moves.
2. **Phase 2 (done):** `/openings` with SQL-first aggregates, guarded verdicts, tests, Swagger.
3. **Phase 3 (done and live):** async jobs, Redis caching, distributed outbound gate, incremental sync, scheduler, Render deployment.
4. **Phase 4 (done in code):** `/time-controls`, `/tilt`, `/trends`, `/summary`.
5. Phase 5: complete one-page chart frontend with the Phase 4 data. The sync + openings dashboard is already live.
6. Phase 6 (optional): feature-flagged Stockfish analysis, only then `/blunders-by-clock`.
