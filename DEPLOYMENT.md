# Deployment

The repository includes a Render Blueprint in `render.yaml`.

## What the Blueprint creates

- A free Docker web service running the GambitRate image.
- A free Render Postgres database.
- A free Render Key Value (Redis-compatible) instance.
- Environment wiring for JDBC, Redis, and the required User-Agent contact.

## One-time setup

1. Push this repository to GitHub.
2. In Render, choose **New → Blueprint** and select the repository.
3. Render creates `gambitrate`, `gambitrate-db`, and `gambitrate-cache`.
4. Set `CHESSCOM_USER_AGENT` when prompted, using a real contact:
   `GambitRate/1.0 (contact: you@example.com)`.
5. Wait for the Docker build and health check.
6. Swagger: `https://<service>.onrender.com/swagger-ui.html`

Migrations run automatically on startup through Flyway.

## Free-tier caveats

- Free web services sleep after inactivity. The first request after sleep is slow.
- The in-process `@Scheduled` sync only runs while the web service is awake.
- Render Key Value free instances have 25 MB and no persistence.
- Postgres free-tier limits and expiration policies apply.
- If the service sleeps, trigger a request before demonstrating Swagger or `/openings`.

## Manual environment variables

| Variable | Source |
|---|---|
| `DATABASE_HOST` | Render Postgres host |
| `DATABASE_PORT` | Render Postgres port |
| `DATABASE_NAME` | Render Postgres database |
| `DATABASE_USER` | Render Postgres user |
| `DATABASE_PASSWORD` | Render Postgres password |
| `REDIS_HOST` | Render Key Value host |
| `REDIS_PORT` | Render Key Value port |
| `REDIS_PASSWORD` | Optional Redis password |
| `CHESSCOM_USER_AGENT` | Your contact-bearing User-Agent |
| `SYNC_FIXED_DELAY` | Optional, default `1h` |
| `SYNC_INITIAL_DELAY` | Optional, default `10m` |

No secrets are stored in the repository.
