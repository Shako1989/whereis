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

The Testcontainers suite (61 tests as of 2026-09-19, including `MvpJourneyIT`) is the only place
transaction boundaries, the MinIO deletion outbox, the location advisory locks and the free-tier
guard are covered at all. Run this on a machine with working Docker before deploying anything:

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

## Step 7b — Optional: switch the assistant to Claude

The default `WHEREIS_AI_PROVIDER=mock` needs no key and is fully functional in English. To use a
real model instead:

1. Create an API key at <https://console.anthropic.com> and **set a spend limit on it**. The app
   has no per-user AI quota, so any authenticated user can loop `/api/v1/assistant/remember`; the
   spend limit is the only ceiling. Haiku 4.5 costs roughly $0.0015 per remember call and about
   a third of that per search, at $1/$5 per MTok.
2. In `deploy/.env`:

   ```sh
   WHEREIS_AI_PROVIDER=claude
   WHEREIS_AI_CLAUDE_API_KEY=sk-ant-...
   ```

3. `docker compose -f docker-compose.prod.yml up -d --force-recreate whereis-api`

If the key is **identity-linked** (the Console may issue this kind), every call comes back
`400 anthropic-workspace-id is required when authenticating with an identity-linked API key`,
surfacing as `502 AI_UNAVAILABLE`. Either set `WHEREIS_AI_CLAUDE_WORKSPACE_ID` to the workspace's
id (Console → Settings → Workspaces; it looks like `wrkspc_...`), or issue a workspace-scoped key
instead and leave that variable blank.

A blank key with `provider=claude` is a **loud** failure, not a silent one: startup aborts with
`IllegalStateException: ai.claude.api-key (AI_CLAUDE_API_KEY) must be configured when
ai.provider=claude`, so check `docker logs whereis-api` if the container will not come up.

Verify the provider is actually the one answering — a sentence the mock also parses proves
nothing, because both would return `CREATED`. Use one the mock cannot handle:

```sh
TOKEN=...   # accessToken from POST /api/v1/auth/login
curl -s -X POST https://$WHEREIS_API_HOST/api/v1/assistant/remember \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"message":"the spare house keys ended up inside the blue box on the garage shelf"}'
```

That phrasing matches none of the mock's verb patterns, so `"status":"CREATED"` with a sensible
`locationPath` means Claude answered. `NOT_UNDERSTOOD` means the provider is still `mock`;
`502 AI_UNAVAILABLE` means the key or network is wrong.

## Step 8 — Backups

Postgres is the only source of truth and photo binaries are unrecoverable without their
metadata rows, so both must be captured together. Add to root's crontab:

```cron
15 3 * * * docker exec autoparts-postgres pg_dump -U autoparts -Fc whereis \
             > /var/backups/whereis-$(date +\%F).dump 2>/dev/null
30 3 * * * find /var/backups -name 'whereis-*.dump' -mtime +14 -delete

> **REWRITTEN 2026-09-19.** The cron line above was documented but never carried out. What the box
> actually ran was `/root/db_backup.sh`, and that script dumped **only the `autoparts` database** —
> it was written 2026-06-15, whereis was deployed in September, and it was never extended. So the
> whereis database and **every MinIO object, both applications'**, had no backup at all. Verified on
> the box: the old dumps in `/root/backups/` contained none of `spaces`, `items`, `locations` or
> `assistant_messages`.
>
> The replacement is version-controlled in this repo as `deploy/db_backup.sh`. Install it with:
>
> ```sh
> scp deploy/db_backup.sh root@$VM:/root/db_backup.sh && ssh root@$VM chmod +x /root/db_backup.sh
> ssh root@$VM 'crontab -l | grep -q db_backup || (crontab -l; echo "0 3 * * * /root/db_backup.sh") | crontab -'
> ```
>
> It writes three artefacts a night, each independent so one failure cannot skip the others, and
> exits non-zero so a failure is visible rather than silent — it also appends to
> `/root/backups/backup.log`, which is the only place a cron failure would otherwise be seen:
>
> | artefact | what |
> |---|---|
> | `postgres-autoparts-*.sql.gz` | `pg_dump` of autoparts |
> | `postgres-whereis-*.sql.gz` | `pg_dump` of whereis |
> | `minio-*.tar.gz` | the whole MinIO volume, every bucket |
>
> Retention is 14 days per artefact kind, which is what
> `WHEREIS_LEGAL_BACKUP_RETENTION_DAYS=14` in `.env` promises the user.
>
> The MinIO archive is a **hot copy** — read from the volume while the server runs, so an object
> being written at that instant can land torn. Accepted: objects are independent files, so the
> blast radius is that one photo rather than the archive, and the alternative is stopping MinIO
> nightly for both applications.
>
> **Verified after installing, and worth repeating after any change** — "a file exists" is not "a
> backup exists". Row counts from the dump matched the live database exactly
> (`users|spaces|locations|items|item_files|assistant_messages` = `1|4|11|19|19|9`), the MinIO
> archive held the same 38 files as the volume, and all three passed `gzip -t`.
>
> **Remaining gap, not solved:** every copy lives on this box's only disk. That covers a dropped
> table or a bad deploy; it does not cover losing the VM. An off-box destination needs a target and
> credentials and is a separate decision.
```

Copy `/var/backups` and the `minio-data` volume off the box — a backup that only lives on the
VM does not survive losing the VM.

## Step 9 — Google Play: account-deletion and privacy URLs

Play requires every app with account creation to offer deletion in-app **and** via a public web
page. The backend serves both pages itself, unauthenticated, from the API vhost — no Caddy change,
the whole `{$WHEREIS_API_HOST}` vhost already proxies to `whereis-api:8080`:

```
Data deletion URL : https://$WHEREIS_API_HOST/legal/delete-account
Privacy policy URL: https://$WHEREIS_API_HOST/legal/privacy
```

Both pages ship with **placeholders that must be replaced before a submission** — a page showing a
literal `{{SUPPORT_EMAIL}}` will fail review. Edit the two files under
`src/main/resources/legal/`. They are no longer edited at all — set the five `WHEREIS_LEGAL_*` values in `.env` and the app renders them at startup:

| Placeholder | Meaning |
|---|---|
| `{{SUPPORT_EMAIL}}` | mailbox that receives e-mail deletion requests (identity is verified before anything is removed) |
| `{{LEGAL_ENTITY}}` | the legal name of the data controller |
| `{{LEGAL_ADDRESS}}` | its postal address |
| `{{EFFECTIVE_DATE}}` | the date the notice takes effect |
| `WHEREIS_LEGAL_BACKUP_RETENTION_DAYS` | how long deleted data can persist in backups. `14`, matching the rotation in `deploy/db_backup.sh`. Change both together or the page lies. |

```sh
# Nothing to grep any more. The pages moved out of src/main/resources/static/ to
# src/main/resources/legal/ precisely so the static resource handler could not serve an
# unrendered one, and the values now arrive from .env at startup. A missing value FAILS
# STARTUP (LegalPages), and LegalPagesIT asserts no served page contains "{{" on either URL
# form. Verify the live pages instead:
curl -s https://$WHEREIS_API_HOST/legal/privacy | grep -o "{{[A-Z_]*}}" && echo "PLACEHOLDERS LEFT" || echo "ok"
```

Verify after deploy (no token on any of these):

```sh
curl -s -o /dev/null -w '%{http_code}\n' https://$WHEREIS_API_HOST/legal/delete-account   # 200
curl -s -o /dev/null -w '%{http_code}\n' https://$WHEREIS_API_HOST/legal/privacy          # 200
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE https://$WHEREIS_API_HOST/api/v1/users/me  # 401
```

The endpoint the app calls is `DELETE /api/v1/users/me` with `{"password": "..."}`; wrong password
→ 401 and nothing deleted. Photo binaries are removed by the janitor from `storage_deletion_queue`
within minutes (50 objects per 60 s sweep) — `select count(*) from storage_deletion_queue;` should
trend to zero after a deletion. Note for whoever signs the privacy statement: a deleted account
genuinely persists in the Step 8 dumps until they rotate out; there is no process that scrubs a
user from an existing dump.

## Step 10 — Free-tier limits: grant UNLIMITED (do this right after the first deploy)

`V9` gives every account `users.plan = 'FREE'`, which allows **1 space** and **100 ACTIVE items**.
The migration deliberately contains **no `UPDATE`** — nothing is grandfathered, including the
account that already exists on this box. Nothing is deleted or hidden, but until it is granted that
account **cannot create a 5th space** (it currently holds 4 spaces and 19 active items).

So do this immediately after the deploy, for your own account and for every tester who should not
hit the wall. There is no endpoint and no admin API for it, by design — it is one statement:

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "UPDATE users SET plan = 'UNLIMITED' WHERE lower(email) = lower('you@example.com');"
```

Expect `UPDATE 1`. `UPDATE 0` means the e-mail does not match a row — check it, do not guess:

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "SELECT email, plan FROM users ORDER BY created_at;"
```

To revoke a grant, set it back to `'FREE'`. Revoking takes nothing away: the account keeps every
space and item it already has and can still read, edit, move, archive and delete them — only
creation is refused from then on.

**Why by hand.** `UNLIMITED` is a *grant* for specific accounts (you, testers, close
acquaintances), not a fact about when an account was created, which is exactly what a migration
could not express. It is also **not** subscription state and must never be merged with it: when
billing lands, a Play RTDN reporting an expiry will write "no longer subscribed" somewhere, and if
that somewhere were this column it would silently erase the grants you made here. The entitlement
rule becomes `plan = 'UNLIMITED' OR active subscription` inside one method
(`PlanLimitEnforcer#hasUnlimitedEntitlement`); this column stays operator-only.

Tuning the limits needs no code change: both are `@ConfigurationProperties`
(`whereis.limits.free.spaces` / `.items`, defaulting to 1 and 100 in `application.yml`). To override
one here, add the passthrough to the `environment:` block of `docker-compose.prod.yml` — it is
deliberately NOT there today, because the defaults ARE the product rule and an unused knob in the
compose file invites drift:

```yaml
      WHEREIS_LIMITS_FREE_SPACES: ${WHEREIS_LIMITS_FREE_SPACES:-1}
      WHEREIS_LIMITS_FREE_ITEMS: ${WHEREIS_LIMITS_FREE_ITEMS:-100}
```

### GATE before promoting a build past closed testing

> **The wall has no door yet.** Billing is not implemented — no Play Billing product, no purchase
> flow, no subscription endpoint. A `FREE` account that reaches 1 space or 100 active items is
> refused with `409 PLAN_LIMIT_REACHED` and **cannot pay to get past it**. The only ways forward are
> archiving an item (which frees room) or an operator grant.
>
> That is intentional for closed testing — testers are meant to exercise the wall. It must **not**
> reach an open track or production that way. Before promoting a build to open testing or
> production, one of these must be true:
>
> 1. billing is implemented and a purchase actually grants unlimited use; **or**
> 2. the limits are raised high enough to be unreachable (`WHEREIS_LIMITS_FREE_*`) so no user is
>    refused a creation they cannot resolve; **or**
> 3. the accounts on the track are all granted `UNLIMITED`.
>
> Shipping a paid wall with no way to pay is a Play policy problem as well as a product one.

## Redeploy and rollback

```sh
git pull
docker compose -f docker-compose.prod.yml --env-file .env build
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

Note that images are built on the VM and tagged `latest`, so there is no previous image to
roll back to. If you want tagged rollbacks, set `WHEREIS_VERSION` per build and keep the old
tags — or move to a registry.
