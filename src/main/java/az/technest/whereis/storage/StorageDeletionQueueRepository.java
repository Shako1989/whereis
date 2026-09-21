package az.technest.whereis.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorageDeletionQueueRepository extends JpaRepository<StorageDeletionQueueEntry, UUID> {

    List<StorageDeletionQueueEntry> findTop50ByNextAttemptAtBeforeOrderByNextAttemptAtAsc(Instant now);

    /**
     * Enqueues one deletion per {@code item_files} row of the user in a single INSERT … SELECT —
     * no entity hydration, so an account with thousands of photos costs one statement. Must run
     * BEFORE the items are deleted: {@code item_files.item_id} is ON DELETE CASCADE, and once the
     * metadata rows are gone the object keys are unrecoverable.
     *
     * <p>{@code attempts}, {@code next_attempt_at} and {@code created_at} are supplied explicitly
     * because {@link StorageDeletionQueueEntry}'s {@code @PrePersist} does not run for a bulk insert.
     * {@code gen_random_uuid()} is built into PostgreSQL 13+. The bucket is copied per row, as every
     * other producer does, so a later bucket rename cannot strand an object.
     */
    @Modifying
    @Query(value = """
            INSERT INTO storage_deletion_queue (id, bucket, object_key, attempts, next_attempt_at, created_at)
            SELECT gen_random_uuid(), f.bucket, f.object_key, 0, now(), now()
            FROM item_files f
            JOIN items i ON i.id = f.item_id
            WHERE i.user_id = :userId
            """, nativeQuery = true)
    int enqueueAllFilesOfUser(@Param("userId") UUID userId);

    /**
     * The same statement for the PUBLIC copies of a user's photos. A second query rather than a
     * {@code UNION ALL} in the first, because the predicate differs ({@code published_object_key
     * IS NOT NULL}) and the two counts are separately meaningful in the deletion log line.
     *
     * <p>Without this, deleting an account would leave the published copies in MinIO with nothing
     * left in the database to find them by — the orphan the outbox exists to prevent, and on the
     * one class of object that is reachable from the open internet.
     *
     * <p><strong>{@code f.published_bucket}, never {@code f.bucket}</strong> (V14). A published copy
     * lives in the world-readable bucket, not the private one, so selecting {@code f.bucket} here
     * would enqueue a correct key against the wrong bucket: the janitor would delete nothing,
     * succeed, drop the row, and leave the public object on the internet forever. It is NOT NULL
     * whenever the key is, by {@code ck_item_files_published_pair} — which is what lets this insert
     * satisfy {@code storage_deletion_queue.bucket}'s NOT NULL.
     */
    @Modifying
    @Query(value = """
            INSERT INTO storage_deletion_queue (id, bucket, object_key, attempts, next_attempt_at, created_at)
            SELECT gen_random_uuid(), f.published_bucket, f.published_object_key, 0, now(), now()
            FROM item_files f
            JOIN items i ON i.id = f.item_id
            WHERE i.user_id = :userId AND f.published_object_key IS NOT NULL
            """, nativeQuery = true)
    int enqueuePublishedCopiesOfUser(@Param("userId") UUID userId);
}
