-- V14 — a published listing photo moves to a SECOND, world-readable bucket, and its camera
-- metadata is removed on the way.
--
-- TWO HOLES V12 LEFT OPEN IN THE PUBLISHED-PHOTO PATH. Neither fails anything at build time and
-- neither throws at runtime, which is why they are written down here.
--
--   1. EXIF, INCLUDING GPS, WAS PUBLISHED VERBATIM. Publishing was a server-side `copyObject`,
--      which is byte-identical by definition, and nothing anywhere in the application decoded or
--      rewrote an image. A published photo therefore carried whatever the camera wrote into it:
--      GPSLatitude, GPSLongitude, DateTimeOriginal, and the device make, model and serial number.
--      That defeats the product decision the whole marketplace was designed around — a listing
--      exposes a CITY and never the item's stored location, precisely so a seller is not telling
--      strangers where their things are. A crawler that downloaded one photo had the coordinates
--      anyway. The application now rewrites the container (ImageMetadataStripper: JPEG segments,
--      PNG chunks, RIFF chunks; keep only what a decoder needs for the pixels, drop everything
--      else) and REFUSES the publish if it cannot, rather than falling back to the original bytes.
--
--   2. THE PUBLIC COPY WAS IN THE PRIVATE BUCKET, SO ITS URL DIED IN TEN MINUTES. `copy()` used the
--      one configured bucket for both source and destination, so the board had to PRESIGN the
--      public copy — with `minio.presign-ttl`, one global property, default 10 minutes, shared with
--      every private photo. A board page's images were therefore cacheable by nobody (the signature
--      rotates per request) and a website would have been handed links that expire while somebody
--      is reading the page. `minio.presign-ttl` is deliberately UNCHANGED by this work: it still
--      governs every private photo.
--
-- WHAT THIS FILE ADDS: one column, one CHECK, and one unpublish of everything published so far.

-- ---------------------------------------------------------------------------------------------
-- 1. item_files.published_bucket — WHICH bucket the public copy is in.
-- ---------------------------------------------------------------------------------------------
-- There are now two buckets, and `item_files.bucket` means the PRIVATE one. Every consumer of the
-- deletion outbox already honours a per-row bucket (MinioAdapter.remove(bucket, key),
-- StorageCleanup, StorageJanitor) — that half of a two-bucket world genuinely needed no change.
-- The PRODUCERS did: `FileStorageService.publishedQueueEntry` and
-- `StorageDeletionQueueRepository.enqueuePublishedCopiesOfUser` both selected `f.bucket` for a
-- published copy, and after this change that is a correct key against the wrong bucket. The
-- janitor would have deleted nothing, reported success, dropped the queue row, and left the photo
-- on the open internet permanently.
--
-- RECORDED PER ROW rather than read from configuration at deletion time, for the same reason
-- `bucket` is: an outbox entry can outlive a bucket rename, and the janitor has to be able to name
-- the bucket the object is actually in. varchar(100) matches `item_files.bucket` and
-- `storage_deletion_queue.bucket` exactly. No index: it is read by primary key, or in the outbox's
-- INSERT ... SELECT, which scans one user's files.
ALTER TABLE item_files
    ADD COLUMN published_bucket varchar(100);

-- ---------------------------------------------------------------------------------------------
-- 2. Unpublish every copy made before this migration.
-- ---------------------------------------------------------------------------------------------
-- Those objects are byte-identical to the originals — they carry the GPS coordinates this change
-- exists to remove — and they are in the PRIVATE bucket, where no permanent public URL can reach
-- them. Leaving them would keep metadata-bearing images reachable by anyone holding a URL, which
-- is the wrong direction for a privacy fix, so they are enqueued for deletion and the columns are
-- cleared. `published_object_key IS NULL` is exactly what `publishPhoto` treats as "not published
-- yet", so a re-publish mints a fresh, stripped copy in the right bucket with no special case.
--
-- THE VISIBLE CONSEQUENCE, STATED RATHER THAN LEFT IMPLICIT: a listing published before this
-- migration stays ACTIVE and on the board, and loses its picture — `imageUrl` comes out null, the
-- same shape the board already degrades to when the public bucket is unconfigured. The seller's
-- route back is withdraw and publish again. There are no real listings yet, so today this costs
-- nothing; it is written this way because the alternative (keep serving them) is the hole.
--
-- The bucket column is `f.bucket` here and ONLY here: these particular copies really are in the
-- private bucket, which is the whole problem being undone.
INSERT INTO storage_deletion_queue (id, bucket, object_key, attempts, next_attempt_at, created_at)
SELECT gen_random_uuid(), f.bucket, f.published_object_key, 0, now(), now()
  FROM item_files f
 WHERE f.published_object_key IS NOT NULL;

UPDATE item_files
   SET published_object_key = NULL,
       published_bucket = NULL
 WHERE published_object_key IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 3. The pair invariant.
-- ---------------------------------------------------------------------------------------------
-- A key with no bucket is an object nothing can delete; a bucket with no key is a column nobody
-- reads. Both or neither — and this CHECK is load-bearing rather than tidy: it is what makes
-- `enqueuePublishedCopiesOfUser` able to satisfy `storage_deletion_queue.bucket`'s NOT NULL while
-- selecting `f.published_bucket` on the predicate `f.published_object_key IS NOT NULL`. Without it
-- the account-deletion cascade could abort on a NOT NULL violation, which is the Play-mandated
-- DELETE /users/me answering 500.
ALTER TABLE item_files
    ADD CONSTRAINT ck_item_files_published_pair
    CHECK ((published_object_key IS NULL) = (published_bucket IS NULL));
