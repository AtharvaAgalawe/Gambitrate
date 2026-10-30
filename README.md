# GambitRate

GambitRate ingests a public chess.com player's games and turns them into opinionated answers about what is hurting their rating. It is **not** a game viewer. Every analytics endpoint ends in numbers and a verdict.

**Current status: Phase 2.** One player can be ingested end to end, and `/openings` returns SQL-aggregated opening groups with sample-size-guarded verdicts. Redis caching, incremental sync, and the scheduler arrive in Phase 3.

## Architecture

```text
                         POST /api/players/{username}/sync
                                     |
                                     v
                            PlayerSyncController
                                     |
                                     v
                            PlayerSyncService
                     /               |                \
                    v                v                 v
            ChessComClient       PgnParser         GameFactory
            (RestClient,         (chesslib)        (result, color,
             serialized,               |            termination,
             429 backoff)              |            accuracy)
                    \                 |                 /
                     \                v                /
                      +------ monthly game list ------+
                                     |
                                     v
                         MonthPersistenceService
                         (one @Transactional unit)
                                     |
                                     v
                                PostgreSQL
                      player / game / move + Flyway

Redis is provisioned in Compose for Phase 3 caching and the distributed
outbound rate limiter. It is deliberately not used yet.
```

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

## Stack

- Java 21, Spring Boot 3.5, Maven
- Spring Web, Spring Data JPA, Bean Validation
- PostgreSQL 16, Flyway
- chesslib 1.3.7 for PGN parsing and move validation
- springdoc-openapi / Swagger UI
- JUnit 5, Testcontainers PostgreSQL, WireMock
- Docker Compose (app, PostgreSQL, Redis)

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

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw`.

## Ingest one player

```bash
curl -i -X POST http://localhost:8080/api/players/YOUR_CHESSCOM_USERNAME/sync
```

Phase 1 sync is intentionally synchronous and returns a summary:

```json
{
  "username": "exampleuser",
  "monthsProcessed": 18,
  "gamesSeen": 412,
  "variantsSkipped": 3,
  "gamesImported": 409,
  "gamesSkippedExisting": 0,
  "movesImported": 24481
}
```

Run the same command again. `gamesImported` must be `0`, `gamesSkippedExisting` must equal `gamesSeen`, and no duplicate rows are created.

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
      "verdictReason": "Within normal game-to-game variation: 50.0% wins vs a 56.3% white baseline over 94 games."
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

## Configuration

All values have safe local defaults and can be overridden with environment variables.

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/gambitrate` | JDBC URL |
| `DATABASE_USERNAME` | `gambitrate` | Database user |
| `DATABASE_PASSWORD` | `gambitrate` | Database password |
| `CHESSCOM_BASE_URL` | `https://api.chess.com` | Override for WireMock/testing |
| `CHESSCOM_USER_AGENT` | `GambitRate/1.0 (contact: you@example.com)` | Change the contact address before deploying |
| `CHESSCOM_REQUEST_DELAY` | `250ms` | Minimum pause between outbound calls |
| `CHESSCOM_MAX_ATTEMPTS` | `4` | Total attempts for a 429 response |
| `PORT` | `8080` | HTTP port |

## Tests

```bash
# Unit tests only
./mvnw test

# Unit + integration tests (Docker required)
./mvnw verify
```

The integration tests start PostgreSQL with Testcontainers. `SyncIngestionIT` also runs a WireMock chess.com server. They assert concrete values rather than just status codes:

- sync is idempotent: two games and eleven moves after two runs;
- PGN clocks, color, result, termination, opening, accuracy, and `is_player_move` are correct;
- variants are excluded and the descriptive `User-Agent` is sent;
- opening groups have exact win rates, draw rates, rating deltas, colors, baselines, verdicts, ordering, and estimated Elo costs;
- date, time-class, `minGames`, and opening-line filters work;
- a 5-game sample is not flagged, while a 10-game sample with the same winrate is;
- unsynced players return `404`, reversed dates return `400`, and empty results return `200` with a message.

No test calls the live chess.com API.

## Design decisions

**Idempotency.** `game.chesscom_id` is unique; the sync skips IDs already present. This makes a repeated sync safe while treating completed games as immutable.

**Month-level atomicity.** Games from one monthly archive are parsed before persistence and written in one transaction. An error cannot leave a half-written month.

**PGN parsing.** chesslib loads and validates the PGN move text. GambitRate only extracts the clock annotation from comments; it does not treat clocks as move quality. `clock_remaining_ms` is time on the clock, not evidence of a blunder.

**Outbound politeness.** Calls are serialized with a reentrant lock, separated by a configurable delay, and use a descriptive User-Agent. HTTP 429 respects `Retry-After` when present and otherwise backs off exponentially. Rate-limit exhaustion maps to `503 Service Unavailable` with a `Retry-After` header.

**No premature caching or incrementality.** Redis, ETag revalidation, immutable-month caching, incremental month selection, and scheduled sync are Phase 3.

**SQL-first analytics.** `/openings` filters and aggregates in PostgreSQL. A `LAG` window over each time class computes real rating changes between consecutive games; a `GROUP BY` produces color + ECO or opening-line groups. Java receives only the aggregate rows, applies the verdict guard, sorts, and serializes. No player history is loaded into memory.

**Verdicts are guarded.** Winrate deviation alone is not enough: a group needs at least five games and a two-sided 95% z-test against the same color's filtered baseline. A 5-game 20%-vs-60% group stays `fine`; the same rate gap over 10 games becomes a `leak`. Sample size and baseline are always returned with the verdict.

**Rating delta honesty.** chess.com's monthly payload does not include a post-game rating delta. `avgRatingDelta` is therefore the actual rating difference between consecutive games in the same time class, not an invented per-game Elo change. `ratingDeltaGames` exposes coverage, and the heuristic cost is labelled as an estimate.

**Date and timezone boundary.** `from` and `to` are inclusive calendar dates interpreted in UTC. Timezone-sensitive hour/day analytics arrive in Phase 4 with an explicit `tz` parameter.

**Honesty about data.** Accuracy is nullable and stored exactly as reported by chess.com. Future aggregate responses must include coverage. No blunder claims will be made from clocks.

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
| Chess.com failure or unparseable PGN | 502 |
| Chess.com rate-limit exhausted | 503 + `Retry-After` |

## Roadmap

1. **Phase 1 (done):** skeleton, Flyway schema, one-player ingestion, parsed moves.
2. **Phase 2 (done):** `/openings` with SQL-first aggregates, guarded verdicts, tests, Swagger.
3. Phase 3: Redis caching, distributed outbound rate limiter, incremental sync, scheduler, deploy.
4. Phase 4: `/time-controls`, `/tilt`, `/trends`, `/summary`.
5. Phase 5: one-page chart frontend.
6. Phase 6 (optional): feature-flagged Stockfish analysis, only then `/blunders-by-clock`.
