# whereis

Backend for "Where did I put this item?" — Java 21, Spring Boot 3.5, Gradle, PostgreSQL 16 +
Flyway, MinIO, JWT (Nimbus/oauth2-resource-server), package-by-feature under `az.technest.whereis`.

**Before implementing, reviewing, or planning anything here, load the `whereis-spec` skill** —
it holds the business rules, locked architecture decisions, database invariants, AI-safety
pipeline, and the review-confirmed guardrails that must not regress. Project agents live in
`.claude/agents/` (senior-java-backend-developer, senior-devops-engineer, senior-qa-tester).

## Commands

```bash
./gradlew build              # compile + unit tests (no Docker needed — must stay green)
./gradlew integrationTest    # Testcontainers ITs (needs Docker; see skill §7 for registry workarounds)
docker compose up -d postgres minio
./gradlew bootRun --args='--spring.profiles.active=local'   # Swagger: /swagger-ui.html
```

## Non-negotiables (details in the whereis-spec skill)

- Ownership from the JWT subject only; repositories expose only userId-scoped finders
  (ArchUnit-enforced); misses are 404.
- Flyway owns the schema (`ddl-auto: validate`); new change = new `V<n>__*.sql`; no `char(N)` columns.
- No MinIO/AI calls inside DB transactions; afterCommit DB writes need REQUIRES_NEW.
- AI output is untrusted until it passes `InterpretationValidator`; the DB is the only source of truth.
- Secrets come from the environment; never log tokens, passwords, or user messages above DEBUG.
