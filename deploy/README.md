# Deploying whereis onto the existing Hetzner VM

whereis is a **co-tenant** of the AutoParts/BakuParts stack, not a standalone deployment.
That stack already owns host ports 80/443 (Caddy) and loopback 5432/9000/9001, so whereis
adds exactly one container and borrows the rest:

```
                     Hetzner VM
  ┌──────────────────────────────────────────────┐
  │ autoparts-caddy   :80 :443                   │
  │   api.bakuparts…  → autoparts-api:8080       │
  │   cdn.bakuparts…  → minio:9000  (public read)│
  │   $WHEREIS_API_HOST   → whereis-api:8080  ◄──┼── new
  │   $WHEREIS_MEDIA_HOST → minio:9000 (presign) ◄┼── new
  │                                              │
  │ autoparts-api      autoparts-postgres        │
  │ whereis-api  ◄─new   ├── autoparts (db)      │
  │                      └── whereis   (db) ◄─new│
  │                    autoparts-minio           │
  │                      ├── autoparts-* buckets │
  │                      └── whereis-item-images ◄── new (PRIVATE)
  └──────────────────────────────────────────────┘
```

All four containers share the Docker network the AutoParts project created — `deploy_default`
unless that stack was started with `-p`. **Confirm before you start:** `docker network ls`.

---

## Step 0 — Gate: the integration suite must pass first

Per the project spec, the 17 Testcontainers tests including `MvpJourneyIT` have never run
green. Transaction boundaries, the MinIO deletion outbox, and the location advisory locks are
all only covered there. Run this on a machine with working Docker before deploying anything:

```sh
./gradlew build && ./gradlew integrationTest
```

## Step 1 — DNS

Two A records pointing at the VM's public IP, **DNS-only / not proxied** — a proxy in front
would terminate TLS upstream and break Caddy's ACME challenge:

| Type | Name | Value |
|---|---|---|
| A | `api.whereis…` | VM public IP |
| A | `files.whereis…` | VM public IP |

## Step 2 — Create the database inside the existing Postgres

```sh
# The AutoParts POSTGRES_USER is a superuser on that instance.
docker exec -i autoparts-postgres psql -U <POSTGRES_USER> -d postgres <<'SQL'
CREATE ROLE whereis LOGIN PASSWORD 'PASTE_WHEREIS_DB_PASSWORD';
CREATE DATABASE whereis OWNER whereis;
SQL
```

`OWNER whereis` matters: since PostgreSQL 15 the `public` schema no longer grants CREATE to
everyone, and it is owned by `pg_database_owner`. Making the role the database owner is what
lets Flyway create tables without extra grants.

Then create the extension **as the superuser**, because `V1__extensions.sql` runs
`CREATE EXTENSION IF NOT EXISTS pg_trgm` and an ordinary role cannot do that. Pre-creating it
makes Flyway's V1 a validated no-op:

```sh
docker exec -i autoparts-postgres psql -U <POSTGRES_USER> -d whereis \
  -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm;'
```

## Step 3 — Create a private bucket and scoped MinIO user

```sh
cat > /tmp/whereis-policy.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:GetBucketLocation"],
      "Resource": ["arn:aws:s3:::whereis-item-images"] },
    { "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::whereis-item-images/*"] }
  ]
}
JSON

docker run --rm --network deploy_default \
  -v /tmp/whereis-policy.json:/policy.json:ro \
  -e ROOT_USER=<MINIO_ROOT_USER> \
  -e ROOT_PASSWORD=<MINIO_ROOT_PASSWORD> \
  -e WHEREIS_SECRET=<WHEREIS_MINIO_SECRET_KEY> \
  --entrypoint sh minio/mc -c '
    set -e
    mc alias set m http://minio:9000 "$ROOT_USER" "$ROOT_PASSWORD"
    mc mb --ignore-existing m/whereis-item-images
    mc admin policy create m whereis-rw /policy.json
    mc admin user add m whereis "$WHEREIS_SECRET"
    mc admin policy attach m whereis-rw --user whereis'

rm /tmp/whereis-policy.json
```

`s3:ListBucket` is not optional — `MinioAdapter.ensureBucket()` issues a `HeadBucket` at
startup and the app fails to boot without it.

**Do not run `mc anonymous set download` on this bucket.** That is correct for the BakuParts
cdn bucket, whose objects are deliberately world-readable; whereis item photos are private
user data served only through short-lived presigned GETs.

## Step 4 — Add the Caddy vhosts

1. Append `Caddyfile.whereis` (in this directory) to `autoparts-api/deploy/Caddyfile`.
2. Add these two lines to that stack's `caddy` service `environment:` block:
   ```yaml
         WHEREIS_API_HOST: ${WHEREIS_API_HOST}
         WHEREIS_MEDIA_HOST: ${WHEREIS_MEDIA_HOST}
   ```
3. Set both in `autoparts-api/deploy/.env`.
4. Recreate Caddy so it picks up the new env vars (a `caddy reload` alone will not — reload
   re-reads the config but not the container's environment):
   ```sh
   cd autoparts-api/deploy
   docker compose -f docker-compose.prod.yml --env-file .env up -d caddy
   ```

Caddy requests Let's Encrypt certs for both new hostnames on their first request.

## Step 5 — Build and start whereis

```sh
git clone https://github.com/Shako1989/whereis.git
cd whereis/deploy
cp .env.example .env
nano .env          # fill in the values from steps 1-3

docker compose -f docker-compose.prod.yml --env-file .env build
docker compose -f docker-compose.prod.yml --env-file .env up -d
docker compose -f docker-compose.prod.yml --env-file .env logs -f whereis-api
```

A 2 vCPU box builds this in a few minutes. If the Gradle build gets OOM-killed while the
other stack is running, build with `--memory` headroom or temporarily stop `autoparts-api`.

Healthy when the log shows `Started WhereisApplication` and Flyway reports 7 migrations
applied. `depends_on` cannot cross compose projects, so if Postgres is briefly unavailable the
app retries via `flyway.connect-retries: 10` and then `restart: unless-stopped`.

## Step 6 — Verify

```sh
curl -s https://$WHEREIS_API_HOST/actuator/health          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' \
     https://$WHEREIS_API_HOST/swagger-ui.html             # expect 401 — prod disables docs
curl -s -o /dev/null -w '%{http_code}\n' \
     https://$WHEREIS_API_HOST/v3/api-docs                 # expect 401
curl -s -o /dev/null -w '%{http_code}\n' \
     https://$WHEREIS_API_HOST/api/v1/spaces               # expect 401 — auth required
curl -s -o /dev/null -w '%{http_code}\n' \
     https://$WHEREIS_MEDIA_HOST/whereis-item-images/x     # expect 403 — bucket is private
```

Then register a user, upload a photo, and confirm the returned presigned URL opens on a phone
off Wi-Fi. That last check is the one that catches a wrong `WHEREIS_MEDIA_HOST`.

## Step 7 — Backups

Postgres is the only source of truth and photo binaries are unrecoverable without their
metadata rows, so both must be captured together. Add to root's crontab:

```cron
15 3 * * * docker exec autoparts-postgres pg_dump -U <POSTGRES_USER> -Fc whereis \
             > /var/backups/whereis-$(date +\%F).dump 2>/dev/null
30 3 * * * find /var/backups -name 'whereis-*.dump' -mtime +14 -delete
```

Copy `/var/backups` and the `minio-data` volume off the box — a backup that only lives on the
VM does not survive losing the VM.

## Redeploy and rollback

```sh
git pull
docker compose -f docker-compose.prod.yml --env-file .env build
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

Note that images are built on the VM and tagged `latest`, so there is no previous image to
roll back to. If you want tagged rollbacks, set `WHEREIS_VERSION` per build and keep the old
tags — or move to a registry.
