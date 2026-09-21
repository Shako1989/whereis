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

The Testcontainers suite (163 tests across 22 classes as of 2026-09-20, including `MvpJourneyIT`) is
the only place transaction boundaries, the MinIO deletion outbox, the location advisory locks, the
tier guard and the whole billing lifecycle — RTDN ordering, refunds, the reconciler, tier changes and
account deletion with a live subscription — are covered at all. `BillingDisabledProdIT` is the one
that covers **this** deployment: it boots the `prod` profile with no Play credentials at all and
proves the plan endpoint, both 409 walls, the 501 purchase refusal and the dead push endpoint.

Run this on a machine with working Docker before deploying anything, and **force the runs** — a
bare `build` returns `BUILD SUCCESSFUL` from cache having executed zero tests, which has already
produced one false green in this project:

```sh
./gradlew clean build --rerun-tasks && ./gradlew integrationTest --rerun-tasks
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
printf '%s\n' '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::whereis-item-images","arn:aws:s3:::whereis-public-images"]},{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::whereis-item-images/*","arn:aws:s3:::whereis-public-images/*"]}]}' > /tmp/whereis-policy.json
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

`s3:GetObject` on the second ARN pair matters even though this bucket does not exist yet: the app
READS the private original and WRITES the stripped public copy, so a publish needs both. Step 4b
creates the bucket itself.

**Do not run `mc anonymous set download` on this bucket.** That is correct for the BakuParts
cdn bucket, whose objects are deliberately world-readable; whereis item photos are private
user data served only through short-lived presigned GETs. It is also correct for the SEPARATE
public bucket in Step 4b — that separation is exactly why there are two buckets.

## Step 4b — Optional: the PUBLIC bucket for published marketplace photos (V14)

**Skip this and deploy.** With `WHEREIS_MINIO_PUBLIC_BUCKET` unset — which is how `.env.example`
ships — published photos are switched off: sellers publish listings, the anonymous board serves
them, and each listing simply has no image. Nothing fails to start and nothing answers 500. The
startup log states the mode in one INFO line. Come back to this step when you want listing photos.

**What the second bucket is for.** A published photo is a *copy*: the application rewrites the
image container to strip every scrap of camera metadata (GPS coordinates, capture time, device
make/model/serial) and writes the result under an opaque `p/{uuid}` key. That copy needs a
**permanent, unsigned URL** so a browser and a CDN can cache it — a listing page whose images are
presigned cannot be cached at all, because the signature rotates on every request, and a website
would be handed links that expire while somebody is reading the page. A world-readable bucket is
the only way to have that without weakening `MINIO_PRESIGN_TTL`, which governs every *private*
photo and is deliberately left alone.

**The application never creates this bucket**, on purpose: it cannot grant the anonymous-read
policy, and a bucket without that policy makes every listing image 403 with nothing saying why. A
configured-but-absent bucket is a startup WARN plus a 502 on publish, which is loud on purpose.

Reusing the shell variables from step 3. The policy grants **`s3:GetObject` and nothing else**:

```sh
printf '%s\n' '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::whereis-public-images/*"]}]}' > /tmp/whereis-public-policy.json
python3 -m json.tool /tmp/whereis-public-policy.json
```

**`s3:ListBucket` is deliberately absent, and `mc anonymous set download` is the wrong command
here** — it grants ListBucket too. The keys are opaque precisely so one photo cannot be tied to a
seller or an item; an anonymous bucket listing would hand over every key in a single request and
the opacity would buy nothing.

```sh
docker run --rm --network deploy_default \
  -v /tmp/whereis-public-policy.json:/policy.json:ro \
  -e RU="$MINIO_ROOT_USER" -e RP="$MINIO_ROOT_PASSWORD" \
  --entrypoint sh minio/mc -c '
    set -e
    mc alias set m http://minio:9000 "$RU" "$RP"
    mc mb --ignore-existing m/whereis-public-images
    mc anonymous set-json /policy.json m/whereis-public-images
    mc anonymous list m/whereis-public-images'

rm /tmp/whereis-public-policy.json
```

Then in `deploy/.env`:

```sh
WHEREIS_MINIO_PUBLIC_BUCKET=whereis-public-images
```

and `docker compose -f docker-compose.prod.yml --env-file .env up -d`. No rebuild.

Verify both halves — a published object must be readable and the bucket must not be listable:

```sh
# Publish a listing from the app, take its imageUrl, then:
curl -sI "<imageUrl>"                                     # 200, Cache-Control: public, max-age=86400
curl -s -o /dev/null -w '%{http_code}\n' \
     "https://$WHEREIS_MEDIA_HOST/whereis-public-images/"  # expect 403 — not listable
```

**It must not be the same bucket as `WHEREIS_MINIO_BUCKET`.** Anonymous read is granted per bucket,
so one bucket for both would publish every private item photo; the application refuses to start on
that rather than let it happen.

**Turning it back off** is one blank variable and a restart. Listings already published keep their
`published_object_key`, so their images keep working — the board reads the bucket out of the row,
not out of the configuration. Only NEW publishes go imageless.

## Step 5 — Add the Caddy vhosts

1. Append `Caddyfile.whereis` (in this directory) to `autoparts-api/deploy/Caddyfile`.
   **If you appended an earlier version of this file, re-copy the media vhost:** it now carries
   `log_skip`. Without it Caddy writes `/{bucket}/u/{userId}/i/{itemId}/{fileId}?X-Amz-Signature=…`
   to the access log on every image fetch — both UUIDs the marketplace design works hardest to keep
   apart, plus a live SigV4 signature — and `db_backup.sh` archives that log. Nothing breaks without
   the line, which is why it is called out here.
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

Healthy when the log shows `Started WhereisApplication` and Flyway reports 14 migrations applied.
You will also see one INFO line stating that Play Billing is not configured — that is the expected
state until Step 11e, and it lists everything the mode switches off. A second INFO line states
whether published marketplace photos are on or off (Step 4b); "DISABLED" is the expected state
until you have created the public bucket. `depends_on` cannot cross compose projects, so if Postgres is briefly unavailable the
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
curl -s -o /dev/null -w '%{http_code}\n' \
     https://$WHEREIS_MEDIA_HOST/whereis-public-images/    # 404 before Step 4b, 403 after
                                                           # (readable, never listable)
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
     https://$WHEREIS_API_HOST/play/rtdn                   # expect 401 — billing off denies all
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
> **The artefacts are NOT encrypted, and the public pages no longer say they are.** A dump is
> `pg_dump | gzip` and the object store is `tar czf`; there is no gpg and no openssl in the script.
> Both legal pages described "encrypted backups" in both languages until 2026-09-20, which on the
> two URLs Play cross-checks against the Data safety form is a false attestation — so the claim was
> removed from the pages rather than encryption added here. Adding it is an owner decision, not a
> code change: it needs a passphrase, a place to keep it that is **not this box** (a key beside the
> ciphertext protects against nothing that matters), and a tested restore path. The header comment
> in `deploy/db_backup.sh` carries the same note and the two commands that would do it. If it is
> ever added, put the claim back on **both pages in both languages** and add the assertion to
> `LegalPagesIT`.
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

The two files under `src/main/resources/legal/` are no longer edited at all — set the **seven**
`WHEREIS_LEGAL_*` values in `.env` and the app renders them at startup. A missing value FAILS
STARTUP rather than serving a literal `{{SUPPORT_EMAIL}}` to a reviewer:

| Variable | Meaning |
|---|---|
| `WHEREIS_LEGAL_SUPPORT_EMAIL` | mailbox that receives e-mail deletion requests (identity is verified before anything is removed) |
| `WHEREIS_LEGAL_ENTITY` | the legal name of the data controller |
| `WHEREIS_LEGAL_ADDRESS` | its postal address |
| `WHEREIS_LEGAL_EFFECTIVE_DATE` | the date the notice takes effect |
| `WHEREIS_LEGAL_BACKUP_RETENTION_DAYS` | how long deleted data can persist in backups. `14`, matching the rotation in `deploy/db_backup.sh`. Change both together or the page lies. |
| `WHEREIS_LEGAL_CANCELLATION_RETRY_DAYS` | how long a Google purchase token is kept after deletion, while the Play cancellation is retried. `7`. **`PlayCancellationJanitor` reads the same property as its give-up deadline**, so the code and the page cannot drift. |
| `WHEREIS_LEGAL_BILLING_LOG_RETENTION_DAYS` | how long a Google billing notification stays in the RTDN ledger (`play_notifications`). `30`. **`PlayNotificationJanitor` reads the same property as its cutoff.** A value of 7 or less is a **startup failure**, not a clamp: Pub/Sub redelivers an unacknowledged message for up to 7 days and `message_id` is what makes a redelivery a no-op. |

**Both pages changed in the V11 release and both must be re-read before submission.** They now
state what happens to a Google Play subscription when the account is deleted — including that the
cancellation request may never succeed, in which case the person may keep being charged and only
they can stop it — and `privacy.html` names Google as the billing processor and discloses the
purchase token. The two pages used to contradict each other about that retention period, which is a
compliance defect on its own; `LegalPagesIT` now asserts both halves in both languages.

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

## Step 10 — The tier ladder: grants, tuning, and the gates before promotion

`V9` gave every account `users.plan = 'FREE'`; `V10` widened that column to the four-tier ladder
plus the operator grant:

| tier        | spaces | active items | Play product              |
|-------------|--------|--------------|---------------------------|
| `FREE`      | 1      | 100          | —                         |
| `STANDARD`  | 3      | 300          | `whereis_standard_annual` |
| `PRO`       | 5      | 600          | `whereis_pro_annual`      |
| `MAX`       | 10     | **no limit** | `whereis_max_annual`      |
| `UNLIMITED` | no limit | no limit   | **never purchasable**     |

Neither migration contains an `UPDATE` — nothing is grandfathered, including the account that
already exists on this box. Nothing is deleted or hidden, but until it is granted that account
**cannot create a 5th space**.

An account's effective tier is `max(users.plan, its best entitling subscription)`. So a grant always
wins, a subscription can never downgrade a granted account, and **revoking a grant leaves a paying
subscriber on their paid tier**. `users.plan` is written by the migration's default or by you, and
by nothing else.

### Granting a tier by hand

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "UPDATE users SET plan = 'PRO' WHERE lower(email) = lower('you@example.com');"
```

Expect `UPDATE 1`. `UPDATE 0` means the e-mail does not match a row — check it, do not guess:

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "SELECT email, plan FROM users ORDER BY created_at;"
```

Valid values are `FREE`, `STANDARD`, `PRO`, `MAX`, `UNLIMITED`. To revoke, set it back to `'FREE'`.
Revoking takes nothing away: the account keeps every space and item it already has and can still
read, edit, move, archive and delete them — only creation is refused from then on.

> ### ⚠️ `V10` IS A ONE-WAY DOOR FOR GRANTS. READ BEFORE GRANTING ANY PAID TIER.
>
> `users.plan` is an `@Enumerated(STRING)` column, and the **pre-V10 image only knows
> `FREE` and `UNLIMITED`**. Flyway does not undo `V10`. So if you grant `STANDARD`/`PRO`/`MAX` and
> then roll the container back to an earlier image — which this runbook otherwise treats as routine —
> every read of that account throws: `GET /users/me/plan`, `POST /spaces`, `POST /items` and both
> assistant paths all answer 500, because the entitlement check runs on every creation. The account
> most likely to be granted first is yours, i.e. the one doing the rollback.
>
> **Do not grant a paid tier until the release has settled.** If you must roll back, run this FIRST,
> paste-ready:
>
> ```sh
> docker exec -i autoparts-postgres psql -U whereis -d whereis -c "UPDATE users SET plan = 'UNLIMITED' WHERE plan IN ('STANDARD','PRO','MAX');"
> ```
>
> `UNLIMITED` exists in both images, so the rolled-back build reads those rows fine (it just gives
> them more than they paid for, which is the safe direction).

### A time-boxed grant (an end date, without touching `users.plan`)

`users.plan` grants are permanent until you revoke them. For a tester who should lose access on a
date, write a `user_subscriptions` row instead — `provenance = 'OPERATOR'`, and `entitled_until` is
what makes it time-boxed. Only `STANDARD`, `PRO` and `MAX` are representable there; an *unlimited*
grant is permanent by construction and belongs on `users.plan`.

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "
INSERT INTO user_subscriptions (user_id, purchase_token, product_id, tier, provenance, state, entitled_until, acknowledged, verified_at)
SELECT id, 'operator:' || gen_random_uuid(), NULL, 'PRO', 'OPERATOR', 'ACTIVE', now() + interval '90 days', true, now()
FROM users WHERE lower(email) = lower('tester@example.com');"
```

The `operator:` token convention matters: `purchase_token` is globally unique and shares a namespace
with Google's real tokens, so an invented value that happened to collide would make a real paid
purchase permanently unredeemable. (It may also be left `NULL` for an `OPERATOR` row — the CHECK
allows that — but a greppable marker is worth more than a null during an incident.)

To revoke early, expire it rather than deleting it:

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "UPDATE user_subscriptions SET entitled_until = now() WHERE provenance = 'OPERATOR' AND user_id = (SELECT id FROM users WHERE lower(email) = lower('tester@example.com'));"
```

### Tuning the numbers

Every tier's ceilings and product id are `@ConfigurationProperties` (`whereis.plans.*`, defaulting in
`application.yml` to the table above), so retuning needs no code change. The environment names are
`WHEREIS_PLANS_<TIER>_SPACES` / `WHEREIS_PLANS_<TIER>_ITEMS` / `WHEREIS_PLANS_<TIER>_PRODUCT_ID` —
**these were renamed in this wave; the old `WHEREIS_LIMITS_FREE_*` names bind nothing and the app now
refuses to start if it sees one**, precisely so a stale `.env` cannot look healthy while production
tuning has silently reverted.

To override one here, add the passthrough to the `environment:` block of `docker-compose.prod.yml` —
it is deliberately NOT there today, because the defaults ARE the product rule and an unused knob in
the compose file invites drift:

```yaml
      WHEREIS_PLANS_FREE_SPACES: ${WHEREIS_PLANS_FREE_SPACES:-1}
      WHEREIS_PLANS_FREE_ITEMS: ${WHEREIS_PLANS_FREE_ITEMS:-100}
```

> **The ladder must never go down.** A higher tier may never allow less than a lower one (a blank
> value means "no ceiling" and counts as the largest). So raising `WHEREIS_PLANS_FREE_ITEMS` to
> `999999` on its own is a **startup failure**, not a working escape hatch — you must raise
> `STANDARD` and `PRO` to at least the same number. The container says exactly that and names both
> offending keys:
>
> ```
> whereis.plans.free.items (999999) exceeds whereis.plans.standard.items (300);
> a higher tier may never allow less — raise every tier above it too
> ```

### Play Billing configuration — deploy now with billing OFF

**Set nothing. Deploy.** There is a third mode, `whereis.play.provider=disabled`, it is the default
under the `prod` profile and in `docker-compose.prod.yml`, and it is what this deployment runs
until the Google Cloud service account and the Pub/Sub topic exist:

| what | with billing `disabled` |
|---|---|
| `GET /users/me/plan`, `GET /plans`, limits, usage, 409 `PLAN_LIMIT_REACHED` | **unchanged** |
| `POST /users/me/plan/purchases` | **501 `PLAY_BILLING_NOT_CONFIGURED`** — the client keeps the token |
| `POST /play/rtdn` | **401, empty body, no ledger row**, for every caller |
| reconciler / voided sweep / cancellation janitor | **do not run** (silently — one startup line states it) |
| `users.plan` grants (Step 10 below) | **unchanged** — they never involved Play |

Nothing can grant a tier by any route in this mode, which makes it strictly safer than either other
provider rather than a way round them. `docker compose up -d` works with **no** `WHEREIS_PLAY_*`
variable in `.env` at all.

Why this exists: those six variables used to be `:?`-required in `docker-compose.prod.yml`, so a
`docker compose up -d` without them took whereis **down** — for free-tier users too, who have
nothing to do with billing — over a feature nobody could use, because the service account and the
Pub/Sub topic are later steps in the launch plan. They are now optional there.

`PLAY_PROVIDER=fake` is still **refused under the `prod` profile** and the container will not start.
The fake's tokens are guessable literals (`fake-active-max`), so a production process running it
would hand the top tier to anyone who posted one. **If you hit a boot failure, the answer is
`disabled`, never `fake`** — the startup message says so.

Confirm the mode from the log after `up -d`:

```
Play Billing is NOT configured (whereis.play.provider=disabled): purchase verification answers
501 PLAY_BILLING_NOT_CONFIGURED, POST /play/rtdn rejects every caller, and the reconciler,
voided-purchase sweep and cancellation janitor will not run. Free-tier limits,
GET /users/me/plan and 409 PLAN_LIMIT_REACHED are unaffected.
```

Two legal values ARE still required (both `:?` in compose), because each is rendered into the
public pages **and** read by the component that has to honour it, so neither can drift:

```sh
WHEREIS_LEGAL_CANCELLATION_RETRY_DAYS=7
WHEREIS_LEGAL_BILLING_LOG_RETENTION_DAYS=30
```

### The refusals that replaced compose's `:?`

Relaxing compose without this would have traded a loud failure for a silent one, so the check moved
into the application, where it can be conditional — which `${VAR:?}` cannot be. Each is a startup
failure naming the property, the environment variable and the fix:

| configuration | what refuses to start | why it matters |
|---|---|---|
| `provider=google`, blank `PLAY_SERVICE_ACCOUNT_JSON` | `PlayConfig` | otherwise every purchase 502s |
| `rtdn.verifier=google`, blank `PLAY_RTDN_SHARED_SECRET` / `_AUDIENCE` / `_SERVICE_ACCOUNT_EMAIL` | `PlayRtdnConfig` | **blank REJECTS every push rather than skipping the check**, so the deployment would silently stop tracking Google — the worst failure mode in this design |
| `PLAY_RTDN_AUDIENCE` that looks like the push URL | `PlayRtdnConfig` | pasting the URL puts the shared secret into config and logs |
| `disabled` on only ONE of `PLAY_PROVIDER` / `PLAY_RTDN_VERIFIER` | `PlayBillingModeGuard` | a live Play API with a dead push endpoint lets a refunded subscriber keep a paid tier; a live push endpoint with no Play API behind it 500s on every delivery forever |

A typo in one of those variable names is therefore still a stopped container, not a
billing-shaped deployment with no credentials.

Everything about the Console and Cloud side — and the one Caddy line that stops the shared secret
being written to disk — is **Step 11**. **Switching billing on is Step 11e**, and it needs no code
change and no image rebuild.

### GATE before promoting a build past closed testing

> **0. BILLING IS OFF UNTIL STEP 11e IS DONE, and the deployed build says so.** With
> `whereis.play.provider=disabled` — the default — `POST /users/me/plan/purchases` answers **501
> `PLAY_BILLING_NOT_CONFIGURED`** and nothing can raise a tier except an operator grant. That is a
> deliberate, deployable state, not a broken one, and it is why the free tier can ship today. It
> also means gates 1–3 below do not apply yet: they become live the moment Step 11e is run, and
> whoever runs it owns them.
>
> **1. The wall's door is new and unfinished.** `POST /users/me/plan/purchases` verifies a Play
> purchase and raises the tier, but there is **no Play Console yet**: the three product ids in
> `whereis.plans.*.product-id` are assumptions. If any of them differs from what is actually created,
> `queryProductDetailsAsync` returns that product as *unfetched* and the app shows the tier with
> limits, no price and no button. Somebody must check the console against that config before the
> first paid build; it is an environment change, not a release.
>
> If billing is still not usable when you promote, one of these must be true instead:
>
> 1. the limits are raised out of reach — `WHEREIS_PLANS_FREE_SPACES` / `WHEREIS_PLANS_FREE_ITEMS`,
>    **and every tier above them**, per the monotonicity rule above; **or**
> 2. every account on the track is granted `UNLIMITED`.
>
> Shipping a paid wall with no way to pay is a Play policy problem as well as a product one.
>
> **2. REFUNDS ARE SWEPT NOW — but only if Step 11 is done.** `voidedPurchaseNotification` revokes
> immediately and a six-hourly sweep of `purchases.voidedpurchases.list` is the backstop. Both need
> the Pub/Sub subscription and the service account from **Step 11**; with neither in place, a
> refunded annual purchase still entitles for up to a year, because nothing tells this server about
> it. Check `SELECT count(*) FROM play_notifications;` after the first tester purchase: a table that
> is still empty a day later means the push subscription is not wired.
>
> **3. UNACKNOWLEDGED PURCHASES HAVE A 5-MINUTE FUSE ON A CLOSED TRACK, and the reconciler now
> retries them.** Google auto-refunds and revokes a purchase that is not acknowledged within 3 days —
> and within **5 minutes** for a test purchase, which is every purchase a license tester makes. The
> verify endpoint acknowledges synchronously with a short retry AND `SubscriptionReconciler` sweeps
> unacknowledged rows every 15 minutes ahead of everything else, so a failed acknowledgement no
> longer depends on the app being reopened. Watch for `Could not acknowledge purchase` at WARN, and
> for `Reconcile acknowledged subscription` at INFO — the second line means the first problem
> happened and was repaired.
>
> **4. THE THREE PRODUCT IDS AND THE BASE PLAN ID `annual` ARE STILL ASSUMPTIONS.** Unchanged from
> the previous wave, and still the only gate nothing in this repository can close.
>
> **5. UPGRADE AND DOWNGRADE BEHAVIOUR IS UNVERIFIED AGAINST A REAL CONSOLE.**
> `CHARGE_PRORATED_PRICE` for upgrades and `DEFERRED` for downgrades are product decisions, and
> whether a deferred downgrade issues a NEW purchase token or retains the old one is not settled by
> the available documentation. The server is deliberately correct either way — it refreshes from
> Google and resolves `linkedPurchaseToken`, with no branch on replacement mode — but whoever gets
> Console access should run ONE real upgrade and ONE real downgrade on the closed track and confirm
> the resulting token shape against `docs/ANDROID_APP_PROMPT.md`.
>
> **6. ACCOUNT DELETION CANCELS AT GOOGLE AND REFUNDS NOTHING.** That is what the public
> account-deletion page now says, in both languages, and it is a COMMERCIAL decision rather than a
> technical limit: `purchases.subscriptionsv2.revoke` exists in the pinned client and would refund
> the user and end access immediately. A human should sign that choice off before the page is
> submitted to Play.
>
> **7. NOTHING NOTICES IF RTDN STOPS ARRIVING.** A ledger with no rows for 48 hours is
> indistinguishable from a quiet week at this user count, and the reconciler papers over it. A
> log-based alert on "no `play_notifications` row in N hours" is the obvious follow-up and is
> deliberately not built.

## Step 11 — Google Play real-time developer notifications (Pub/Sub)

Everything in this step is **Console and Cloud work that no code in this repository can do or
verify**. The handler, the ledger, the reconciler, the refund sweep and the cancellation janitor all
ship; they receive nothing until this is done.

### 11a. The Pub/Sub topic and push subscription

1. In the Google Cloud project that owns the Play service account, create a Pub/Sub **topic**
   (e.g. `whereis-play-rtdn`) and grant `service-cloudpubsub@system.gserviceaccount.com` the
   **Pub/Sub Publisher** role on it. Play publishes as that account; without the grant the Console
   refuses to save the topic name.
2. In the Play Console → **Monetisation setup** → *Real-time developer notifications*, paste the full
   topic name and use **Send test notification**. A `TEST` row in `play_notifications` with
   `outcome = 'IGNORED'` is the confirmation, and the app logs
   `RTDN test notification received — the Pub/Sub push path is working` at INFO.
3. Create a **push** subscription on that topic with these settings. Every one of them is assumed by
   the handler:

   | setting | value | why |
   |---|---|---|
   | Delivery type | **Push** | the handler is an HTTPS endpoint, not a puller |
   | Endpoint URL | `https://<WHEREIS_API_HOST>/play/rtdn?key=<WHEREIS_PLAY_RTDN_SHARED_SECRET>` | check 1 |
   | Enable authentication | **on**, with a service account | check 2 |
   | **Audience** | **an explicit opaque string, e.g. `whereis-rtdn`** | see the warning below |
   | Acknowledgement deadline | **600 s** | the handler may make one Google call per message |
   | Retry policy | **exponential backoff**, min 10 s, max 600 s | a 500 from us must back off, not hammer |
   | Message retention | **7 days** | the longest outage the design survives without the reconciler |
   | Dead-letter topic | **none** | `play_notifications` IS the dead-letter store, and it is queryable |

> ### ⚠️ THE AUDIENCE FIELD IS OPTIONAL IN THE CONSOLE AND MUST NOT BE LEFT BLANK.
>
> When the audience is omitted, Cloud Pub/Sub sets the OIDC token's `aud` claim to **the full push
> endpoint URL — query string and shared secret included**. The app requires `aud` to equal
> `PLAY_RTDN_AUDIENCE`, so leaving it blank gives you two bad options: every genuine push is rejected
> on `aud` (401 → Pub/Sub retries → **entitlements quietly stop tracking Google**, which is the worst
> outcome this design has), or you "fix" it by pasting the URL into `PLAY_RTDN_AUDIENCE` — which
> writes the shared secret into `.env`, into the container's environment, and into every startup log
> that echoes bound properties, collapsing two independent checks into one compromised value.
>
> The app therefore **refuses to boot** if `PLAY_RTDN_AUDIENCE` starts with `http` or contains `?` or
> `key=`, with a message naming this trap.

4. The same service account needs the **Pub/Sub Subscriber** role, and its e-mail goes in
   `WHEREIS_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL`. That claim is what stops any Google-issued OIDC token
   for any project from passing: without it, the first four checks (signature, expiry, issuer,
   audience) are satisfiable by anyone with a Google Cloud account.

### 11b. Caddy must not log the shared secret

`deploy/Caddyfile.whereis` carries `log_skip @rtdn` for `/play/rtdn`. **Apply it.** The secret lives
in the URL's query string, Caddy's access log records the full URI by default, and the nightly backup
archives that log. If this is left undone the endpoint still works and still authenticates — check 2
is unaffected — so nothing breaks loudly, which is exactly why it is written down.

### 11c. What to watch in the logs

| line | level | what it means | action |
|---|---|---|---|
| `RTDN test notification received` | INFO | the push path works | none — this is the Step 11a confirmation |
| `Rejected an RTDN push … the shared secret did not match` | WARN | a probe, or `?key=` drifted from `.env` | compare the subscription's endpoint URL with `.env` |
| `Rejected an RTDN push … the push token did not verify` | WARN | `aud`/`email` mismatch, or the JWKS is unreachable | re-check 11a; **this one silently stops entitlement tracking** |
| `REVOKED subscription <digest> of user <id>` | WARN | a refund or chargeback was applied | none normally; this line is what makes an ERRONEOUS revoke findable |
| `Subscription reconcile is falling behind` | WARN | the sweep can no longer keep up (~1,200 live subscriptions) | raise `WHEREIS_PLAY_RECONCILE_BATCH_SIZE` or lower the delay |
| `Voided-purchase sweep stopped at its N-page cap` | WARN | more than ~20,000 refunds in a week | a business event before it is an engineering one |
| `GAVE UP cancelling Play subscription <digest> (<product>)` | **ERROR** | **a deleted account may still be being charged** | **see below — a human must act** |
| `RTDN <id> failed N times; giving up` | ERROR | a message hit the retry ceiling | the reconciler is the repair; check `processing_error` in the ledger |

**The `GAVE UP cancelling` alert is the one case in this wave where a human must act.** The account
and its e-mail address are gone, so there is no channel left to tell the user, and the public page
told them they might keep being charged. Cancel the subscription by hand in the Play Console using
the product id and the token digest from the log line:

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "SELECT purchase_token, product_id, attempts, last_error FROM play_cancellation_queue ORDER BY created_at;"
```

### 11d. Undoing a WRONG revocation — the only sanctioned way

A revocation is the one thing this system applies from a notification with **no Google
corroboration**: `voided_at` is write-once, a refresh may never clear it, the reconciler refuses to
run against a voided row, and the sweep only ever sets it. So a mistaken revoke — a crafted
notification, a Google-side error, a bug in a product filter — is permanent unless it is undone by
hand. Find it from the `REVOKED subscription` WARN line, then:

```sh
docker exec -i autoparts-postgres psql -U whereis -d whereis -c "
UPDATE user_subscriptions
   SET voided_at = NULL, verified_at = '1970-01-01Z'
 WHERE purchase_token = '<the token>' AND voided_at IS NOT NULL;"
```

Clearing `verified_at` as well is the point: it puts the row at the head of the reconciler's queue,
so **Google's answer — not the operator — restores the true state** within fifteen minutes. This is
the only sanctioned way `voided_at` is ever cleared; do not set it to a value of your own.

### 11e. Switching billing ON — the whole procedure

Billing ships **off** (`whereis.play.provider=disabled`, Step 10). Turning it on is an `.env`
change and a restart: **no code change, no image rebuild.** Do 11a–11c first — all six values below
come out of them.

1. Confirm the four prerequisites exist, because the app cannot check them for you: the Play
   service account and its JSON key, the Pub/Sub topic and push subscription with an **explicit
   opaque audience** (11a), the `log_skip` line in Caddy (11b), and the three product ids actually
   created in the Play Console (the GATE in Step 10 — still the one thing nothing in this
   repository can verify).

2. Set **all six together** in `.env`. Half of them is a startup failure, by design:

   ```sh
   WHEREIS_PLAY_PROVIDER=google
   WHEREIS_PLAY_PACKAGE_NAME=az.technest.whereis
   WHEREIS_PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account", ...}'

   WHEREIS_PLAY_RTDN_VERIFIER=google
   WHEREIS_PLAY_RTDN_SHARED_SECRET="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
   WHEREIS_PLAY_RTDN_AUDIENCE=whereis-rtdn
   WHEREIS_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL=whereis-rtdn@<project>.iam.gserviceaccount.com
   ```

   The shared secret must be the `?key=` of the push URL registered on the subscription, and
   `WHEREIS_PLAY_RTDN_AUDIENCE` must be the audience you set **explicitly** in the Console — never
   the push URL, which would put the secret into configuration and every startup log. The app
   refuses to boot on a URL-shaped value.

3. Restart and read the log:

   ```sh
   cd ~/whereis/deploy
   docker compose -f docker-compose.prod.yml --env-file .env up -d
   docker compose -f docker-compose.prod.yml --env-file .env logs -f whereis-api
   ```

   The `Play Billing is NOT configured` line must be **gone**. If the container exits instead, the
   message names the property and the variable — fix that, and do not fall back to
   `PLAY_PROVIDER=fake`, which the prod profile refuses anyway.

4. Prove it end to end, in this order — each step is the prerequisite for trusting the next:

   ```sh
   # a) the endpoint no longer says "not configured". 401 here (this curl carries no JWT) is the
   #    PASS: what matters is that it is no longer 501.
   curl -s -o /dev/null -w '%{http_code}\n' -X POST \
        -H 'Content-Type: application/json' \
        -d '{"purchaseToken":"x","productId":"whereis_pro_annual"}' \
        https://$WHEREIS_API_HOST/api/v1/users/me/plan/purchases

   # b) the push path works: Play Console -> Monetisation setup -> Send test notification,
   #    then look for the TEST row
   docker exec -i autoparts-postgres psql -U whereis -d whereis -c \
     "SELECT message_id, notification_kind, outcome, created_at
        FROM play_notifications ORDER BY created_at DESC LIMIT 5;"

   # c) the scheduled jobs are alive again — they were silent no-ops until now
   docker compose -f docker-compose.prod.yml --env-file .env logs whereis-api | grep -i reconcile
   ```

   If (b) is still empty a day after the first tester purchase, the push subscription is not wired
   — that is GATE 2 in Step 10, and refunds are not being caught.

5. **Switching back off is the same change in reverse**, and it is a legitimate incident response:
   set `WHEREIS_PLAY_PROVIDER=disabled` and `WHEREIS_PLAY_RTDN_VERIFIER=disabled` (both, or the
   container refuses to start) and restart. Existing `user_subscriptions` rows keep entitling until
   `entitled_until` passes — the entitlement query is pure database state and asks Google nothing —
   so nobody who paid loses access. What stops is new verification, notification handling and the
   three sweeps. Nothing is deleted and nothing is downgraded.


## Redeploy and rollback

```sh
git pull
docker compose -f docker-compose.prod.yml --env-file .env build
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

Note that images are built on the VM and tagged `latest`, so there is no previous image to
roll back to. If you want tagged rollbacks, set `WHEREIS_VERSION` per build and keep the old
tags — or move to a registry.

**Before rolling back past the V10 release**, run the `users.plan` repair statement in Step 10's
one-way-door box. Flyway does not undo a migration, and an older image cannot read a `STANDARD`,
`PRO` or `MAX` row.

---

## Step 12 — The marketplace (V12, BR-14)

The board at `GET /api/v1/market/listings` is the **first endpoint in this deployment reachable
without a JWT**. Two things are operational rather than code.

### 12a — the Caddy line the rate limiter depends on

`deploy/Caddyfile.whereis` now sets, inside the API vhost's `reverse_proxy`:

```caddyfile
header_up X-Forwarded-For {remote_host}
```

**Append it to the AutoParts Caddyfile and reload**, or the per-IP limiter on the public board is
bypassed with one curl header: `reverse_proxy` APPENDS to whatever the client sent and Spring reads
the FIRST entry. Nothing fails loudly without it.

```sh
docker exec autoparts-caddy caddy reload --config /etc/caddy/Caddyfile
# verify: a spoofed header must NOT get its own budget
for i in $(seq 1 40); do
  curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-For: 1.2.3.4' \
       "https://$WHEREIS_API_HOST/api/v1/market/listings"
done | sort | uniq -c        # expect some 429s
```

### 12b — taking ONE listing off the board

Hiding a single listing is still SQL: it acts on one row an operator is already looking at, and
the argument for keeping it out of the API is Step 10's — an admin endpoint needs an admin auth
model. **Blocking a SELLER is different and is an endpoint (Step 12e)**: it changes what a whole
account may do, and that has to leave a record of who decided and why by construction rather than
by the diligence of whoever was at the keyboard. **Review before you write:**

```sql
SELECT id, title, city, contact_phone, status, created_at FROM listings WHERE id = '<uuid>';
SELECT reason, note, reported_at FROM listing_reports WHERE listing_id = '<uuid>' ORDER BY reported_at DESC;
```

```sql
-- Hide it. The board 404s on the next request; the seller sees `hidden` + `hiddenReason`.
UPDATE listings
   SET hidden_at = now(), hidden_reason = 'PROHIBITED_ITEM', hidden_note = 'why, for a colleague'
 WHERE id = '<uuid>';
```

`hidden_reason` must be one of `PROHIBITED_ITEM`, `SCAM_OR_FRAUD`, `OFFENSIVE_CONTENT`,
`WRONG_OR_MISLEADING`, `ABUSE_REPORTS`, `OTHER` — a CHECK enforces it, and `hidden_at` and
`hidden_reason` must be set together.

**Hiding a listing does not remove its photo from the internet, and since V14 there is no TTL
doing it for you.** The published copy is at a permanent, unsigned public URL, so anyone who
already has that URL keeps fetching it until the OBJECT is deleted. (Before V14 the link expired
after `MINIO_PRESIGN_TTL`; that property now governs private photos only.) To take the photo down,
hand the object to the deletion outbox — the janitor removes it within a minute:

```sql
INSERT INTO storage_deletion_queue (id, bucket, object_key, attempts, next_attempt_at, created_at)
SELECT gen_random_uuid(), f.published_bucket, f.published_object_key, 0, now(), now()
  FROM item_files f JOIN listings l ON l.cover_file_id = f.id
 WHERE l.id = '<uuid>' AND f.published_object_key IS NOT NULL;

-- ...and clear the pair, or the board keeps offering a URL that now 404s. Both columns together:
-- ck_item_files_published_pair refuses one without the other.
UPDATE item_files f
   SET published_object_key = NULL, published_bucket = NULL
  FROM listings l
 WHERE l.cover_file_id = f.id AND l.id = '<uuid>';
```

**`f.published_bucket`, never `f.bucket`.** The public copy is in the world-readable bucket; the
private original is not. Enqueueing the right key against the wrong bucket makes the janitor delete
nothing, report success, drop the queue row, and leave the photo up permanently.

A copy a browser or a CDN already cached survives for up to a day (the object's own
`Cache-Control: public, max-age=86400`). That bound is stated on `/legal/privacy`.

Un-hiding is `SET hidden_at = NULL, hidden_reason = NULL` — it restores nothing, because it
overwrote nothing.

### 12c — the report queue

```sql
SELECT l.id, l.title, count(*) AS reports
  FROM listing_reports r JOIN listings l ON l.id = r.listing_id
 WHERE r.reviewed_at IS NULL
 GROUP BY l.id, l.title ORDER BY reports DESC;

UPDATE listing_reports SET reviewed_at = now(), review_outcome = 'UPHELD' WHERE id = '<uuid>';
```

**Nothing alerts on report volume** — a quiet queue is indistinguishable from a quiet week. Check
it when you check the backup log.

### 12d — the listing cap

`whereis.plans.<tier>.listings` (FREE 1, STANDARD 3, PRO 10, MAX 25), retunable per environment as
`WHEREIS_PLANS_PRO_LISTINGS` and so on. The same monotonicity rule as the other two allowances:
raising a lower tier means raising every tier above it, or startup fails naming both keys.

### 12e — blocking a SELLER (V13)

Hiding listings one at a time does not stop somebody who keeps posting: ten listings cost ten
operations and nothing refuses the eleventh. **Google Play's user-generated-content policy requires
a way to block a USER**, not only a way to remove content, and this is it.

**A block is a marketplace sanction, not an account action.** Every listing the account has leaves
the public board on the next request and they cannot publish another. Their items, spaces,
locations, photos and history are untouched, they keep reading and editing all of it, and they can
still withdraw or mark sold the listings they already have.

#### Step 1 — configure who may do it, ONCE

```sh
# deploy/.env — comma-separated. UNSET MEANS NOBODY, which is what it means today.
WHEREIS_MARKETPLACE_MODERATOR_EMAILS=you@example.com
```

Then restart whereis. The address must be an account that **exists and can log in** — the operator
is a user of their own application and the JWT they already have is the credential. There is no
role column and no admin account; this allowlist is the whole authorization model, and an unset or
mistyped value answers **403 `NOT_A_MODERATOR`** to everybody rather than opening the endpoint up.
The same address is written into `blocked_sellers.blocked_by`, so the action is attributable.

#### Step 2 — find the seller behind a reported listing

```sql
-- Reported listings, worst first, WITH the seller id the block needs.
SELECT l.user_id AS seller_id, l.id AS listing_id, l.title, l.city, count(*) AS reports
  FROM listing_reports r JOIN listings l ON l.id = r.listing_id
 WHERE r.reviewed_at IS NULL
 GROUP BY l.user_id, l.id, l.title, l.city
 ORDER BY reports DESC;

-- Everything that seller has ever published, to judge a PATTERN rather than one row.
SELECT id, title, price_amount, city, status, hidden_at, created_at
  FROM listings WHERE user_id = '<seller_id>' ORDER BY created_at DESC;
```

#### Step 3 — block

```sh
TOKEN=$(curl -s -X POST "https://$WHEREIS_API_HOST/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"…"}' | jq -r .accessToken)

curl -i -X POST "https://$WHEREIS_API_HOST/api/v1/moderation/sellers/<seller_id>/block" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"SCAM_OR_FRAUD","note":"three upheld reports, same fake IMEI"}'
# 204 No Content
```

`reason` must be one of `SCAM_OR_FRAUD`, `PROHIBITED_ITEMS`, `OFFENSIVE_CONTENT`,
`SPAM_OR_BULK_LISTINGS`, `REPEATED_VIOLATIONS`, `OTHER` — a CHECK enforces it and **the seller is
shown it**, so pick the one you would be willing to defend to them. `note` is optional, capped at
500 characters, internal, and never appears in any response. Blocking an already-blocked account
replaces the reason, note, author and timestamp instead of adding a row. Every still-open report
against any of their listings is closed as `UPHELD` in the same transaction, so 12c's queue does
not re-surface what you just acted on.

Other answers: **403 `NOT_A_MODERATOR`** (you are not on the allowlist, or nobody is),
**404 `USER_NOT_FOUND`** (wrong seller id — the expected mistake, since you pasted it),
**400** (a `reason` that is not in the list).

#### Step 4 — verify, and unblock

```sql
SELECT user_id, reason, note, blocked_by, blocked_at FROM blocked_sellers;
```

```sh
curl -i -X DELETE "https://$WHEREIS_API_HOST/api/v1/moderation/sellers/<seller_id>/block" \
  -H "Authorization: Bearer $TOKEN"
# 204 — and every listing they still have is back on the board, unchanged
```

**Unblocking really does restore the board**, because blocking mutated nothing: the listings stayed
`ACTIVE` with `hidden_at` NULL and the board simply stopped selecting them. That is also why a
per-listing hide you applied earlier (12b) survives an unblock — the two decisions are independent
and neither erases the other. Unblocking an account that was not blocked is a 204, not a 404.

**What a block deliberately does NOT do**, so nobody expects it:

* It does not delete the published photo copies. A block is reversible and destroying the artefact
  would leave an unblocked listing the board cannot show a picture for. Since V14 those copies are
  at permanent public URLs, so anyone holding one keeps fetching it — there is no TTL that ends it.
  12b's `storage_deletion_queue` insert is the escape hatch, and it is now the ONLY thing that
  takes a published photo off the internet.
* It does not survive account deletion. The row is keyed on `users.id` and cascades, so a blocked
  seller who uses `DELETE /users/me` and registers again is a new account with no sanction. Keeping
  anything about a deleted person would contradict `/legal/delete-account`. **A block is not an
  identity ban** — if the same person returns, block them again.
* It does not keep a history of lifted blocks. The row is deleted on unblock and the application
  log line is the only surviving record, so grep for `unblocked seller` rather than expecting a
  table.
* Nothing alerts on any of this. Check 12c's queue when you check the backup log.

