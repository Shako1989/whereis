# whereis

Backend for the question **"Where did I put this item?"** — remember where physical items live
across your home, office, car, garage or warehouse, with full movement history, photos, search,
and an AI assistant for natural-language registration and lookup.

## Stack

Java 21 · Spring Boot 3.5 · Spring Web / Data JPA / Security (JWT via Nimbus) · PostgreSQL 16 +
Flyway · MinIO · MapStruct + Lombok · springdoc-openapi · JUnit 5 + Mockito + Testcontainers ·
Gradle · Docker Compose. Modular monolith, package-by-feature (`az.technest.whereis`).

## Quick start (local development)

```bash
# 1. Start dependencies (PostgreSQL + MinIO, with sensible dev defaults)
docker compose up -d postgres minio

# 2. Run the app with the local profile
./gradlew bootRun --args='--spring.profiles.active=local'
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- MinIO console: http://localhost:9001 (minioadmin / minioadmin)
- Health: http://localhost:8080/actuator/health

Full stack in Docker instead: copy `.env.example` to `.env`, set `JWT_SECRET`, then
`docker compose --profile app up --build`.

## Try the MVP journey

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"password123"}' | jq -r .accessToken)

curl -s -X POST localhost:8080/api/v1/spaces -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Home","type":"HOME"}'

curl -s -X POST localhost:8080/api/v1/assistant/remember -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"I put my passport in the bedroom wardrobe top drawer"}'

curl -s -X POST localhost:8080/api/v1/assistant/search -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"query":"Where is my passport?"}'
```

The default AI provider is `mock` — deterministic, offline, no API key. Set `AI_PROVIDER=openai`
plus `AI_API_KEY`/`AI_MODEL`/`AI_BASE_URL` to use any OpenAI-compatible endpoint.

## Tests

```bash
./gradlew test              # unit tests — no Docker needed
./gradlew integrationTest   # Testcontainers (PostgreSQL + MinIO) — requires a running Docker daemon
```

Integration tests are tagged `integration` and excluded from `build`/`test` so the build stays
green on machines without Docker. `MvpJourneyIT` covers the complete user journey end to end.

Behind a TLS-intercepting corporate proxy, pre-pull the images (`postgres:16-alpine`,
`minio/minio:RELEASE.2023-09-04T19-57-37Z`, the Ryuk helper) or configure a registry mirror via
`hub.image.name.prefix` in `~/.testcontainers.properties`. When Docker Hub is blocked outright,
point the tests at any reachable registry and disable the Ryuk sidecar:

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew integrationTest \
  -Dit.postgres.image=<mirror>/postgres:16-alpine \
  -Dit.minio.image=<mirror>/minio/minio:RELEASE.2023-09-04T19-57-37Z
```

(MinIO is also published on quay.io: `quay.io/minio/minio`.)

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost dev values | PostgreSQL connection |
| `JWT_SECRET` | — (required, ≥ 32 bytes) | HS256 signing key; app refuses to start without it |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | `15m` / `30d` | Token lifetimes |
| `MINIO_ENDPOINT` | `http://localhost:9000` | S3 API endpoint used by the app |
| `MINIO_EXTERNAL_ENDPOINT` | = endpoint | Host browsers use for presigned URLs |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | — (required) | MinIO credentials |
| `MINIO_BUCKET` | `item-images` | Bucket (auto-created at startup) |
| `AI_PROVIDER` | `mock` | `mock` or `openai` |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` | OpenAI defaults | Any OpenAI-compatible endpoint |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated origins |

Secrets live in the environment (or an untracked `.env`) — never in the repo.

## API overview (`/api/v1`)

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register`, `/auth/login`, `/auth/refresh` (rotating refresh tokens, reuse ⇒ family revoked) |
| Spaces | CRUD under `/spaces` |
| Locations | `POST/GET /spaces/{id}/locations`, `GET /spaces/{id}/location-tree`, `GET/PUT/DELETE /locations/{id}`, `GET /locations/{id}/children` |
| Items | CRUD under `/items` (paginated, max size 100), `POST /items/{id}/move`, `GET /items/{id}/history`, `GET /items/search?q=` |
| Files | `POST/GET /items/{id}/files` (multipart; JPEG/PNG/WebP, magic-byte checked), `DELETE .../{fileId}`, `GET .../{fileId}/url` (presigned) |
| Assistant | `POST /assistant/remember`, `/assistant/search`, `/assistant/images/analyze` |

Errors use one shape: `{timestamp, status, code, message, path}`.

## Design notes

- **Ownership**: userId always comes from the JWT subject; repositories expose only
  userId-scoped finders (enforced by an ArchUnit test). Misses return 404 — no existence oracle.
- **Location hierarchy**: single `locations` table with `parent_location_id`; sibling names
  unique per level (`UNIQUE NULLS NOT DISTINCT`); a composite FK enforces "parent in the same
  space" at the DB level; cycle checks + a per-space advisory lock serialize structural changes;
  paths resolve via one recursive CTE per batch (no N+1).
- **History**: `moveItem` runs in one transaction with a pessimistic item lock — close the open
  record, insert the new one, update `current_location_id`. A partial unique index guarantees at
  most one open record per item. History rows carry a path snapshot and survive location deletion.
- **PostgreSQL ↔ MinIO consistency** (no distributed transaction pretense): uploads put the
  object first, then write metadata, compensating on failure (worst case: invisible orphan
  object). Deletes remove metadata and enqueue the object key in `storage_deletion_queue` within
  one DB transaction; a post-commit sweep plus a scheduled janitor with exponential backoff
  guarantee eventual object removal.
- **AI is not a source of truth**: interpretations are validated (length, depth ≤ 6, charset,
  confidence), entity-resolved against the database, and ambiguity returns
  `NEEDS_CONFIRMATION` with zero writes. Locations are auto-created only inside the user's own,
  unambiguously resolved space. Assistant answers are composed from retrieved rows only.
  Swap providers via `ai.provider`; vector/semantic search can implement the `SearchService`
  port later without API changes.
# whereis
