#!/bin/bash
#
# Nightly backup for everything on this box. Run from root's crontab at 03:00.
#
# History worth knowing: until 2026-09-19 this script dumped ONLY the `autoparts` database. It was
# written 2026-06-15, whereis was deployed in September, and it was never extended — so the whereis
# database and EVERY MinIO object (both applications') had no backup at all. deploy/README.md in
# the whereis repo documented a rotation this box never performed.
#
# Three artefacts per night, each independent: one failing must not skip the others, but the script
# still exits non-zero so a failure is visible rather than silent.
#
#   postgres-autoparts-*.sql.gz   pg_dump of autoparts
#   postgres-whereis-*.sql.gz     pg_dump of whereis
#   minio-*.tar.gz                the whole MinIO volume, every bucket
#
# The MinIO archive is a HOT copy: it reads the volume while the server is running, so an object
# being written at that instant can land torn. Accepted deliberately — objects are independent
# files, so the blast radius is that one photo rather than the archive, and the alternative is
# stopping MinIO nightly for both applications. Write volume here is a few photos a day.
#
# REMAINING GAP, not solved here: every copy lives on this box's only disk. That covers a dropped
# table or a bad deploy; it does NOT cover losing the VM or the disk. An off-box destination needs
# a target and credentials and is a separate decision.
#
# NOT ENCRYPTED, AND THE PUBLIC PAGES NO LONGER SAY IT IS. Both /legal/privacy and
# /legal/delete-account used to describe these artefacts as "encrypted backups" / "şifrələnmiş
# ehtiyat nüsxələr" in both languages. Nothing below encrypts anything — a dump is `pg_dump | gzip`
# and the object store is `tar czf` — so on 2026-09-20 the CLAIM was removed from the pages rather
# than encryption bolted on here, because that is the owner's decision and not a code change:
#
#   * it needs a passphrase or key, and somewhere to keep it that is NOT this box (a key stored
#     beside the ciphertext protects against nothing that matters — losing the disk loses both);
#   * it needs a tested restore path, because an encrypted backup nobody can decrypt under pressure
#     is worse than a plaintext one;
#   * gpg --symmetric --batch --passphrase-file, or openssl enc -aes-256-cbc -pbkdf2, would slot
#     into the two pipelines below with `set -o pipefail` already doing the right thing.
#
# If encryption is added, say so on BOTH pages in BOTH languages again, and add the assertion to
# LegalPagesIT — the page and this script are the pair that has to move together, exactly like
# RETENTION_DAYS below and WHEREIS_LEGAL_BACKUP_RETENTION_DAYS.

set -uo pipefail

BACKUP_DIR=/root/backups
LOG=/root/backups/backup.log
# MUST equal WHEREIS_LEGAL_BACKUP_RETENTION_DAYS in deploy/.env: that value is rendered into both
# public legal pages as a promise about how long deleted data survives, and only this rotation can
# make it true.
RETENTION_DAYS=14
DATE=$(date +%Y%m%d-%H%M)
mkdir -p "$BACKUP_DIR"

failed=0

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$LOG"; }

dump_db() {
  # Two statements, not one: in a single `local a=$1 b=$a` bash has already declared `b`'s name
  # when it evaluates `$a`, so under `set -u` the second reference fires "unbound variable".
  local db="$1"
  local out="$BACKUP_DIR/postgres-$db-$DATE.sql.gz"
  # pipefail matters here: without it a failing pg_dump still leaves a valid-looking .gz.
  if docker exec autoparts-postgres pg_dump -U autoparts "$db" | gzip > "$out"; then
    log "ok   $db $(du -h "$out" | cut -f1)"
  else
    log "FAIL $db — removing the partial dump so it cannot be mistaken for a good one"
    rm -f "$out"
    failed=1
  fi
}

dump_db autoparts
dump_db whereis

# A throwaway container with the volume mounted READ-ONLY: no credentials, no mc alias, and the
# running MinIO container is not touched. Captures every bucket, both applications'.
minio_out="$BACKUP_DIR/minio-$DATE.tar.gz"
if docker run --rm -v deploy_minio-data:/data:ro -v "$BACKUP_DIR":/out alpine:3 \
     tar czf "/out/minio-$DATE.tar.gz" -C /data . 2>/dev/null; then
  log "ok   minio $(du -h "$minio_out" | cut -f1)"
else
  log "FAIL minio — removing the partial archive"
  rm -f "$minio_out"
  failed=1
fi

# Retention applies per artefact kind, so a gap in one does not age out the others early.
find "$BACKUP_DIR" -maxdepth 1 -name 'postgres-*.sql.gz' -mtime +$RETENTION_DAYS -delete
find "$BACKUP_DIR" -maxdepth 1 -name 'minio-*.tar.gz'   -mtime +$RETENTION_DAYS -delete

log "done (retention ${RETENTION_DAYS}d, total $(du -sh "$BACKUP_DIR" | cut -f1)), failed=$failed"
exit $failed
