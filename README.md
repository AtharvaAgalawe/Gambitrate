# GambitRate

GambitRate ingests a public chess.com player's games and turns them into opinionated answers about what is hurting their rating. It is **not** a game viewer. Every analytics endpoint ends in numbers and a verdict.

**Current status: Phase 3 implementation.** Sync runs as a persisted async job with progress polling. Redis caches immutable months, revalidates the current month with ETags, and serializes outbound chess.com calls across instances. Incremental sync and a scheduled re-sync job are in place. The Render Blueprint is ready; a live URL requires Render account authorization.

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

- API: `http://localhost:8080`
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
- unsynced players return `404`, reversed dates return `400`, and empty results return `200` with a message.

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
| Player has no sync job yet | 404 |
| Chess.com failure or unparseable PGN | 502 |
| Synchronous chess.com rate-limit exhaustion | 503 + `Retry-After` |

Async ingestion failures are recorded in the sync job’s `error` field and do not change the already-returned `202`.

## Deployment

`render.yaml` provisions the free Render web service, Postgres database, and Key Value cache. See [DEPLOYMENT.md](DEPLOYMENT.md) for the one-time setup and free-tier sleep caveats. The repository is deployment-ready but has not been published to a live host from this session because no Render account authorization is available.

## Roadmap

1. **Phase 1 (done):** skeleton, Flyway schema, one-player ingestion, parsed moves.
2. **Phase 2 (done):** `/openings` with SQL-first aggregates, guarded verdicts, tests, Swagger.
3. **Phase 3 (implementation done, deploy pending authorization):** async jobs, Redis caching, distributed outbound gate, incremental sync, scheduler, Render Blueprint.
4. Phase 4: `/time-controls`, `/tilt`, `/trends`, `/summary`.
5. Phase 5: one-page chart frontend.
6. Phase 6 (optional): feature-flagged Stockfish analysis, only then `/blunders-by-clock`.
