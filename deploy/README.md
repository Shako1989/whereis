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

## Step 2 — Swap, clone, and generate secrets

A 3.7 GB box with no swap is the tightest constraint here. There is enough memory to *run*
whereis, but not to run a Gradle build beside a live JVM — and with no swap an allocation spike
goes straight to the OOM killer, which picks the largest RSS process. That is `autoparts-api`,
so a failed whereis build can take BakuParts down with it. Check and fix first:

```sh
free -h
```

If the Swap row reads `0B`:

```sh
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl -w vm.swappiness=10 && echo 'vm.swappiness=10' >> /etc/sysctl.conf
free -h
```

Then clone and write the three secrets straight into `.env`, so they never pass through your
clipboard or scrollback:

```sh
cd ~ && git clone https://github.com/Shako1989/whereis.git
cd ~/whereis/deploy && cp .env.example .env
```

```sh
sed -i "s|^WHEREIS_DB_PASSWORD=.*|WHEREIS_DB_PASSWORD=$(openssl rand -base64 32)|" .env
sed -i "s|^WHEREIS_MINIO_SECRET_KEY=.*|WHEREIS_MINIO_SECRET_KEY=$(openssl rand -base64 32)|" .env
sed -i "s|^WHEREIS_JWT_SECRET=.*|WHEREIS_JWT_SECRET=$(openssl rand -base64 48)|" .env
```

`|` is safe as the sed delimiter because the base64 alphabet is `A-Za-z0-9+/=` — it contains
neither `|` nor `&`.

Finally set the values only you know:

```sh
nano .env
```

- `WHEREIS_API_HOST` / `WHEREIS_MEDIA_HOST` — the two hostnames from step 1.
- `SHARED_NETWORK` — leave as `deploy_default` unless `docker network ls` disagrees.
- `WHEREIS_MEM_LIMIT` — `1g` on a 4 GB box. At the 768m default, `MaxRAMPercentage=75` leaves
  only ~190m for metaspace, code cache, thread stacks and the MinIO SDK's direct buffers,
  which is enough to risk a cgroup kill.

## Step 3 — Create the database inside the existing Postgres

Every command here is a single line by design. Do NOT reach for a heredoc: pasting one into
a terminal usually indents the closing delimiter, and a terminator that is not at column 0
never matches, so the shell hangs waiting for input that never comes.

First load the credentials. `eval` on a grep keeps them as plain shell variables rather than
exported ones, so they cannot leak into whereis's compose interpolation later:

```sh
cd ~/whereis/deploy
eval "$(grep -E '^(POSTGRES_USER|MINIO_ROOT_USER|MINIO_ROOT_PASSWORD)=' ~/autoparts-api/deploy/.env)"
DBPW=$(grep '^WHEREIS_DB_PASSWORD=' .env | cut -d= -f2-)
MINIOPW=$(grep '^WHEREIS_MINIO_SECRET_KEY=' .env | cut -d= -f2-)
echo "pg_user=[$POSTGRES_USER] dbpw_len=${#DBPW} miniopw_len=${#MINIOPW}"
```

Both lengths must be 44 and `pg_user` must be non-empty before you continue. Reading the
secrets back out of `.env` rather than regenerating them is deliberate — `.env` is what the
container will use, so these are the only values that can be correct.

The role and the database are two separate invocations because `CREATE DATABASE` cannot run
inside a transaction block:

```sh
docker exec -i autoparts-postgres psql -U "$POSTGRES_USER" -d postgres -c "CREATE ROLE whereis LOGIN PASSWORD '$DBPW';"
```

```sh
docker exec -i autoparts-postgres psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE whereis OWNER whereis;"
```

Expect `CREATE ROLE` and `CREATE DATABASE`. If the role ends up with the wrong password you do
not need to start over: `ALTER ROLE whereis PASSWORD '$DBPW';` fixes it in place.

`OWNER whereis` matters: since PostgreSQL 15 the `public` schema no longer grants CREATE to
everyone, and it is owned by `pg_database_owner`. Making the role the database owner is what
lets Flyway create tables without extra grants.

Then create the extension **as the superuser**, because `V1__extensions.sql` runs
`CREATE EXTENSION IF NOT EXISTS pg_trgm` and an ordinary role cannot do that. Pre-creating it
makes Flyway's V1 a validated no-op:

```sh
docker exec -i autoparts-postgres psql -U "$POSTGRES_USER" -d whereis -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

Verify both before moving on — `pg_trgm` should be listed and the role should exist:

```sh
docker exec -i autoparts-postgres psql -U "$POSTGRES_USER" -d whereis -c "\dx" -c "\du whereis"
```

## Step 4 — Create a private bucket and scoped MinIO user

Reusing the shell variables loaded in step 3. Write the policy as one single-quoted line — again,
no heredoc:

```sh
printf '%s\n' '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::whereis-item-images"]},{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::whereis-item-images/*"]}]}' > /tmp/whereis-policy.json
```

Confirm it parses, because a malformed policy fails inside `mc` in a confusing way:

```sh
python3 -m json.tool /tmp/whereis-policy.json
```

The `mc` body below is safe to paste as-is: it is a single-quoted argument to `sh -c`, so its
indentation carries no meaning and there is no delimiter to misalign.

```sh
docker run --rm --network deploy_default \
  -v /tmp/whereis-policy.json:/policy.json:ro \
  -e RU="$MINIO_ROOT_USER" -e RP="$MINIO_ROOT_PASSWORD" -e WS="$MINIOPW" \
  --entrypoint sh minio/mc -c '
    set -e
    mc alias set m http://minio:9000 "$RU" "$RP"
    mc mb --ignore-existing m/whereis-item-images
    mc admin policy create m whereis-rw /policy.json
    mc admin user add m whereis "$WS"
    mc admin policy attach m whereis-rw --user whereis'

rm /tmp/whereis-policy.json
```

Re-running `mc admin user add` with a different secret is how you correct a mismatched key
later; it overwrites rather than erroring.

`s3:ListBucket` is not optional — `MinioAdapter.ensureBucket()` issues a `HeadBucket` at
startup and the app fails to boot without it.

**Do not run `mc anonymous set download` on this bucket.** That is correct for the BakuParts
cdn bucket, whose objects are deliberately world-readable; whereis item photos are private
user data served only through short-lived presigned GETs.

## Step 5 — Add the Caddy vhosts

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

## Step 6 — Build and start whereis

Everything is already configured by step 2, so this is just build and up:

```sh
cd ~/whereis/deploy
docker compose -f docker-compose.prod.yml --env-file .env build
docker compose -f docker-compose.prod.yml --env-file .env up -d
docker compose -f docker-compose.prod.yml --env-file .env logs -f whereis-api
```

A 2 vCPU box builds this in a few minutes; watch `free -h` in another shell. With the swapfile
from step 2 this should hold, but if the build is still OOM-killed, `docker stop autoparts-api`
for the duration and start it again afterwards.

Healthy when the log shows `Started WhereisApplication` and Flyway reports 7 migrations
applied. `depends_on` cannot cross compose projects, so if Postgres is briefly unavailable the
app retries via `flyway.connect-retries: 10` and then `restart: unless-stopped`.

## Step 7 — Verify

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

## Step 8 — Backups

Postgres is the only source of truth and photo binaries are unrecoverable without their
metadata rows, so both must be captured together. Add to root's crontab:

```cron
15 3 * * * docker exec autoparts-postgres pg_dump -U autoparts -Fc whereis \
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
